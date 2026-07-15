package fpt

import chisel3._
import chisel3.util._

/** Two-way transposed prefetch frontend for the batched CMUX engine. */
final class BitwisePrefetchedBatchedCmuxCoefficientStore(
    override val config: CmuxCoefficientConfig,
    override val batchContexts: Int,
    val bitsPerCycle: Int
) extends BatchedCmuxCoefficientStoreBase(config, batchContexts) {
  import TransformUtil._
  require(batchContexts >= 2)

  private val contextWidth = counterWidth(batchContexts)
  private val rows = config.components * config.levels
  private val rowWidth = counterWidth(rows)
  private val commandInterval = rows * config.forwardBeats
  private val cooldownWidth = counterWidth(commandInterval)
  private val coreCount = 2
  private val coreWidth = counterWidth(coreCount)
  private val commandQueueEntries = batchContexts + 2
  private val commandQueuePointerWidth = counterWidth(commandQueueEntries)
  private val commandQueueCountWidth = counterWidth(commandQueueEntries + 1)

  private val memoryConfig = ReplicatedAccumulatorBanksConfig(
    config,
    batchContexts
  )
  require(
    memoryConfig.halfBeats <= commandInterval,
    "bitwise accumulator prefetch must fit inside the command interval"
  )

  val memory = Module(new ReplicatedAccumulatorBanks(memoryConfig))
  val cores = Seq.fill(coreCount) {
    Module(new BitwiseCmuxForwardFrontend(config, bitsPerCycle))
  }

  memory.io.loadStart := io.loadStart
  memory.io.loadContext := io.loadContext
  memory.io.loadValid := io.loadValid
  memory.io.load := io.load
  io.loadReady := memory.io.loadReady
  io.loadDone := memory.io.loadDone
  io.loadDoneContext := memory.io.loadDoneContext
  io.contextLoaded := memory.io.contextLoaded

  memory.io.updateValid := io.updateValid
  memory.io.updateFirst := io.updateFirst
  memory.io.updateContext := io.updateContext
  memory.io.updateLow := io.updateLow
  memory.io.updateHigh := io.updateHigh
  io.updateReady := memory.io.updateReady
  io.updateDone := memory.io.updateDone
  io.updateDoneContext := memory.io.updateDoneContext

  val busy = RegInit(VecInit(Seq.fill(batchContexts)(false.B)))
  io.contextBusy := busy

  val commandCooldown = RegInit(0.U(cooldownWidth.W))
  val fillOutstanding = RegInit(false.B)
  val fillCore = RegInit(0.U(coreWidth.W))
  val fillIsDrain = RegInit(false.B)
  val coreExponent = Reg(Vec(coreCount, UInt(config.exponentWidth.W)))
  val coreStartReady = VecInit(cores.map(_.io.prefetchStartReady))
  val hasReadyCore = coreStartReady.asUInt.orR
  val selectedCore = PriorityEncoder(coreStartReady.asUInt)

  val commandAvailable = memory.io.contextLoaded(io.commandContext) &&
    (!busy(io.commandContext) ||
      (memory.io.updateDone &&
        memory.io.updateDoneContext === io.commandContext))
  // This small explicit FIFO exposes both the active core and its successor.
  // Queue only exposes the head, but the successor must see streamEnable on
  // the active command's final pair to provide SGen's next frame marker.
  val commandCores = Reg(Vec(commandQueueEntries, UInt(coreWidth.W)))
  val commandReadPointer = RegInit(0.U(commandQueuePointerWidth.W))
  val commandWritePointer = RegInit(0.U(commandQueuePointerWidth.W))
  val commandCount = RegInit(0.U(commandQueueCountWidth.W))
  val commandQueueFull = commandCount === commandQueueEntries.U

  def nextCommandPointer(pointer: UInt): UInt =
    Mux(
      pointer === (commandQueueEntries - 1).U,
      0.U,
      pointer + 1.U
    )

  io.commandReady := commandCooldown === 0.U &&
    memory.io.prefetchReady && !fillOutstanding && hasReadyCore &&
    !commandQueueFull && commandAvailable
  val commandFire = io.commandValid && io.commandReady
  when(commandFire) {
    commandCores(commandWritePointer) := selectedCore
    commandWritePointer := nextCommandPointer(commandWritePointer)
    commandCooldown := (commandInterval - 1).U
  }.elsewhen(commandCooldown =/= 0.U) {
    commandCooldown := commandCooldown - 1.U
  }
  when(io.commandValid) {
    assert(io.commandContext < batchContexts.U, "invalid command context")
  }

  val drainActive = RegInit(false.B)
  val drainCore = RegInit(0.U(coreWidth.W))
  val drainContextReg = RegInit(0.U(contextWidth.W))
  val drainCanStart = !drainActive && !fillOutstanding &&
    memory.io.prefetchReady && hasReadyCore && commandCount === 0.U &&
    !io.commandValid && io.drainContext < batchContexts.U &&
    !busy(io.drainContext)
  io.drainStartReady := drainCanStart
  val drainFire = io.drainStart && drainCanStart
  when(io.drainStart) {
    assert(drainCanStart, "bitwise drain started while unavailable")
    assert(io.drainContext < batchContexts.U, "invalid drain context")
  }

  memory.io.prefetchStart := commandFire || drainFire
  memory.io.prefetchContext := Mux(
    commandFire,
    io.commandContext,
    io.drainContext
  )
  when(commandFire || drainFire) {
    fillOutstanding := true.B
    fillCore := selectedCore
    fillIsDrain := drainFire
    when(commandFire) {
      for (core <- 0 until coreCount) {
        when(selectedCore === core.U) {
          coreExponent(core) := io.exponent
        }
      }
    }.otherwise {
      drainCore := selectedCore
      drainContextReg := io.drainContext
    }
  }
  when(memory.io.prefetchDone) {
    fillOutstanding := false.B
  }

  for ((core, index) <- cores.zipWithIndex) {
    val selectedFill = fillCore === index.U
    core.io.loadStart := false.B
    core.io.loadValid := false.B
    core.io.load := 0.U.asTypeOf(core.io.load)
    core.io.prefetchStart := (commandFire || drainFire) &&
      selectedCore === index.U
    core.io.prefetchValid := memory.io.prefetchValid && selectedFill
    core.io.prefetchLow := memory.io.prefetchLow
    core.io.prefetchHigh := memory.io.prefetchHigh
    core.io.rotateStart := core.io.prefetchDone && !fillIsDrain
    core.io.exponent := coreExponent(index)
    core.io.updateStart := false.B
    core.io.updateValid := false.B
    core.io.updateLow := 0.S.asTypeOf(core.io.updateLow)
    core.io.updateHigh := 0.S.asTypeOf(core.io.updateHigh)
    core.io.drainStart := core.io.prefetchDone && fillIsDrain
    core.io.drainReady := io.drainReady && drainCore === index.U
  }

  when(memory.io.prefetchValid) {
    assert(fillOutstanding, "prefetch data returned without a bitwise core")
    for ((core, index) <- cores.zipWithIndex) {
      when(fillCore === index.U) {
        assert(core.io.prefetchReady, "bitwise core rejected prefetch data")
      }
    }
  }

  val outputQueued = commandCount =/= 0.U
  val outputCore = Mux(
    outputQueued,
    commandCores(commandReadPointer),
    0.U(coreWidth.W)
  )
  val followingCore = commandCores(nextCommandPointer(commandReadPointer))
  val followingQueued = commandCount > 1.U
  val selectedPairValid = VecInit(cores.map(_.io.pairValid))(outputCore)
  val selectedPairLast = VecInit(cores.map(_.io.pairLast))(outputCore)
  val selectedRow = VecInit(cores.map(_.io.rowIndex))(outputCore)
  val selectedStreamFinishing =
    VecInit(cores.map(_.io.streamFinishing))(outputCore)
  val selectedTransformStart =
    VecInit(cores.map(_.io.transformStart))(outputCore)
  io.pairValid := outputQueued && selectedPairValid
  io.pairLast := outputQueued && selectedPairLast
  io.rowIndex := Mux(outputQueued, selectedRow, 0.U(rowWidth.W))
  io.coefficientLow := VecInit(cores.map(_.io.coefficientLow))(outputCore)
  io.coefficientHigh := VecInit(cores.map(_.io.coefficientHigh))(outputCore)
  val finishingOutput = outputQueued && selectedStreamFinishing
  val followingStartReady =
    VecInit(cores.map(_.io.streamStartReady))(followingCore)
  val switchWithoutBubble = finishingOutput && followingQueued &&
    followingStartReady
  val followingTransformStart =
    VecInit(cores.map(_.io.transformStart))(followingCore)
  // If the following core is accepting its final decomposition chunk, its
  // streamer starts next cycle. Issue the SGen marker now and suppress the
  // streamer's redundant marker when the completed buffer becomes visible.
  val suppressQueuedTransformStart = RegInit(false.B)
  val selectedTransformMarker = outputQueued && selectedTransformStart
  io.transformStart :=
    (selectedTransformMarker && !suppressQueuedTransformStart) ||
      switchWithoutBubble
  when(suppressQueuedTransformStart && selectedTransformMarker) {
    suppressQueuedTransformStart := false.B
  }
  when(switchWithoutBubble && !followingTransformStart) {
    suppressQueuedTransformStart := true.B
  }

  for ((core, index) <- cores.zipWithIndex) {
    val selectedOutput = outputQueued && outputCore === index.U
    val selectedFollowing = switchWithoutBubble && followingCore === index.U
    core.io.streamEnable := selectedOutput || selectedFollowing
    core.io.pairReady := io.pairReady && selectedOutput
  }

  when(finishingOutput) {
    commandReadPointer := nextCommandPointer(commandReadPointer)
  }
  switch(Cat(commandFire, finishingOutput)) {
    is("b01".U) { commandCount := commandCount - 1.U }
    is("b10".U) { commandCount := commandCount + 1.U }
  }

  for (context <- 0 until batchContexts) {
    when(memory.io.updateDone &&
        memory.io.updateDoneContext === context.U) {
      busy(context) := false.B
    }
    when(commandFire && io.commandContext === context.U) {
      busy(context) := true.B
    }
  }
  when(io.updateFirst && io.updateValid) {
    assert(busy(io.updateContext), "inverse update targets an idle context")
  }
  when(io.loadStart) {
    assert(!busy(io.loadContext), "cannot load an in-flight context")
    assert(!fillOutstanding, "cannot load during a bitwise prefetch")
    assert(commandCount === 0.U, "cannot load with queued commands")
  }

  val selectedDrainValid = VecInit(cores.map(_.io.drainValid))(drainCore)
  val selectedDrainDone = VecInit(cores.map(_.io.drainDone))(drainCore)
  io.drainValid := drainActive && selectedDrainValid
  io.drain := VecInit(cores.map(_.io.drain))(drainCore)
  io.drainDone := drainActive && selectedDrainDone
  io.drainDoneContext := drainContextReg
  when(!drainActive && fillIsDrain &&
      VecInit(cores.map(_.io.prefetchDone))(drainCore)) {
    drainActive := true.B
  }
  when(drainActive && selectedDrainDone) {
    drainActive := false.B
  }
}

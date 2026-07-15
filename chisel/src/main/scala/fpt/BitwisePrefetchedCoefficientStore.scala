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
  val commandCores = Module(
    new Queue(UInt(coreWidth.W), batchContexts + 2)
  )
  io.commandReady := commandCooldown === 0.U &&
    memory.io.prefetchReady && !fillOutstanding && hasReadyCore &&
    commandCores.io.enq.ready && commandAvailable
  val commandFire = io.commandValid && io.commandReady
  commandCores.io.enq.valid := commandFire
  commandCores.io.enq.bits := selectedCore
  when(commandFire) {
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
    memory.io.prefetchReady && hasReadyCore && !commandCores.io.deq.valid &&
    !io.commandValid
  val drainFire = io.drainStart && drainCanStart
  when(io.drainStart) {
    assert(drainCanStart, "bitwise drain started while unavailable")
    assert(io.drainContext < batchContexts.U, "invalid drain context")
    assert(!busy(io.drainContext), "cannot drain an in-flight context")
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

  val outputCore = commandCores.io.deq.bits
  val outputQueued = commandCores.io.deq.valid
  val selectedPairValid = VecInit(cores.map(_.io.pairValid))(outputCore)
  val selectedPairLast = VecInit(cores.map(_.io.pairLast))(outputCore)
  val selectedRow = VecInit(cores.map(_.io.rowIndex))(outputCore)
  val selectedTransformStart =
    VecInit(cores.map(_.io.transformStart))(outputCore)
  io.pairValid := outputQueued && selectedPairValid
  io.pairLast := outputQueued && selectedPairLast
  io.rowIndex := Mux(outputQueued, selectedRow, 0.U(rowWidth.W))
  io.transformStart := outputQueued && selectedTransformStart
  io.coefficientLow := VecInit(cores.map(_.io.coefficientLow))(outputCore)
  io.coefficientHigh := VecInit(cores.map(_.io.coefficientHigh))(outputCore)
  val finishingOutput = outputQueued && selectedPairLast &&
    selectedRow === (rows - 1).U
  commandCores.io.deq.ready := finishingOutput
  for ((core, index) <- cores.zipWithIndex) {
    val selectedOutput = outputQueued && outputCore === index.U
    core.io.streamEnable := selectedOutput
    core.io.pairReady := io.pairReady && selectedOutput
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
    assert(!commandCores.io.deq.valid, "cannot load with queued commands")
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

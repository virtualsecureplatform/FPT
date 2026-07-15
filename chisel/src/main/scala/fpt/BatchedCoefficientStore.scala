package fpt

import chisel3._
import chisel3.util._

/** Multi-context coefficient storage for FPT batch bootstrapping.
  *
  * The forward side streams all decomposition rows of one context and may
  * switch directly to the next context on the final beat. Independently, the
  * inverse side writes a delayed result back to a different context. With
  * twelve contexts at Set II, a context is revisited only after the
  * 192-cycle CMUX pipeline has released it.
  */
final class BatchedCmuxCoefficientStore(
    val config: CmuxCoefficientConfig,
    val batchContexts: Int
) extends Module {
  import TransformUtil._
  require(batchContexts >= 2)

  private val contextWidth = counterWidth(batchContexts)
  private val indexWidth = log2Ceil(config.polynomialSize)
  private val componentWidth = counterWidth(config.components)
  private val levelWidth = counterWidth(config.levels)
  private val rows = config.components * config.levels
  private val rowWidth = counterWidth(rows)
  private val forwardBeatWidth = counterWidth(config.forwardBeats)
  private val inverseBeatWidth = counterWidth(config.inverseBeats)
  private val polynomialBeatWidth = counterWidth(config.polynomialBeats)

  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val loadContext = Input(UInt(contextWidth.W))
    val loadValid = Input(Bool())
    val loadReady = Output(Bool())
    val load = Input(
      Vec(
        config.components,
        Vec(config.inverseLanes, UInt(config.torusWidth.W))
      )
    )
    val loadDone = Output(Bool())
    val loadDoneContext = Output(UInt(contextWidth.W))

    val commandValid = Input(Bool())
    val commandReady = Output(Bool())
    val commandContext = Input(UInt(contextWidth.W))
    val exponent = Input(UInt(config.exponentWidth.W))
    val transformStart = Output(Bool())
    val pairValid = Output(Bool())
    val pairReady = Input(Bool())
    val coefficientLow = Output(
      Vec(config.forwardLanes, SInt(config.forwardFormat.width.W))
    )
    val coefficientHigh = Output(
      Vec(config.forwardLanes, SInt(config.forwardFormat.width.W))
    )
    val rowIndex = Output(UInt(rowWidth.W))
    val pairLast = Output(Bool())

    val updateValid = Input(Bool())
    val updateReady = Output(Bool())
    val updateFirst = Input(Bool())
    val updateContext = Input(UInt(contextWidth.W))
    val updateLow = Input(
      Vec(
        config.components,
        Vec(config.inverseLanes, SInt(config.inverseFormat.width.W))
      )
    )
    val updateHigh = Input(
      Vec(
        config.components,
        Vec(config.inverseLanes, SInt(config.inverseFormat.width.W))
      )
    )
    val updateDone = Output(Bool())
    val updateDoneContext = Output(UInt(contextWidth.W))

    val drainStart = Input(Bool())
    val drainContext = Input(UInt(contextWidth.W))
    val drainValid = Output(Bool())
    val drainReady = Input(Bool())
    val drain = Output(
      Vec(
        config.components,
        Vec(config.inverseLanes, UInt(config.torusWidth.W))
      )
    )
    val drainDone = Output(Bool())
    val drainDoneContext = Output(UInt(contextWidth.W))

    val contextLoaded = Output(Vec(batchContexts, Bool()))
    val contextBusy = Output(Vec(batchContexts, Bool()))
  })

  val memory = Reg(
    Vec(
      batchContexts,
      Vec(
        config.components,
        Vec(
          config.inverseLanes,
          Vec(config.polynomialBeats, UInt(config.torusWidth.W))
        )
      )
    )
  )
  val loaded = RegInit(VecInit(Seq.fill(batchContexts)(false.B)))
  val busy = RegInit(VecInit(Seq.fill(batchContexts)(false.B)))
  io.contextLoaded := loaded
  io.contextBusy := busy

  // Initial/load-side context writer.
  val loadActive = RegInit(false.B)
  val loadContextReg = RegInit(0.U(contextWidth.W))
  val loadBeat = RegInit(0.U(polynomialBeatWidth.W))
  val loadDoneReg = RegInit(false.B)
  val loadDoneContextReg = RegInit(0.U(contextWidth.W))
  io.loadReady := loadActive
  io.loadDone := loadDoneReg
  io.loadDoneContext := loadDoneContextReg
  loadDoneReg := false.B

  when(io.loadStart) {
    assert(!loadActive, "batch context load started while active")
    assert(io.loadContext < batchContexts.U, "invalid load context")
    assert(!busy(io.loadContext), "cannot load an in-flight context")
    loadActive := true.B
    loadContextReg := io.loadContext
    loadBeat := 0.U
    loaded(io.loadContext) := false.B
  }
  when(io.loadValid) {
    assert(io.loadReady, "batch context load data presented while idle")
  }
  val loadFire = io.loadValid && io.loadReady
  when(loadFire) {
    for (context <- 0 until batchContexts) {
      when(loadContextReg === context.U) {
        for (component <- 0 until config.components) {
          for (lane <- 0 until config.inverseLanes) {
            memory(context)(component)(lane)(loadBeat) :=
              io.load(component)(lane)
          }
        }
      }
    }
    when(loadBeat === (config.polynomialBeats - 1).U) {
      loadActive := false.B
      loadBeat := 0.U
      loaded(loadContextReg) := true.B
      loadDoneReg := true.B
      loadDoneContextReg := loadContextReg
    }.otherwise {
      loadBeat := loadBeat + 1.U
    }
  }

  // Independent inverse-update writer.
  val updateActive = RegInit(false.B)
  val updateContextReg = RegInit(0.U(contextWidth.W))
  val updateBeat = RegInit(0.U(inverseBeatWidth.W))
  val updateDoneReg = RegInit(false.B)
  val updateDoneContextReg = RegInit(0.U(contextWidth.W))
  io.updateReady := updateActive || io.updateFirst
  io.updateDone := updateDoneReg
  io.updateDoneContext := updateDoneContextReg
  updateDoneReg := false.B

  val updateFire = io.updateValid && io.updateReady
  val activeUpdateContext = Mux(
    updateActive,
    updateContextReg,
    io.updateContext
  )
  val activeUpdateBeat = Mux(updateActive, updateBeat, 0.U)
  val updateFinal = updateFire &&
    activeUpdateBeat === (config.inverseBeats - 1).U

  when(io.updateValid) {
    assert(
      updateActive || io.updateFirst,
      "first inverse update beat must carry updateFirst"
    )
  }
  when(io.updateFirst && io.updateValid) {
    assert(!updateActive, "updateFirst asserted inside an inverse transaction")
    assert(io.updateContext < batchContexts.U, "invalid update context")
    assert(busy(io.updateContext), "inverse update targets an idle context")
  }
  when(updateFire) {
    when(io.updateFirst) {
      updateContextReg := io.updateContext
    }
    val lowDepth = activeUpdateBeat.pad(polynomialBeatWidth)
    val highDepth = lowDepth + config.inverseBeats.U
    for (context <- 0 until batchContexts) {
      when(activeUpdateContext === context.U) {
        for (component <- 0 until config.components) {
          for (lane <- 0 until config.inverseLanes) {
            val lowTorus = (io.updateLow(component)(lane).asUInt <<
              config.torusShift)(config.torusWidth - 1, 0)
            val highTorus = (io.updateHigh(component)(lane).asUInt <<
              config.torusShift)(config.torusWidth - 1, 0)
            memory(context)(component)(lane)(lowDepth) :=
              memory(context)(component)(lane)(lowDepth) + lowTorus
            memory(context)(component)(lane)(highDepth) :=
              memory(context)(component)(lane)(highDepth) + highTorus
          }
        }
      }
    }
    when(updateFinal) {
      updateActive := false.B
      updateBeat := 0.U
      updateDoneReg := true.B
      updateDoneContextReg := activeUpdateContext
    }.otherwise {
      updateActive := true.B
      updateBeat := activeUpdateBeat + 1.U
    }
  }

  // Forward decomposition streamer. A new command can be accepted on the
  // final pair beat, giving Set II its exact rows*forwardBeats = 16 interval.
  val streamActive = RegInit(false.B)
  val selectedContext = RegInit(0.U(contextWidth.W))
  val selectedExponent = RegInit(0.U(config.exponentWidth.W))
  val row = RegInit(0.U(rowWidth.W))
  val forwardBeat = RegInit(0.U(forwardBeatWidth.W))
  val pairFire = io.pairValid && io.pairReady
  val finalPairBeat = pairFire &&
    forwardBeat === (config.forwardBeats - 1).U
  val finalRow = row === (rows - 1).U
  val finishingCommand = streamActive && finalPairBeat && finalRow
  val commandAvailable = loaded(io.commandContext) &&
    (!busy(io.commandContext) ||
      (updateFinal && activeUpdateContext === io.commandContext))
  io.commandReady := (!streamActive || finishingCommand) && commandAvailable &&
    !loadActive
  val commandFire = io.commandValid && io.commandReady
  val launchFollowingRow = streamActive && finalPairBeat && !finalRow
  io.transformStart := commandFire || launchFollowingRow
  io.pairValid := streamActive
  io.pairLast := streamActive &&
    forwardBeat === (config.forwardBeats - 1).U
  io.rowIndex := row

  when(io.commandValid && (!streamActive || finishingCommand)) {
    assert(io.commandContext < batchContexts.U, "invalid command context")
  }
  when(commandFire) {
    selectedContext := io.commandContext
    selectedExponent := io.exponent
    streamActive := true.B
    row := 0.U
    forwardBeat := 0.U
  }
  when(pairFire) {
    when(finalPairBeat) {
      forwardBeat := 0.U
      when(finalRow) {
        streamActive := commandFire
      }.otherwise {
        row := row + 1.U
      }
    }.otherwise {
      forwardBeat := forwardBeat + 1.U
    }
  }

  val rowComponents = VecInit(
    (0 until rows).map(index => (index / config.levels).U(componentWidth.W))
  )
  val rowLevels = VecInit(
    (0 until rows).map(index => (index % config.levels).U(levelWidth.W))
  )
  val selectedComponent = rowComponents(row)
  val selectedLevel = rowLevels(row)
  val selectedPolynomial: Seq[UInt] =
    (0 until config.polynomialSize).map { index =>
      val bank = index % config.inverseLanes
      val depth = index / config.inverseLanes
      VecInit((0 until batchContexts).map { context =>
        VecInit((0 until config.components).map(component =>
          memory(context)(component)(bank)(depth)
        ))(selectedComponent)
      })(selectedContext)
    }
  val rotator = Module(
    new NegacyclicBarrelRotator(config.polynomialSize, config.torusWidth)
  )
  rotator.io.input := VecInit(selectedPolynomial)
  rotator.io.exponent := selectedExponent

  def decompose(source: UInt, current: UInt): SInt = {
    val difference = (source - current)(config.torusWidth - 1, 0)
    val biased = (difference +& config.decompositionBias.U)(
      config.torusWidth - 1,
      0
    )
    val shifts = (0 until config.levels).map { level =>
      val shift = config.torusWidth - (level + 1) * config.baseBits
      (biased >> shift)(config.baseBits - 1, 0)
    }
    val digitBits = MuxLookup(selectedLevel, shifts.head)(
      shifts.zipWithIndex.map { case (bits, level) => level.U -> bits }
    )
    val centered = (digitBits -
      (BigInt(1) << (config.baseBits - 1)).U)(
      config.baseBits - 1,
      0
    ).asSInt
    val fixed = Wire(SInt(config.forwardFormat.width.W))
    fixed := centered << config.forwardFormat.fractionalBits
    fixed
  }

  def streamedCoefficient(lane: Int, high: Boolean): SInt = {
    val halfOffset = if (high) config.points else 0
    val rotated = VecInit((0 until config.forwardBeats).map { beat =>
      rotator.io.output(halfOffset + beat * config.forwardLanes + lane)
    })
    val current = VecInit((0 until config.forwardBeats).map { beat =>
      selectedPolynomial(halfOffset + beat * config.forwardLanes + lane)
    })
    decompose(rotated(forwardBeat), current(forwardBeat))
  }
  for (lane <- 0 until config.forwardLanes) {
    io.coefficientLow(lane) := streamedCoefficient(lane, high = false)
    io.coefficientHigh(lane) := streamedCoefficient(lane, high = true)
  }

  // Context ownership changes are ordered so a context released by the final
  // inverse beat can be accepted as a new command on the same edge.
  for (context <- 0 until batchContexts) {
    when(updateFinal && activeUpdateContext === context.U) {
      busy(context) := false.B
    }
    when(commandFire && io.commandContext === context.U) {
      busy(context) := true.B
    }
  }
  when(updateFire && streamActive) {
    assert(
      activeUpdateContext =/= selectedContext,
      "forward and inverse sides accessed the same batch context"
    )
  }
  when(loadFire && updateFire) {
    assert(
      loadContextReg =/= activeUpdateContext,
      "load and inverse update accessed the same batch context"
    )
  }

  // Read-only drain used after a batch completes.
  val drainActive = RegInit(false.B)
  val drainContextReg = RegInit(0.U(contextWidth.W))
  val drainBeat = RegInit(0.U(polynomialBeatWidth.W))
  val drainDoneReg = RegInit(false.B)
  val drainDoneContextReg = RegInit(0.U(contextWidth.W))
  io.drainValid := drainActive
  io.drainDone := drainDoneReg
  io.drainDoneContext := drainDoneContextReg
  drainDoneReg := false.B
  for (component <- 0 until config.components) {
    for (lane <- 0 until config.inverseLanes) {
      val contextValues = VecInit((0 until batchContexts).map(context =>
        memory(context)(component)(lane)(drainBeat)
      ))
      io.drain(component)(lane) := contextValues(drainContextReg)
    }
  }
  when(io.drainStart) {
    assert(!drainActive, "batch drain started while active")
    assert(io.drainContext < batchContexts.U, "invalid drain context")
    assert(!busy(io.drainContext), "cannot drain an in-flight context")
    drainActive := true.B
    drainContextReg := io.drainContext
    drainBeat := 0.U
  }
  when(io.drainValid && io.drainReady) {
    when(drainBeat === (config.polynomialBeats - 1).U) {
      drainActive := false.B
      drainBeat := 0.U
      drainDoneReg := true.B
      drainDoneContextReg := drainContextReg
    }.otherwise {
      drainBeat := drainBeat + 1.U
    }
  }
}

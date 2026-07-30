package fpt

import chisel3._
import chisel3.util._

/** BRAM-backed coefficient frontend for the batch-interleaved CMUX engine.
  *
  * Two full-polynomial register buffers bridge the accumulator memories'
  * 64-lane packed interface to the forward transform's 128-lane tangent
  * boundary. A context is prefetched in eight Set-II cycles while the prior
  * buffer emits its sixteen decomposition beats. This preserves the CMUX
  * initiation interval without keeping every batch context in flip-flops.
  */
final class PrefetchedBatchedCmuxCoefficientStore(
    override val config: CmuxCoefficientConfig,
    override val batchContexts: Int
) extends BatchedCmuxCoefficientStoreBase(config, batchContexts) {
  import TransformUtil._
  require(batchContexts >= 2)

  private val contextWidth = counterWidth(batchContexts)
  private val componentWidth = counterWidth(config.components)
  private val levelWidth = counterWidth(config.levels)
  private val rows = config.components * config.levels
  private val rowWidth = counterWidth(rows)
  private val forwardBeatWidth = counterWidth(config.forwardBeats)
  private val polynomialBeatWidth = counterWidth(config.polynomialBeats)
  private val commandInterval = rows * config.forwardBeats
  private val cooldownWidth = counterWidth(commandInterval)
  private val bufferCount = 2
  private val bufferWidth = 1

  private val memoryConfig = ReplicatedAccumulatorBanksConfig(
    config,
    batchContexts
  )
  require(
    memoryConfig.halfBeats <= commandInterval,
    "accumulator prefetch must fit inside the command interval"
  )

  val memory = Module(new ReplicatedAccumulatorBanks(memoryConfig))
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

  // Shape each prefetch buffer exactly like the packed memory banks. This
  // gives every returning lane a fixed write destination; only the eight-word
  // depth is selected dynamically.
  val buffers = Reg(
    Vec(
      bufferCount,
      Vec(
        config.components,
        Vec(
          config.inverseLanes,
          Vec(
            memoryConfig.halfBeats,
            Vec(2, UInt(config.torusWidth.W))
          )
        )
      )
    )
  )
  val bufferOccupied = RegInit(VecInit(Seq.fill(bufferCount)(false.B)))
  val bufferExponent = Reg(
    Vec(bufferCount, UInt(config.exponentWidth.W))
  )
  val hasFreeBuffer = !bufferOccupied.asUInt.andR
  val freeBuffer = Mux(bufferOccupied(0), 1.U, 0.U)

  val fillOutstanding = RegInit(false.B)
  val fillBuffer = RegInit(0.U(bufferWidth.W))
  val fillIsDrain = RegInit(false.B)

  // Accept external commands at exactly the transform row-stream interval.
  // The cooldown also prevents both prefetch buffers from being consumed by
  // an unnecessarily early burst.
  val commandCooldown = RegInit(0.U(cooldownWidth.W))
  val commandAvailable = memory.io.contextLoaded(io.commandContext) &&
    (!busy(io.commandContext) ||
      (memory.io.updateDone &&
        memory.io.updateDoneContext === io.commandContext))
  io.commandReady := commandCooldown === 0.U &&
    memory.io.prefetchReady && !fillOutstanding && hasFreeBuffer &&
    commandAvailable
  val commandFire = io.commandValid && io.commandReady
  when(commandFire) {
    commandCooldown := (commandInterval - 1).U
  }.elsewhen(commandCooldown =/= 0.U) {
    commandCooldown := commandCooldown - 1.U
  }
  when(io.commandValid) {
    assert(io.commandContext < batchContexts.U, "invalid command context")
  }

  // A drain reuses the same prefetch port and a free register buffer after a
  // batch completes. It is intentionally excluded from the active pipeline.
  val drainStreaming = RegInit(false.B)
  val drainBuffer = RegInit(0.U(bufferWidth.W))
  val drainContextReg = RegInit(0.U(contextWidth.W))
  val drainBeat = RegInit(0.U(polynomialBeatWidth.W))
  val drainDoneReg = RegInit(false.B)
  val drainDoneContextReg = RegInit(0.U(contextWidth.W))
  drainDoneReg := false.B

  val readyBuffers = Module(new Queue(UInt(bufferWidth.W), bufferCount))
  val drainCanStart = !drainStreaming && !fillOutstanding &&
    memory.io.prefetchReady && hasFreeBuffer && !io.commandValid &&
    !readyBuffers.io.deq.valid && io.drainContext < batchContexts.U &&
    !busy(io.drainContext)
  io.drainStartReady := drainCanStart
  val drainFire = io.drainStart && drainCanStart
  when(io.drainStart) {
    assert(drainCanStart, "drain started while the prefetch path was busy")
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
    fillBuffer := freeBuffer
    fillIsDrain := drainFire
    bufferOccupied(freeBuffer) := true.B
    when(commandFire) {
      bufferExponent(freeBuffer) := io.exponent
    }.otherwise {
      drainContextReg := io.drainContext
    }
  }

  when(memory.io.prefetchValid) {
    assert(fillOutstanding, "prefetch data returned without an owner")
    for (component <- 0 until config.components) {
      for (lane <- 0 until config.inverseLanes) {
        buffers(fillBuffer)(component)(lane)(memory.io.prefetchBeat)(0) :=
          memory.io.prefetchLow(component)(lane)
        buffers(fillBuffer)(component)(lane)(memory.io.prefetchBeat)(1) :=
          memory.io.prefetchHigh(component)(lane)
      }
    }
  }

  readyBuffers.io.enq.valid := memory.io.prefetchDone && !fillIsDrain
  readyBuffers.io.enq.bits := fillBuffer
  when(readyBuffers.io.enq.valid) {
    assert(readyBuffers.io.enq.ready, "prefetched buffer queue overflow")
  }
  when(memory.io.prefetchDone) {
    fillOutstanding := false.B
    when(fillIsDrain) {
      drainStreaming := true.B
      drainBuffer := fillBuffer
      drainBeat := 0.U
    }
  }

  // Decomposition streamer. A ready prefetched buffer may replace the active
  // buffer on the final pair beat with no bubble at the SGen input boundary.
  // Selection (lead) counters run `rotatorLatency` cycles ahead of emission:
  // the rotator's register layers plus the narrow metadata/subtrahend pipes
  // below cross to the emission domain under one shared advance strobe, so
  // downstream sees the unpipelined protocol with a fixed extra latency.
  val rotatorOpt =
    if (config.windowedRotator) None
    else
      Some(
        Module(
          new NegacyclicBarrelRotator(
            config.polynomialSize,
            config.torusWidth,
            config.rotatorPipelineEvery
          )
        )
      )
  private val rotatorLatency = rotatorOpt.map(_.latency).getOrElse(0)

  val leadActive = RegInit(false.B)
  val selectedBuffer = RegInit(0.U(bufferWidth.W))
  val row = RegInit(0.U(rowWidth.W))
  val forwardBeat = RegInit(0.U(forwardBeatWidth.W))

  private def emitPipe[T <: Data](value: T, enable: Bool): T =
    ShiftRegister(value, rotatorLatency, enable)
  private def emitPipeReset[T <: Data](value: T, init: T, enable: Bool): T =
    ShiftRegister(value, rotatorLatency, init, enable)

  val emitActive = Wire(Bool())
  val advance = !emitActive || io.pairReady
  rotatorOpt.foreach(_.enable.foreach(_ := advance))

  val leadFire = leadActive && advance
  val beatLast = forwardBeat === (config.forwardBeats - 1).U
  val finalRow = row === (rows - 1).U
  val finishingCommand = leadFire && beatLast && finalRow
  val launchFromIdle = !leadActive && readyBuffers.io.deq.valid && advance
  val switchCommand = finishingCommand && readyBuffers.io.deq.valid
  val launchFollowingRow = leadFire && beatLast && !finalRow

  readyBuffers.io.deq.ready := launchFromIdle || switchCommand
  emitActive := emitPipeReset(leadActive, false.B, advance)
  io.transformStart := emitPipeReset(
    launchFromIdle || switchCommand || launchFollowingRow,
    false.B,
    advance
  )
  io.pairValid := emitActive
  io.pairLast := emitPipeReset(leadActive && beatLast, false.B, advance)
  io.rowIndex := emitPipe(row, advance)

  when(launchFromIdle) {
    leadActive := true.B
    selectedBuffer := readyBuffers.io.deq.bits
    row := 0.U
    forwardBeat := 0.U
  }.elsewhen(leadFire) {
    when(beatLast) {
      forwardBeat := 0.U
      when(finalRow) {
        bufferOccupied(selectedBuffer) := false.B
        when(switchCommand) {
          leadActive := true.B
          selectedBuffer := readyBuffers.io.deq.bits
          row := 0.U
        }.otherwise {
          leadActive := false.B
        }
      }.otherwise {
        row := row + 1.U
      }
    }.otherwise {
      forwardBeat := forwardBeat + 1.U
    }
  }

  // Keep the four row choices as an explicit mux tree. A dynamic Vec of
  // constants becomes a SystemVerilog assignment pattern that stock Yosys
  // cannot parse and obscures the intended small selector in synthesis.
  def balancedSelect(values: IndexedSeq[UInt], selector: UInt): UInt = {
    def select(values: IndexedSeq[UInt], bit: Int): UInt =
      if (values.size == 1) values.head
      else {
        val (low, high) = values.splitAt(values.size / 2)
        Mux(selector(bit), select(high, bit - 1), select(low, bit - 1))
      }

    select(values, log2Ceil(values.size) - 1)
  }

  val encodedRows = 1 << rowWidth
  val rowComponents = IndexedSeq.tabulate(encodedRows) { index =>
    (if (index < rows) index / config.levels else 0).U(componentWidth.W)
  }
  val rowLevels = IndexedSeq.tabulate(encodedRows) { index =>
    (if (index < rows) index % config.levels else 0).U(levelWidth.W)
  }
  val selectedComponent = balancedSelect(rowComponents, row)
  val selectedLevel = balancedSelect(rowLevels, row)
  val selectedExponent = bufferExponent(selectedBuffer)
  val selectedPolynomial: Seq[UInt] =
    (0 until config.polynomialSize).map { index =>
      val high = index >= config.points
      val point = index & (config.points - 1)
      val bank = point % config.inverseLanes
      val depth = point / config.inverseLanes
      VecInit((0 until bufferCount).map { buffer =>
        VecInit((0 until config.components).map(component =>
          buffers(buffer)(component)(bank)(depth)(if (high) 1 else 0)
        ))(selectedComponent)
      })(selectedBuffer)
    }
  rotatorOpt.foreach { rotator =>
    rotator.io.input := VecInit(selectedPolynomial)
    rotator.io.exponent := selectedExponent
  }

  def decompose(source: UInt, current: UInt, level: UInt): SInt = {
    val difference = (source - current)(config.torusWidth - 1, 0)
    val biased = (difference +& config.decompositionBias.U)(
      config.torusWidth - 1,
      0
    )
    val shifts = (0 until config.levels).map { index =>
      val shift = config.torusWidth - (index + 1) * config.baseBits
      (biased >> shift)(config.baseBits - 1, 0)
    }
    val digitBits = MuxLookup(level, shifts.head)(
      shifts.zipWithIndex.map { case (bits, index) => index.U -> bits }
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

  // The unrotated subtrahends and the beat/level metadata cross to the
  // emission domain at stream width; only the rotator itself pipes the full
  // polynomial.
  val emitLevel = emitPipe(selectedLevel, advance)
  val emitBeat = emitPipe(forwardBeat, advance)
  val currentWindow = Wire(
    Vec(2, Vec(config.forwardLanes, UInt(config.torusWidth.W)))
  )
  for (half <- 0 until 2) {
    for (lane <- 0 until config.forwardLanes) {
      val values = VecInit((0 until config.forwardBeats).map { beat =>
        selectedPolynomial(
          half * config.points + beat * config.forwardLanes + lane
        )
      })
      currentWindow(half)(lane) := values(forwardBeat)
    }
  }
  val emitCurrent = emitPipe(currentWindow, advance)

  // Stream-width rotation: each emitted half-window reads one contiguous
  // span of the virtual 2N ring, so two block-selected inputs and a lane
  // extractor replace the full-width barrel entirely.
  val windowedOutputs: Option[Vec[Vec[UInt]]] =
    if (config.windowedRotator) {
      val lanes = config.forwardLanes
      val blocks = config.polynomialSize / lanes
      val laneWidth = log2Ceil(lanes)
      val ringWidth = log2Ceil(2 * config.polynomialSize)
      val pairs = VecInit((0 until blocks).map { block =>
        VecInit((0 until 2 * lanes).map { t =>
          selectedPolynomial((block * lanes + t) % config.polynomialSize)
        })
      })
      Some(VecInit((0 until 2).map { half =>
        val base = (half * config.points).U
        val position = base +& (2 * config.polynomialSize).U +&
          (forwardBeat << laneWidth) -& selectedExponent
        val v = position(ringWidth - 1, 0)
        val firstRing = v(ringWidth - 1, laneWidth)
        val offset = v(laneWidth - 1, 0)
        val window = Module(
          new WindowedNegacyclicRotatorWindow(
            config.polynomialSize,
            config.torusWidth,
            lanes
          )
        )
        val physical = firstRing(log2Ceil(blocks) - 1, 0)
        window.io.firstBlock := VecInit(
          (0 until lanes).map(t => pairs(physical)(t))
        )
        window.io.secondBlock := VecInit(
          (0 until lanes).map(t => pairs(physical)(lanes + t))
        )
        window.io.offset := offset
        window.io.firstRingBlock := firstRing
        window.io.output
      }))
    } else None

  def streamedCoefficient(lane: Int, high: Boolean): SInt = {
    val source = windowedOutputs match {
      case Some(windows) => windows(if (high) 1 else 0)(lane)
      case None =>
        val halfOffset = if (high) config.points else 0
        val rotated = VecInit((0 until config.forwardBeats).map { beat =>
          rotatorOpt.get.io
            .output(halfOffset + beat * config.forwardLanes + lane)
        })
        rotated(emitBeat)
    }
    decompose(
      source,
      emitCurrent(if (high) 1 else 0)(lane),
      emitLevel
    )
  }
  for (lane <- 0 until config.forwardLanes) {
    io.coefficientLow(lane) := streamedCoefficient(lane, high = false)
    io.coefficientHigh(lane) := streamedCoefficient(lane, high = true)
  }

  when(io.updateFirst && io.updateValid) {
    assert(busy(io.updateContext), "inverse update targets an idle context")
  }
  for (context <- 0 until batchContexts) {
    when(memory.io.updateDone &&
        memory.io.updateDoneContext === context.U) {
      busy(context) := false.B
    }
    // A context may be relaunched on the cycle its prior update commits.
    // Keep it busy for the newly accepted command in that case.
    when(commandFire && io.commandContext === context.U) {
      busy(context) := true.B
    }
  }
  when(io.loadStart) {
    assert(!busy(io.loadContext), "cannot load an in-flight context")
    assert(!fillOutstanding, "cannot load during an accumulator prefetch")
    assert(
      !leadActive && !emitActive,
      "cannot load while decomposition is active"
    )
    assert(!drainStreaming, "cannot load while drain is active")
    assert(!io.updateValid, "cannot load with inverse update data")
  }

  // Read-only diagnostic drain from its prefetched buffer.
  io.drainValid := drainStreaming
  io.drainDone := drainDoneReg
  io.drainDoneContext := drainDoneContextReg
  val drainHigh = drainBeat >= memoryConfig.halfBeats.U
  val drainHalfBeat = drainBeat(memoryConfig.halfBeatWidth - 1, 0)
  for (component <- 0 until config.components) {
    for (lane <- 0 until config.inverseLanes) {
      val halves = VecInit(
        (0 until bufferCount).map { buffer =>
          VecInit(
            Seq(
              buffers(buffer)(component)(lane)(drainHalfBeat)(0),
              buffers(buffer)(component)(lane)(drainHalfBeat)(1)
            )
          )(drainHigh)
        }
      )
      io.drain(component)(lane) := halves(drainBuffer)
    }
  }
  when(io.drainValid && io.drainReady) {
    when(drainBeat === (config.polynomialBeats - 1).U) {
      drainStreaming := false.B
      drainBeat := 0.U
      bufferOccupied(drainBuffer) := false.B
      drainDoneReg := true.B
      drainDoneContextReg := drainContextReg
    }.otherwise {
      drainBeat := drainBeat + 1.U
    }
  }
}

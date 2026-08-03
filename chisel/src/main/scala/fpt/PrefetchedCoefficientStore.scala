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
  // One elastic beat cuts the accumulator-buffer selector away from the
  // sample-extraction RAM write.  At Set II the unregistered path is almost
  // entirely routing: drainBeat selects thousands of buffer bits, then the
  // result crosses the middle SLR to the distributed mask RAM.  This stage
  // sustains one beat per cycle while allowing the consumer to stall.
  val drainOutputValid = RegInit(false.B)
  val drainOutputLast = RegInit(false.B)
  val drainOutput = Reg(
    Vec(
      config.components,
      Vec(config.inverseLanes, UInt(config.torusWidth.W))
    )
  )
  val drainDoneReg = RegInit(false.B)
  val drainDoneContextReg = RegInit(0.U(contextWidth.W))
  drainDoneReg := false.B

  val readyBuffers = Module(new Queue(UInt(bufferWidth.W), bufferCount))
  val drainCanStart = !drainStreaming && !drainOutputValid &&
    !fillOutstanding &&
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
  // Selection (lead) counters run `emissionLatency` cycles ahead of emission:
  // the rotator's register layers, the narrow metadata/subtrahend pipes, and
  // the final decomposition register all cross to the emission domain under
  // one shared advance strobe.  The last register makes the wide SLR boundary
  // to the forward transform register-to-register rather than placing the
  // subtract/bias/digit-select cone on that crossing.
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
  // The windowed path first registers the narrow row/buffer/beat selectors,
  // then delays the data selectors alongside a registered physical-bank
  // selector. This cuts the large span mux before its candidate registers.
  // The aligner's four local stages, a biased-difference cut, and a final
  // stream-width stage follow.
  private val selectionStages = if (config.windowedRotator) 2 else 0
  private val windowedStages =
    1 + PipelinedWindowedNegacyclicRotatorSpan.latency
  private val rotatorLatency = rotatorOpt
    .map(_.latency)
    .getOrElse(if (config.windowedRotator) windowedStages else 0)
  private val outputStages = if (config.windowedRotator) 2 else 0
  private val emissionLatency =
    selectionStages + rotatorLatency + outputStages

  val leadActive = RegInit(false.B)
  val selectedBuffer = RegInit(0.U(bufferWidth.W))
  val row = RegInit(0.U(rowWidth.W))
  val forwardBeat = RegInit(0.U(forwardBeatWidth.W))

  private def dataPipe[T <: Data](value: T, enable: Bool): T =
    ShiftRegister(value, rotatorLatency, enable)
  private def emissionPipe[T <: Data](value: T, enable: Bool): T =
    ShiftRegister(value, emissionLatency, enable)
  private def emissionPipeReset[T <: Data](
      value: T,
      init: T,
      enable: Bool
  ): T = ShiftRegister(value, emissionLatency, init, enable)

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
  emitActive := emissionPipeReset(leadActive, false.B, advance)
  io.transformStart := emissionPipeReset(
    launchFromIdle || switchCommand || launchFollowingRow,
    false.B,
    advance
  )
  io.pairValid := emitActive
  io.pairLast := emissionPipeReset(
    leadActive && beatLast,
    false.B,
    advance
  )
  io.rowIndex := emissionPipe(row, advance)

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

  // Isolate the wide polynomial muxes from the lead counters. In Set II the
  // component bit otherwise drives roughly twenty thousand LUT inputs before
  // the first data register, producing a route-dominated path across SLR1.
  // Registered selectors are simple high-fanout sources that Vivado can
  // replicate locally during placement. The non-windowed implementation keeps
  // its original latency and structure.
  val streamComponent =
    if (config.windowedRotator)
      RegEnable(selectedComponent, advance)
    else selectedComponent
  val streamLevel =
    if (config.windowedRotator) RegEnable(selectedLevel, advance)
    else selectedLevel
  val streamBuffer =
    if (config.windowedRotator) RegEnable(selectedBuffer, advance)
    else selectedBuffer
  val streamBeat =
    if (config.windowedRotator) RegEnable(forwardBeat, advance)
    else forwardBeat
  val streamExponent =
    if (config.windowedRotator) RegEnable(selectedExponent, advance)
    else selectedExponent
  // Keep the buffered polynomial data aligned with the physical span select
  // registered below. Registering only the span select would pair one beat's
  // address with the following beat's buffer contents.
  val windowComponent =
    if (config.windowedRotator) RegEnable(streamComponent, advance)
    else streamComponent
  val windowLevel =
    if (config.windowedRotator) RegEnable(streamLevel, advance)
    else streamLevel
  val windowBuffer =
    if (config.windowedRotator) RegEnable(streamBuffer, advance)
    else streamBuffer
  val windowBeat =
    if (config.windowedRotator) RegEnable(streamBeat, advance)
    else streamBeat
  val selectedPolynomial: Seq[UInt] =
    (0 until config.polynomialSize).map { index =>
      val high = index >= config.points
      val point = index & (config.points - 1)
      val bank = point % config.inverseLanes
      val depth = point / config.inverseLanes
      VecInit((0 until bufferCount).map { buffer =>
        VecInit((0 until config.components).map(component =>
          buffers(buffer)(component)(bank)(depth)(if (high) 1 else 0)
        ))(windowComponent)
      })(windowBuffer)
    }
  rotatorOpt.foreach { rotator =>
    rotator.io.input := VecInit(selectedPolynomial)
    rotator.io.exponent := streamExponent
  }

  def biasedDifference(source: UInt, current: UInt): UInt = {
    val difference = (source - current)(config.torusWidth - 1, 0)
    (difference +& config.decompositionBias.U)(
      config.torusWidth - 1,
      0
    )
  }

  def decomposeBiased(biased: UInt, level: UInt): SInt = {
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
  val emitLevel = dataPipe(windowLevel, advance)
  val emitBeat = dataPipe(windowBeat, advance)
  val decompositionLevel =
    if (config.windowedRotator) RegEnable(emitLevel, advance)
    else emitLevel
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
      currentWindow(half)(lane) := values(windowBeat)
    }
  }
  val emitCurrent = dataPipe(currentWindow, advance)

  // Stream-width rotation: each emitted half-window reads one contiguous
  // span of the virtual 2N ring. Select at the accumulator's physical
  // inverse-lane bank width, rather than at the wider forward width, so the
  // variable aligner is substantially smaller and its select fanout stays
  // local to one bank group.
  val windowedOutputs: Option[Vec[Vec[UInt]]] =
    if (config.windowedRotator) {
      val blockLanes = config.inverseLanes
      val outputLanes = config.forwardLanes
      require(blockLanes >= 2)
      require(outputLanes >= blockLanes)
      require(outputLanes % blockLanes == 0)
      val blocks = config.polynomialSize / blockLanes
      require(blocks >= 4 && isPow2(blocks))
      val blockLaneWidth = log2Ceil(blockLanes)
      val outputLaneWidth = log2Ceil(outputLanes)
      val ringWidth = log2Ceil(2 * config.polynomialSize)
      val spanBlocks = outputLanes / blockLanes + 1
      val spanSize = spanBlocks * blockLanes
      val spans = (0 until blocks).map { block =>
        VecInit((0 until spanSize).map { t =>
          selectedPolynomial(
            (block * blockLanes + t) % config.polynomialSize
          )
        })
      }
      val position = (2 * config.polynomialSize).U +&
        (streamBeat << outputLaneWidth) -& streamExponent
      val v = position(ringWidth - 1, 0)
      val firstRing = v(ringWidth - 1, blockLaneWidth)
      val offset = v(blockLaneWidth - 1, 0)
      val physical = firstRing(log2Ceil(blocks) - 1, 0)
      val upperSelect = physical(log2Ceil(blocks) - 1, 1)
      val stagedUpperSelect = RegEnable(upperSelect, advance)
      val parity = RegEnable(physical(0), advance)
      val stagedOffset = RegEnable(offset, advance)
      val stagedFirstRing = RegEnable(firstRing, advance)
      val candidateParity = RegEnable(parity, advance)
      val candidateOffset = RegEnable(stagedOffset, advance)
      val candidateFirstRing = RegEnable(stagedFirstRing, advance)
      val halfBlockOffset = config.points / blockLanes

      Some(VecInit((0 until 2).map { half =>
        // Index both halves with the same low-half physical selector. The
        // high half is exactly N/2 coefficients, or `halfBlockOffset`
        // physical blocks, later in the same anti-periodic ring.
        val indexedSpans = (0 until blocks).map { start =>
          spans((start + half * halfBlockOffset) % blocks)
        }
        val evenSpans = VecInit(
          (0 until blocks by 2).map(index => indexedSpans(index))
        )
        val oddSpans = VecInit(
          (1 until blocks by 2).map(index => indexedSpans(index))
        )
        val evenCandidate = RegEnable(evenSpans(stagedUpperSelect), advance)
        val oddCandidate = RegEnable(oddSpans(stagedUpperSelect), advance)
        val selectedSpan = Mux(
          candidateParity,
          oddCandidate,
          evenCandidate
        )

        val window = Module(
          new PipelinedWindowedNegacyclicRotatorSpan(
            config.polynomialSize,
            config.torusWidth,
            blockLanes,
            outputLanes
          )
        )
        for (block <- 0 until spanBlocks) {
          window.io.input(block) := VecInit(
            (0 until blockLanes).map { lane =>
              selectedSpan(block * blockLanes + lane)
            }
          )
        }
        window.io.offset := candidateOffset
        window.io.firstRingBlock := (
          candidateFirstRing + (half * halfBlockOffset).U
        )(log2Ceil(2 * blocks) - 1, 0)
        window.io.enable := advance
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
    val biased = biasedDifference(
      source,
      emitCurrent(if (high) 1 else 0)(lane)
    )
    // Split the 32-bit subtract/bias carry chain from digit selection and the
    // final forward-SLR boundary. On the placed U280 design the uncut path is
    // dominated by the route from the carry chain to the stream registers,
    // even though the arithmetic itself is shallow.
    val stagedBiased =
      if (config.windowedRotator) RegEnable(biased, advance)
      else biased
    decomposeBiased(stagedBiased, decompositionLevel)
  }
  for (lane <- 0 until config.forwardLanes) {
    val low = streamedCoefficient(lane, high = false)
    val high = streamedCoefficient(lane, high = true)
    io.coefficientLow(lane) :=
      (if (config.windowedRotator) RegEnable(low, advance) else low)
    io.coefficientHigh(lane) :=
      (if (config.windowedRotator) RegEnable(high, advance) else high)
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
    assert(!drainOutputValid, "cannot load with a buffered drain beat")
    assert(!io.updateValid, "cannot load with inverse update data")
  }

  // Read-only diagnostic drain from its prefetched buffer.  The elastic
  // output register decouples the wide buffer selection from the downstream
  // sample-extraction memory without reducing the one-beat-per-cycle rate.
  io.drainValid := drainOutputValid
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
      io.drain(component)(lane) := drainOutput(component)(lane)
      when(drainStreaming && (!drainOutputValid || io.drainReady)) {
        drainOutput(component)(lane) := halves(drainBuffer)
      }
    }
  }

  val drainOutputFire = drainOutputValid && io.drainReady
  val drainSourceFire = drainStreaming &&
    (!drainOutputValid || io.drainReady)
  when(drainSourceFire) {
    drainOutputValid := true.B
    drainOutputLast := drainBeat === (config.polynomialBeats - 1).U
    when(drainBeat === (config.polynomialBeats - 1).U) {
      drainStreaming := false.B
      drainBeat := 0.U
    }.otherwise {
      drainBeat := drainBeat + 1.U
    }
  }.elsewhen(drainOutputFire) {
    drainOutputValid := false.B
  }
  when(drainOutputFire && drainOutputLast) {
    assert(!drainStreaming, "final drain beat consumed before source stopped")
    drainOutputLast := false.B
    for (buffer <- 0 until bufferCount) {
      when(drainBuffer === buffer.U) {
        bufferOccupied(buffer) := false.B
      }
    }
    drainDoneReg := true.B
    drainDoneContextReg := drainContextReg
  }
}

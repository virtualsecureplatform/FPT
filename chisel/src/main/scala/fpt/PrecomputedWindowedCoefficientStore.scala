package fpt

import chisel3._
import chisel3.util._

/** Prefetched CMUX frontend with one time-shared stream-width rotator.
  *
  * The ordinary windowed frontend rotates the low and high polynomial halves
  * in parallel for every decomposition level.  At Set II that leaves two
  * independent 6,144-bit span boundaries and repeats the 32-bit difference
  * arithmetic for both 128-lane halves.  This implementation instead uses
  * the otherwise redundant level cycles to preprocess one half per cycle.
  * It computes every gadget level together, stores the centered digits in a
  * packed three-entry transpose, and emits prior worksets while the next is
  * being prepared. The third entry covers data still traversing the pipelined
  * selector and rotator when the following command starts.
  *
  * For the paper configuration preprocessing takes
  * `components * 2 * forwardBeats = 16` cycles, exactly matching the CMUX
  * command interval.  Consequently one rotator sustains II=16 while removing
  * the second wide rotation network and its physical boundary.
  */
final class PrecomputedWindowedBatchedCmuxCoefficientStore(
    override val config: CmuxCoefficientConfig,
    override val batchContexts: Int
) extends BatchedCmuxCoefficientStoreBase(config, batchContexts) {
  import TransformUtil._

  require(batchContexts >= 2)
  require(config.windowedRotator)

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
  // One workset emits, one is being written by the rotator pipeline, and a
  // third must be allocated when the next 16-beat preprocessing interval
  // starts. Two buffers create an every-other-command bubble because the
  // final launch precedes the prior workset's final emitted beat.
  private val digitBufferCount = 3
  private val digitBufferWidth = counterWidth(digitBufferCount)
  private val halfCount = 2
  private val preprocessingBeats =
    config.components * halfCount * config.forwardBeats
  require(
    preprocessingBeats <= commandInterval,
    "serialized window preprocessing must fit inside the command interval"
  )

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

  // Match the packed accumulator-bank layout so every prefetch lane has a
  // fixed write destination and only the shallow beat address is dynamic.
  val buffers = Reg(
    Vec(
      bufferCount,
      Vec(
        config.components,
        Vec(
          config.inverseLanes,
          Vec(
            memoryConfig.halfBeats,
            Vec(halfCount, UInt(config.torusWidth.W))
          )
        )
      )
    )
  )
  val bufferOccupied = RegInit(VecInit(Seq.fill(bufferCount)(false.B)))
  val bufferExponent = Reg(Vec(bufferCount, UInt(config.exponentWidth.W)))
  val sourceReleaseValid = WireDefault(false.B)
  val sourceReleaseBuffer = WireDefault(0.U(bufferWidth.W))
  val drainReleaseValid = WireDefault(false.B)
  val drainReleaseBuffer = WireDefault(0.U(bufferWidth.W))
  val sourceAvailable = VecInit((0 until bufferCount).map { buffer =>
    !bufferOccupied(buffer) ||
      (sourceReleaseValid && sourceReleaseBuffer === buffer.U) ||
      (drainReleaseValid && drainReleaseBuffer === buffer.U)
  })
  val hasFreeBuffer = sourceAvailable.asUInt.orR
  val freeBuffer = PriorityEncoder(sourceAvailable.asUInt)

  val fillOutstanding = RegInit(false.B)
  val fillBuffer = RegInit(0.U(bufferWidth.W))
  val fillIsDrain = RegInit(false.B)

  // Completed prefetches wait here until the serialized preprocessor can
  // claim a digit buffer. Source buffers remain occupied while queued.
  val readySourceBuffers = Module(
    new Queue(
      UInt(bufferWidth.W),
      bufferCount,
      pipe = true,
      flow = true
    )
  )

  // Accept external commands at the transform row-stream interval.  A
  // source buffer is released shortly after its final span has entered the
  // selection pipeline, well before the same ping-pong slot is needed again.
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

  // The diagnostic drain uses a normal source buffer but is only admitted
  // when the preprocessing and digit-emission pipelines are empty.
  val drainStreaming = RegInit(false.B)
  val drainBuffer = RegInit(0.U(bufferWidth.W))
  val drainContextReg = RegInit(0.U(contextWidth.W))
  val drainBeat = RegInit(0.U(polynomialBeatWidth.W))
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

  // Preprocessing scheduler state is declared before drain admission so the
  // latter can prove that no workset still owns a transient source buffer.
  val preprocessActive = RegInit(false.B)
  val preprocessSourceBuffer = RegInit(0.U(bufferWidth.W))
  val preprocessDigitBuffer = RegInit(0.U(digitBufferWidth.W))
  val preprocessComponent = RegInit(0.U(componentWidth.W))
  val preprocessHalf = RegInit(false.B)
  val preprocessBeat = RegInit(0.U(forwardBeatWidth.W))

  // A packed, replicated block-memory transpose holds all levels for the
  // emitting, pipeline-draining, and newly starting commands. The emitter
  // reads one low/high pair while the preprocessor writes another workset.
  val digitBufferOccupied = RegInit(
    VecInit(Seq.fill(digitBufferCount)(false.B))
  )
  val readyDigitBuffers = Module(
    new Queue(
      UInt(digitBufferWidth.W),
      digitBufferCount,
      pipe = true,
      flow = true
    )
  )

  // Keep one elastic stage between the synchronous transpose read and an
  // explicit physical-cut register. A bare block-RAM read still left its
  // clock-to-output delay and the SLR crossing in the same cycle as the first
  // forward-transform logic. The extra cut removes that path without reducing
  // the one-beat-per-cycle emission rate.
  val emitLeadActive = RegInit(false.B)
  val emitBuffer = RegInit(0.U(digitBufferWidth.W))
  val emitRow = RegInit(0.U(rowWidth.W))
  val emitBeat = RegInit(0.U(forwardBeatWidth.W))
  val readValid = RegInit(false.B)
  val readRow = RegInit(0.U(rowWidth.W))
  val readLast = RegInit(false.B)
  val leadTransformStart = RegInit(false.B)
  val readTransformStart = RegInit(false.B)
  val outputValid = RegInit(false.B)
  val outputRow = RegInit(0.U(rowWidth.W))
  val outputLast = RegInit(false.B)
  val digitReadLow = Wire(
    Vec(config.forwardLanes, SInt(config.baseBits.W))
  )
  val digitReadHigh = Wire(
    Vec(config.forwardLanes, SInt(config.baseBits.W))
  )
  val digitOutputBoundary = Module(
    new PhysicalCutRegister(
      2 * config.forwardLanes * config.baseBits
    )
  )
  digitOutputBoundary.io.clock := clock
  val outputAdvance = !outputValid || io.pairReady
  val readAdvance = !readValid || outputAdvance
  digitOutputBoundary.io.enable := outputAdvance && readValid
  digitOutputBoundary.io.inputData := Cat(
    digitReadHigh.asUInt,
    digitReadLow.asUInt
  )
  val boundaryDigits = digitOutputBoundary.io.outputData.asTypeOf(
    Vec(
      2,
      Vec(config.forwardLanes, SInt(config.baseBits.W))
    )
  )

  io.pairValid := outputValid
  io.rowIndex := outputRow
  io.pairLast := outputLast
  // SGen consumes this marker one cycle before the corresponding first beat.
  // Gate it with the read-to-output transfer so a downstream stall cannot
  // separate the marker from its data.
  io.transformStart :=
    readValid && readTransformStart && outputAdvance
  for (lane <- 0 until config.forwardLanes) {
    io.coefficientLow(lane) :=
      boundaryDigits(0)(lane) << config.forwardFormat.fractionalBits
    io.coefficientHigh(lane) :=
      boundaryDigits(1)(lane) << config.forwardFormat.fractionalBits
  }

  val emitLeadFire = emitLeadActive && readAdvance
  val emitBeatLast = emitBeat === (config.forwardBeats - 1).U
  val emitFinalRow = emitRow === (rows - 1).U
  val emitFinalLead = emitLeadFire && emitBeatLast && emitFinalRow
  val emitLaunchFromIdle = !emitLeadActive &&
    readyDigitBuffers.io.deq.valid && readAdvance
  val emitSwitch = emitFinalLead && readyDigitBuffers.io.deq.valid
  val emitFollowingRow = emitLeadFire && emitBeatLast && !emitFinalRow
  readyDigitBuffers.io.deq.ready := emitLaunchFromIdle || emitSwitch

  when(outputAdvance) {
    outputValid := readValid
    outputRow := readRow
    outputLast := readLast
  }
  when(readAdvance) {
    readValid := emitLeadActive
    readRow := emitRow
    readLast := emitLeadActive && emitBeatLast
    readTransformStart := leadTransformStart
    leadTransformStart :=
      emitLaunchFromIdle || emitSwitch || emitFollowingRow
  }

  when(emitLaunchFromIdle) {
    emitLeadActive := true.B
    emitBuffer := readyDigitBuffers.io.deq.bits
    emitRow := 0.U
    emitBeat := 0.U
  }.elsewhen(emitLeadFire) {
    when(emitBeatLast) {
      emitBeat := 0.U
      when(emitFinalRow) {
        when(emitSwitch) {
          emitLeadActive := true.B
          emitBuffer := readyDigitBuffers.io.deq.bits
          emitRow := 0.U
        }.otherwise {
          emitLeadActive := false.B
          emitRow := 0.U
        }
      }.otherwise {
        emitRow := emitRow + 1.U
      }
    }.otherwise {
      emitBeat := emitBeat + 1.U
    }
  }

  val digitAvailable = VecInit((0 until digitBufferCount).map { buffer =>
    !digitBufferOccupied(buffer) ||
      (emitFinalLead && emitBuffer === buffer.U)
  })
  val hasFreeDigitBuffer = digitAvailable.asUInt.orR
  val freeDigitBuffer = PriorityEncoder(digitAvailable.asUInt)

  val preprocessFinalLaunch = preprocessActive &&
    preprocessComponent === (config.components - 1).U &&
    preprocessHalf &&
    preprocessBeat === (config.forwardBeats - 1).U
  val preprocessCanAccept = !preprocessActive || preprocessFinalLaunch
  readySourceBuffers.io.deq.ready :=
    preprocessCanAccept && hasFreeDigitBuffer
  val preprocessStart = readySourceBuffers.io.deq.fire

  when(preprocessStart) {
    preprocessActive := true.B
    preprocessSourceBuffer := readySourceBuffers.io.deq.bits
    preprocessDigitBuffer := freeDigitBuffer
    preprocessComponent := 0.U
    preprocessHalf := false.B
    preprocessBeat := 0.U
  }.elsewhen(preprocessActive) {
    when(preprocessBeat === (config.forwardBeats - 1).U) {
      preprocessBeat := 0.U
      when(preprocessHalf) {
        preprocessHalf := false.B
        when(preprocessComponent === (config.components - 1).U) {
          preprocessActive := false.B
          preprocessComponent := 0.U
        }.otherwise {
          preprocessComponent := preprocessComponent + 1.U
        }
      }.otherwise {
        preprocessHalf := true.B
      }
    }.otherwise {
      preprocessBeat := preprocessBeat + 1.U
    }
  }

  // The final source span is fully captured by the candidate registers two
  // clocks after launch. Releasing there, instead of waiting for the entire
  // rotator pipeline, preserves the two-buffer prefetch cadence.
  sourceReleaseValid := ShiftRegister(
    preprocessFinalLaunch,
    2,
    false.B,
    true.B
  )
  sourceReleaseBuffer := ShiftRegister(preprocessSourceBuffer, 2)

  val drainCanStart = !drainStreaming && !drainOutputValid &&
    !fillOutstanding && memory.io.prefetchReady && hasFreeBuffer &&
    !readySourceBuffers.io.deq.valid && !preprocessActive &&
    !digitBufferOccupied.asUInt.orR && !emitLeadActive && !readValid &&
    !outputValid &&
    !io.commandValid && io.drainContext < batchContexts.U &&
    !busy(io.drainContext)
  io.drainStartReady := drainCanStart
  val drainFire = io.drainStart && drainCanStart
  when(io.drainStart) {
    assert(drainCanStart, "precomputed drain started while unavailable")
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

  readySourceBuffers.io.enq.valid := memory.io.prefetchDone && !fillIsDrain
  readySourceBuffers.io.enq.bits := fillBuffer
  when(readySourceBuffers.io.enq.valid) {
    assert(readySourceBuffers.io.enq.ready, "source buffer queue overflow")
  }
  when(memory.io.prefetchDone) {
    fillOutstanding := false.B
    when(fillIsDrain) {
      drainStreaming := true.B
      drainBuffer := fillBuffer
      drainBeat := 0.U
    }
  }

  // Source-buffer ownership has three mutually exclusive endpoints. A new
  // allocation wins if a release and allocation ever share an edge.
  for (buffer <- 0 until bufferCount) {
    when(
      (sourceReleaseValid && sourceReleaseBuffer === buffer.U) ||
        (drainReleaseValid && drainReleaseBuffer === buffer.U)
    ) {
      bufferOccupied(buffer) := false.B
    }
    when((commandFire || drainFire) && freeBuffer === buffer.U) {
      bufferOccupied(buffer) := true.B
    }
  }

  // Capture the serialized workset identity before any wide source mux. All
  // later pipeline registers advance unconditionally; validity is carried as
  // a narrow companion, so bubbles drain naturally and no wide CE net is
  // introduced.
  val selectionValid = RegNext(preprocessActive, false.B)
  val selectionSourceBuffer = RegEnable(
    preprocessSourceBuffer,
    0.U(bufferWidth.W),
    preprocessActive
  )
  val selectionDigitBuffer = RegEnable(
    preprocessDigitBuffer,
    0.U(digitBufferWidth.W),
    preprocessActive
  )
  val selectionComponent = RegEnable(
    preprocessComponent,
    0.U(componentWidth.W),
    preprocessActive
  )
  val selectionHalf = RegEnable(preprocessHalf, false.B, preprocessActive)
  val selectionBeat = RegEnable(
    preprocessBeat,
    0.U(forwardBeatWidth.W),
    preprocessActive
  )
  val selectionExponent = RegEnable(
    bufferExponent(preprocessSourceBuffer),
    0.U(config.exponentWidth.W),
    preprocessActive
  )

  val selectedPolynomial: IndexedSeq[UInt] =
    IndexedSeq.tabulate(config.polynomialSize) { index =>
      val high = index >= config.points
      val point = index & (config.points - 1)
      val bank = point % config.inverseLanes
      val depth = point / config.inverseLanes
      VecInit((0 until bufferCount).map { buffer =>
        VecInit((0 until config.components).map { component =>
          buffers(buffer)(component)(bank)(depth)(if (high) 1 else 0)
        })(selectionComponent)
      })(selectionSourceBuffer)
    }

  private val blockLanes = config.inverseLanes
  private val outputLanes = config.forwardLanes
  require(blockLanes >= 2)
  require(outputLanes >= blockLanes)
  require(outputLanes % blockLanes == 0)
  private val blocks = config.polynomialSize / blockLanes
  require(blocks >= 4 && isPow2(blocks))
  private val blockLaneWidth = log2Ceil(blockLanes)
  private val outputLaneWidth = log2Ceil(outputLanes)
  private val ringWidth = log2Ceil(2 * config.polynomialSize)
  private val spanBlocks = outputLanes / blockLanes + 1
  private val spanSize = spanBlocks * blockLanes

  val spans = IndexedSeq.tabulate(blocks) { block =>
    VecInit(IndexedSeq.tabulate(spanSize) { offset =>
      selectedPolynomial(
        (block * blockLanes + offset) % config.polynomialSize
      )
    })
  }
  val destination = Wire(UInt(ringWidth.W))
  destination :=
    (selectionBeat << outputLaneWidth) +
      Mux(selectionHalf, config.points.U, 0.U)
  val ringPosition =
    (2 * config.polynomialSize).U((ringWidth + 1).W) +&
      destination.pad(ringWidth + 1) -&
      selectionExponent.pad(ringWidth + 1)
  val v = ringPosition(ringWidth - 1, 0)
  val firstRing = v(ringWidth - 1, blockLaneWidth)
  val offset = v(blockLaneWidth - 1, 0)
  val physical = firstRing(log2Ceil(blocks) - 1, 0)
  val upperSelect = physical(log2Ceil(blocks) - 1, 1)
  val evenSpans = VecInit((0 until blocks by 2).map(spans))
  val oddSpans = VecInit((1 until blocks by 2).map(spans))
  val evenCandidate = RegNext(evenSpans(upperSelect))
  val oddCandidate = RegNext(oddSpans(upperSelect))

  val currentWindows = VecInit(
    (0 until 2 * config.forwardBeats).map { window =>
      VecInit((0 until outputLanes).map { lane =>
        selectedPolynomial(window * outputLanes + lane)
      })
    }
  )
  val currentWindowIndex =
    Mux(selectionHalf, config.forwardBeats.U, 0.U) + selectionBeat
  val currentCandidate = RegNext(currentWindows(currentWindowIndex))
  val candidateValid = RegNext(selectionValid, false.B)
  val candidateParity = RegNext(physical(0))
  val candidateOffset = RegNext(offset)
  val candidateFirstRing = RegNext(firstRing)
  val candidateDigitBuffer = RegNext(selectionDigitBuffer)
  val candidateComponent = RegNext(selectionComponent)
  val candidateHalf = RegNext(selectionHalf)
  val candidateBeat = RegNext(selectionBeat)

  val selectedSpanBoundary = Module(
    new PhysicalCutRegister(spanSize * config.torusWidth)
  )
  selectedSpanBoundary.io.clock := clock
  selectedSpanBoundary.io.enable := true.B
  selectedSpanBoundary.io.inputData :=
    Mux(candidateParity, oddCandidate, evenCandidate).asUInt
  val boundarySpan = selectedSpanBoundary.io.outputData.asTypeOf(evenCandidate)

  val currentBoundary = Module(
    new PhysicalCutRegister(outputLanes * config.torusWidth)
  )
  currentBoundary.io.clock := clock
  currentBoundary.io.enable := true.B
  currentBoundary.io.inputData := currentCandidate.asUInt
  val boundaryCurrent =
    currentBoundary.io.outputData.asTypeOf(currentCandidate)

  val boundaryValid = RegNext(candidateValid, false.B)
  val boundaryOffset = RegNext(candidateOffset)
  val boundaryFirstRing = RegNext(candidateFirstRing)
  val boundaryDigitBuffer = RegNext(candidateDigitBuffer)
  val boundaryComponent = RegNext(candidateComponent)
  val boundaryHalf = RegNext(candidateHalf)
  val boundaryBeat = RegNext(candidateBeat)

  val window = Module(
    new PipelinedWindowedNegacyclicRotatorSpan(
      config.polynomialSize,
      config.torusWidth,
      blockLanes,
      outputLanes
    )
  )
  for (block <- 0 until spanBlocks) {
    window.io.input(block) := VecInit((0 until blockLanes).map { lane =>
      boundarySpan(block * blockLanes + lane)
    })
  }
  window.io.offset := boundaryOffset
  window.io.firstRingBlock := boundaryFirstRing
  window.io.enable := true.B

  val alignedValid = ShiftRegister(
    boundaryValid,
    window.latency,
    false.B,
    true.B
  )
  val alignedCurrent = ShiftRegister(boundaryCurrent, window.latency)
  val alignedDigitBuffer = ShiftRegister(
    boundaryDigitBuffer,
    window.latency
  )
  val alignedComponent = ShiftRegister(boundaryComponent, window.latency)
  val alignedHalf = ShiftRegister(boundaryHalf, window.latency)
  val alignedBeat = ShiftRegister(boundaryBeat, window.latency)

  val biased = Wire(Vec(outputLanes, UInt(config.torusWidth.W)))
  for (lane <- 0 until outputLanes) {
    val difference =
      (window.io.output(lane) - alignedCurrent(lane))(
        config.torusWidth - 1,
        0
      )
    biased(lane) :=
      (difference +& config.decompositionBias.U)(
        config.torusWidth - 1,
        0
      )
  }
  val stagedBiased = RegNext(biased)
  val digitWriteValid = RegNext(alignedValid, false.B)
  val digitWriteBuffer = RegNext(alignedDigitBuffer)
  val digitWriteComponent = RegNext(alignedComponent)
  val digitWriteHalf = RegNext(alignedHalf)
  val digitWriteBeat = RegNext(alignedBeat)
  val digitWriteFinal = digitWriteValid &&
    digitWriteComponent === (config.components - 1).U &&
    digitWriteHalf &&
    digitWriteBeat === (config.forwardBeats - 1).U

  private val digitAddressDepth = config.components * config.forwardBeats
  private val digitAddressWidth = counterWidth(digitAddressDepth)
  private val digitMemoryDepth =
    digitBufferCount * halfCount * digitAddressDepth
  private val digitMemoryAddressWidth = counterWidth(digitMemoryDepth)
  private def digitMemoryAddress(
      buffer: UInt,
      half: UInt,
      address: UInt
  ): UInt = {
    val slot = buffer * halfCount.U + half
    (slot * digitAddressDepth.U + address)(
      digitMemoryAddressWidth - 1,
      0
    )
  }
  // Each copy has one synchronous read and one write port. Both receive every
  // precomputed word; one services low-half emission while the other services
  // the simultaneous high-half read. Packing buffer, half, component, and beat
  // into the address turns 768 shallow LUT memories into two 48 x 2,560-bit
  // block-RAM words for the paper configuration.
  val digitMemories = Seq.fill(2) {
    SyncReadMem(
      digitMemoryDepth,
      Vec(
        config.forwardLanes,
        Vec(config.levels, SInt(config.baseBits.W))
      )
    )
  }
  val digitWriteAddress =
    (digitWriteComponent * config.forwardBeats.U + digitWriteBeat)(
      digitAddressWidth - 1,
      0
    )
  val centeredDigits = Wire(
    Vec(
      config.forwardLanes,
      Vec(config.levels, SInt(config.baseBits.W))
    )
  )
  for (lane <- 0 until config.forwardLanes) {
    for (level <- 0 until config.levels) {
      val shift = config.torusWidth - (level + 1) * config.baseBits
      val digit =
        (stagedBiased(lane) >> shift)(config.baseBits - 1, 0)
      centeredDigits(lane)(level) :=
        (digit - (BigInt(1) << (config.baseBits - 1)).U)(
          config.baseBits - 1,
          0
        ).asSInt
    }
  }
  val digitWriteMemoryAddress = digitMemoryAddress(
    digitWriteBuffer,
    digitWriteHalf.asUInt,
    digitWriteAddress
  )
  when(digitWriteValid) {
    for (memory <- digitMemories) {
      memory.write(digitWriteMemoryAddress, centeredDigits)
    }
  }

  readyDigitBuffers.io.enq.valid := digitWriteFinal
  readyDigitBuffers.io.enq.bits := digitWriteBuffer
  when(digitWriteFinal) {
    assert(readyDigitBuffers.io.enq.ready, "digit buffer queue overflow")
  }

  // A final emitted beat frees its backing transpose at the same edge that
  // the block RAM captures its read. Allow the preprocessor to allocate that
  // buffer immediately; the allocation assignment deliberately has priority.
  for (buffer <- 0 until digitBufferCount) {
    when(emitFinalLead && emitBuffer === buffer.U) {
      digitBufferOccupied(buffer) := false.B
    }
    when(preprocessStart && freeDigitBuffer === buffer.U) {
      digitBufferOccupied(buffer) := true.B
    }
  }

  val encodedRows = 1 << rowWidth
  val rowComponents = VecInit(IndexedSeq.tabulate(encodedRows) { index =>
    (if (index < rows) index / config.levels else 0).U(componentWidth.W)
  })
  val rowLevels = VecInit(IndexedSeq.tabulate(encodedRows) { index =>
    (if (index < rows) index % config.levels else 0).U(levelWidth.W)
  })
  val emitComponent = rowComponents(emitRow)
  val digitReadAddress =
    (emitComponent * config.forwardBeats.U + emitBeat)(
      digitAddressWidth - 1,
      0
    )
  val requestedDigitReadLowAddress =
    digitMemoryAddress(emitBuffer, 0.U, digitReadAddress)
  val requestedDigitReadHighAddress =
    digitMemoryAddress(emitBuffer, 1.U, digitReadAddress)
  val heldDigitReadLowAddress =
    RegInit(0.U(digitMemoryAddressWidth.W))
  val heldDigitReadHighAddress =
    RegInit(0.U(digitMemoryAddressWidth.W))
  when(emitLeadFire) {
    heldDigitReadLowAddress := requestedDigitReadLowAddress
    heldDigitReadHighAddress := requestedDigitReadHighAddress
  }
  // A SyncReadMem's output is unspecified on a disabled read. Keep the last
  // issued address active while an output beat is stalled so pairValid always
  // denotes stable coefficient data, including the final beat of a workset.
  val digitReadEnable = emitLeadFire || readValid
  val digitReadLowWords = digitMemories(0).read(
    Mux(
      emitLeadFire,
      requestedDigitReadLowAddress,
      heldDigitReadLowAddress
    ),
    digitReadEnable
  )
  val digitReadHighWords = digitMemories(1).read(
    Mux(
      emitLeadFire,
      requestedDigitReadHighAddress,
      heldDigitReadHighAddress
    ),
    digitReadEnable
  )
  val readLevel = rowLevels(readRow)
  for (lane <- 0 until config.forwardLanes) {
    digitReadLow(lane) := digitReadLowWords(lane)(readLevel)
    digitReadHigh(lane) := digitReadHighWords(lane)(readLevel)
  }

  when(io.updateFirst && io.updateValid) {
    assert(busy(io.updateContext), "inverse update targets an idle context")
  }
  for (context <- 0 until batchContexts) {
    when(
      memory.io.updateDone &&
        memory.io.updateDoneContext === context.U
    ) {
      busy(context) := false.B
    }
    when(commandFire && io.commandContext === context.U) {
      busy(context) := true.B
    }
  }
  when(io.loadStart) {
    assert(!busy(io.loadContext), "cannot load an in-flight context")
    assert(!fillOutstanding, "cannot load during an accumulator prefetch")
    assert(!readySourceBuffers.io.deq.valid, "cannot load queued source data")
    assert(!preprocessActive, "cannot load during coefficient preprocessing")
    assert(!digitBufferOccupied.asUInt.orR, "cannot load live digit data")
    assert(
      !emitLeadActive && !readValid && !outputValid,
      "cannot load during emission"
    )
    assert(!drainStreaming, "cannot load while drain is active")
    assert(!drainOutputValid, "cannot load with a buffered drain beat")
    assert(!io.updateValid, "cannot load with inverse update data")
  }

  // Read-only diagnostic drain from its prefetched buffer. The elastic output
  // register keeps the wide beat selector away from the sample-extraction
  // write path and preserves one beat per cycle under normal readiness.
  io.drainValid := drainOutputValid
  io.drainDone := drainDoneReg
  io.drainDoneContext := drainDoneContextReg
  val drainHigh = drainBeat >= memoryConfig.halfBeats.U
  val drainHalfBeat = drainBeat(memoryConfig.halfBeatWidth - 1, 0)
  for (component <- 0 until config.components) {
    for (lane <- 0 until config.inverseLanes) {
      val halves = VecInit((0 until bufferCount).map { buffer =>
        VecInit(
          Seq(
            buffers(buffer)(component)(lane)(drainHalfBeat)(0),
            buffers(buffer)(component)(lane)(drainHalfBeat)(1)
          )
        )(drainHigh)
      })
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
    drainReleaseValid := true.B
    drainReleaseBuffer := drainBuffer
    drainDoneReg := true.B
    drainDoneContextReg := drainContextReg
  }
}

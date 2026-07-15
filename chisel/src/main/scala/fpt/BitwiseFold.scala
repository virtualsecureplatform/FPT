package fpt

import chisel3._
import chisel3.util._

/** Converts polynomial-wide centered digits to component/level tangent rows. */
final class BitwiseFoldedDigitStreamer(val config: CmuxCoefficientConfig)
    extends Module {
  private val rows = config.components * config.levels
  private val rowWidth = TransformUtil.counterWidth(rows)
  private val beatWidth = TransformUtil.counterWidth(config.forwardBeats)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val digits = Input(
      Vec(
        config.components,
        Vec(
          config.levels,
          Vec(config.polynomialSize, SInt(config.baseBits.W))
        )
      )
    )
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
    val transformStart = Output(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
  })

  val active = RegInit(false.B)
  val row = RegInit(0.U(rowWidth.W))
  val beat = RegInit(0.U(beatWidth.W))
  val doneReg = RegInit(false.B)

  io.rowIndex := row
  io.busy := active
  io.done := doneReg
  doneReg := false.B

  def fixedDigit(lane: Int, high: Boolean): SInt = {
    val keyWidth = rowWidth + beatWidth
    val halfOffset = if (high) config.points else 0
    val selections = IndexedSeq.tabulate(1 << keyWidth) { key =>
      val rowIndex = key >> beatWidth
      val beatIndex = key & ((1 << beatWidth) - 1)
      if (rowIndex < rows && beatIndex < config.forwardBeats) {
        val component = rowIndex / config.levels
        val level = rowIndex % config.levels
        val position = halfOffset + beatIndex * config.forwardLanes + lane
        io.digits(component)(level)(position)
      } else {
        io.digits(0)(0)(halfOffset + lane)
      }
    }

    def balancedSelect(values: IndexedSeq[SInt], bit: Int): SInt =
      if (values.size == 1) values.head
      else {
        val (low, highValues) = values.splitAt(values.size / 2)
        Mux(
          Cat(row, beat)(bit),
          balancedSelect(highValues, bit - 1),
          balancedSelect(low, bit - 1)
        )
      }

    val selected = balancedSelect(selections, keyWidth - 1)
    val fixed = Wire(SInt(config.forwardFormat.width.W))
    fixed := selected << config.forwardFormat.fractionalBits
    fixed
  }

  for (lane <- 0 until config.forwardLanes) {
    io.coefficientLow(lane) := fixedDigit(lane, high = false)
    io.coefficientHigh(lane) := fixedDigit(lane, high = true)
  }

  // When idle, the first pair can flow in the same cycle as start. This keeps
  // the row stream aligned with a same-rate bitwise decomposition pipeline.
  io.pairValid := active || io.start
  val pairFire = io.pairValid && io.pairReady
  val finalBeat = beat === (config.forwardBeats - 1).U
  val finalRow = row === (rows - 1).U
  val finishing = pairFire && finalBeat && finalRow
  io.pairLast := pairFire && finalBeat
  io.transformStart := io.start || (pairFire && finalBeat && !finalRow)
  when(io.start) {
    assert(!active || finishing, "bitwise digit stream started while busy")
    active := true.B
    row := 0.U
    beat := 0.U
  }
  when(pairFire) {
    when(finalBeat) {
      beat := 0.U
      when(finalRow) {
        active := io.start && active
        row := 0.U
        doneReg := true.B
      }.otherwise {
        row := row + 1.U
      }
    }.otherwise {
      beat := beat + 1.U
    }
  }
}

/** Both-component bitwise CMUX frontend through the folded FFT input stream. */
final class BitwiseCmuxForwardFrontend(
    val config: CmuxCoefficientConfig,
    val bitsPerCycle: Int
) extends Module {
  private val rows = config.components * config.levels
  private val rowWidth = TransformUtil.counterWidth(rows)

  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val loadValid = Input(Bool())
    val loadReady = Output(Bool())
    val load = Input(
      Vec(
        config.components,
        Vec(config.inverseLanes, UInt(config.torusWidth.W))
      )
    )
    val loadDone = Output(Bool())

    val prefetchStart = Input(Bool())
    val prefetchStartReady = Output(Bool())
    val prefetchValid = Input(Bool())
    val prefetchReady = Output(Bool())
    val prefetchLow = Input(
      Vec(
        config.components,
        Vec(config.inverseLanes, UInt(config.torusWidth.W))
      )
    )
    val prefetchHigh = Input(
      Vec(
        config.components,
        Vec(config.inverseLanes, UInt(config.torusWidth.W))
      )
    )
    val prefetchDone = Output(Bool())

    val updateStart = Input(Bool())
    val updateValid = Input(Bool())
    val updateReady = Output(Bool())
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

    val drainStart = Input(Bool())
    val drainValid = Output(Bool())
    val drainReady = Input(Bool())
    val drain = Output(
      Vec(
        config.components,
        Vec(config.inverseLanes, UInt(config.torusWidth.W))
      )
    )
    val drainDone = Output(Bool())

    val rotateStart = Input(Bool())
    val rotateReady = Output(Bool())
    val exponent = Input(UInt(config.exponentWidth.W))
    val pairValid = Output(Bool())
    val pairReady = Input(Bool())
    val streamEnable = Input(Bool())
    val coefficientLow = Output(
      Vec(config.forwardLanes, SInt(config.forwardFormat.width.W))
    )
    val coefficientHigh = Output(
      Vec(config.forwardLanes, SInt(config.forwardFormat.width.W))
    )
    val rowIndex = Output(UInt(rowWidth.W))
    val pairLast = Output(Bool())
    val transformStart = Output(Bool())
    val loaded = Output(Bool())
    val idle = Output(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
  })

  val components = Seq.fill(config.components) {
    Module(new BitwiseCmuxDecompositionFrontend(config, bitsPerCycle))
  }
  val streamer = Module(new BitwiseFoldedDigitStreamer(config))

  private val bufferCount = 2
  private val bufferWidth = TransformUtil.counterWidth(bufferCount)
  val digitBuffers = Reg(
    Vec(
      bufferCount,
      Vec(
        config.components,
        Vec(
          config.levels,
          Vec(config.polynomialSize, SInt(config.baseBits.W))
        )
      )
    )
  )
  val bufferOccupied = RegInit(VecInit(Seq.fill(bufferCount)(false.B)))
  val drainBuffer = RegInit(0.U(bufferWidth.W))
  val pendingBuffers = Module(
    new Queue(UInt(bufferWidth.W), bufferCount, pipe = true, flow = true)
  )
  val readyBuffers = Module(
    new Queue(UInt(bufferWidth.W), bufferCount, pipe = true, flow = true)
  )

  val allLoadReady = components.map(_.io.loadReady).reduce(_ && _)
  val allLoadDone = components.map(_.io.loadDone).reduce(_ && _)
  val allPrefetchStartReady =
    components.map(_.io.prefetchStartReady).reduce(_ && _)
  val allPrefetchReady = components.map(_.io.prefetchReady).reduce(_ && _)
  val allPrefetchDone = components.map(_.io.prefetchDone).reduce(_ && _)
  val allUpdateReady = components.map(_.io.updateReady).reduce(_ && _)
  val allUpdateDone = components.map(_.io.updateDone).reduce(_ && _)
  val allDrainValid = components.map(_.io.drainValid).reduce(_ && _)
  val allDrainDone = components.map(_.io.drainDone).reduce(_ && _)
  val allLoaded = components.map(_.io.loaded).reduce(_ && _)
  val allDecompositionDone = components.map(_.io.done).reduce(_ && _)
  val anyComponentBusy = components.map(_.io.busy).reduce(_ || _)
  val allRotateReady = components.map(_.io.rotateReady).reduce(_ && _)
  val frontendBusy = anyComponentBusy || streamer.io.busy ||
    bufferOccupied.asUInt.orR

  when(
    PopCount(
      Cat(io.loadStart, io.prefetchStart, io.updateStart, io.drainStart)
    ) > 1.U
  ) {
    assert(false.B, "bitwise accumulator operations must not start together")
  }
  when(io.loadStart || io.prefetchStart || io.updateStart || io.drainStart) {
    assert(!anyComponentBusy, "bitwise storage operation started while busy")
  }

  val finalOutputRow = streamer.io.rowIndex === (rows - 1).U
  val streamerFinishing = streamer.io.pairLast && finalOutputRow
  val availableBuffers = VecInit((0 until bufferCount).map { buffer =>
    !bufferOccupied(buffer) ||
      (streamerFinishing && drainBuffer === buffer.U)
  })
  val bufferAvailable = availableBuffers.asUInt.orR
  val selectedFillBuffer = PriorityEncoder(availableBuffers.asUInt)
  io.rotateReady := allRotateReady && bufferAvailable &&
    pendingBuffers.io.enq.ready
  val rotateFire = io.rotateStart && io.rotateReady
  io.prefetchStartReady := allPrefetchStartReady && bufferAvailable &&
    pendingBuffers.io.enq.ready

  pendingBuffers.io.enq.valid := rotateFire
  pendingBuffers.io.enq.bits := selectedFillBuffer
  pendingBuffers.io.deq.ready := allDecompositionDone
  when(allDecompositionDone) {
    assert(pendingBuffers.io.deq.valid, "missing bitwise result buffer")
  }

  io.loadReady := allLoadReady
  io.loadDone := allLoadDone
  io.prefetchReady := allPrefetchReady
  io.prefetchDone := allPrefetchDone
  io.updateReady := allUpdateReady
  io.updateDone := allUpdateDone
  io.drainValid := allDrainValid
  io.drainDone := allDrainDone
  io.loaded := allLoaded
  for ((frontend, component) <- components.zipWithIndex) {
    frontend.io.loadStart := io.loadStart
    frontend.io.loadValid := io.loadValid && allLoadReady
    frontend.io.load := io.load(component)
    frontend.io.rotateStart := rotateFire
    frontend.io.exponent := io.exponent
    frontend.io.prefetchStart := io.prefetchStart
    frontend.io.prefetchValid := io.prefetchValid && allPrefetchReady
    frontend.io.prefetchLow := io.prefetchLow(component)
    frontend.io.prefetchHigh := io.prefetchHigh(component)
    frontend.io.updateStart := io.updateStart
    frontend.io.updateValid := io.updateValid && allUpdateReady
    for (lane <- 0 until config.inverseLanes) {
      frontend.io.updateLow(lane) := (io.updateLow(component)(lane).asUInt <<
        config.torusShift)(config.torusWidth - 1, 0)
      frontend.io.updateHigh(lane) := (io.updateHigh(component)(lane).asUInt <<
        config.torusShift)(config.torusWidth - 1, 0)
    }
    frontend.io.drainStart := io.drainStart
    frontend.io.drainReady := io.drainReady && allDrainValid
    io.drain(component) := frontend.io.drain
  }
  when(io.loadValid) {
    assert(io.loadReady, "bitwise frontend load data presented while idle")
  }
  when(io.rotateStart) {
    assert(io.rotateReady, "bitwise frontend rotation started while unavailable")
  }
  when(io.updateValid) {
    assert(io.updateReady, "bitwise frontend update data presented while idle")
  }
  when(io.prefetchValid) {
    assert(io.prefetchReady, "bitwise prefetch data presented while idle")
  }

  when(allDecompositionDone) {
    for (buffer <- 0 until bufferCount) {
      when(pendingBuffers.io.deq.bits === buffer.U) {
        for (component <- 0 until config.components) {
          digitBuffers(buffer)(component) :=
            components(component).io.centeredDigit
        }
      }
    }
  }
  readyBuffers.io.enq.valid := allDecompositionDone
  readyBuffers.io.enq.bits := pendingBuffers.io.deq.bits
  when(allDecompositionDone) {
    assert(readyBuffers.io.enq.ready, "bitwise digit buffer queue overflow")
  }

  val streamerCanStart = io.streamEnable && !streamer.io.busy
  val streamerStart = streamerCanStart && readyBuffers.io.deq.valid
  readyBuffers.io.deq.ready := streamerCanStart
  streamer.io.start := streamerStart
  val streamerFlowStart = streamerStart && !streamer.io.busy
  val selectedDrainBuffer = Mux(
    streamerFlowStart,
    readyBuffers.io.deq.bits,
    drainBuffer
  )
  // A flow-through queue can start the streamer in the same cycle that the
  // completed digits are written. Bypass those registers for its first pair;
  // subsequent pairs use the newly written buffer.
  val directCompletionStart = streamerFlowStart &&
    readyBuffers.io.count === 0.U && allDecompositionDone
  for (component <- 0 until config.components) {
    for (level <- 0 until config.levels) {
      for (position <- 0 until config.polynomialSize) {
        streamer.io.digits(component)(level)(position) := Mux(
          directCompletionStart,
          components(component).io.centeredDigit(level)(position),
          digitBuffers(selectedDrainBuffer)(component)(level)(position)
        )
      }
    }
  }
  when(streamerStart) {
    drainBuffer := readyBuffers.io.deq.bits
  }

  when(streamerFinishing) {
    for (buffer <- 0 until bufferCount) {
      when(drainBuffer === buffer.U) {
        bufferOccupied(buffer) := false.B
      }
    }
  }
  when(rotateFire) {
    for (buffer <- 0 until bufferCount) {
      when(selectedFillBuffer === buffer.U) {
        bufferOccupied(buffer) := true.B
      }
    }
  }

  streamer.io.pairReady := io.pairReady
  io.pairValid := streamer.io.pairValid
  io.coefficientLow := streamer.io.coefficientLow
  io.coefficientHigh := streamer.io.coefficientHigh
  io.rowIndex := streamer.io.rowIndex
  io.pairLast := streamer.io.pairLast
  io.transformStart := streamer.io.transformStart
  io.busy := frontendBusy
  io.idle := !frontendBusy
  io.done := streamer.io.done
}

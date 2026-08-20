package fpt

import chisel3._
import chisel3.util._

sealed trait ExternalProductMultiplier

object ExternalProductMultiplier {
  case object Schoolbook extends ExternalProductMultiplier
  case object ExactGaussDsp extends ExternalProductMultiplier
  case object ExactPipelinedSchoolbookDsp extends ExternalProductMultiplier
  case object ExactGaussTwoLimbDsp extends ExternalProductMultiplier
}

final case class ExternalProductConfig(
    points: Int,
    inputLanes: Int,
    outputLanes: Int,
    rows: Int,
    outputComponents: Int,
    spectrum: FixedFormat,
    bootstrappingKey: FixedFormat,
    accumulator: FixedFormat,
    multiplier: ExternalProductMultiplier =
      ExternalProductMultiplier.Schoolbook
) {
  require(points >= 1 && isPow2(points))
  require(inputLanes >= 1 && isPow2(inputLanes) && inputLanes <= points)
  require(outputLanes >= 1 && isPow2(outputLanes) && outputLanes <= points)
  require(inputLanes >= outputLanes && inputLanes % outputLanes == 0)
  require(rows >= 1)
  require(outputComponents >= 1)

  val inputFrameBeats: Int = points / inputLanes
  val outputFrameBeats: Int = points / outputLanes
  val outputGroupsPerInputBeat: Int = inputLanes / outputLanes
  val productFractionalBits: Int =
    spectrum.fractionalBits + bootstrappingKey.fractionalBits
  val productShift: Int = productFractionalBits - accumulator.fractionalBits
  require(productShift >= 0)
  require(
    productShift + accumulator.width <=
      spectrum.width + bootstrappingKey.width + 1
  )
  if (multiplier == ExternalProductMultiplier.ExactGaussDsp) {
    require(spectrum.width > 27 && spectrum.width <= 35)
    require(bootstrappingKey.width >= 2 && bootstrappingKey.width <= 27)
  }
  if (
    multiplier == ExternalProductMultiplier.ExactPipelinedSchoolbookDsp
  ) {
    require(spectrum.width > 27 && spectrum.width <= 35)
    require(bootstrappingKey.width >= 2 && bootstrappingKey.width <= 27)
    require(
      inputFrameBeats >= PipelinedExactSchoolbookComplexMultiply.latency + 1,
      "the pipelined product and accumulator commit must complete before an accumulator address repeats"
    )
  }
  if (multiplier == ExternalProductMultiplier.ExactGaussTwoLimbDsp) {
    require(spectrum.width >= 36 && spectrum.width <= 51)
    require(bootstrappingKey.width >= 28 && bootstrappingKey.width <= 33)
  }
}

private[fpt] object ExternalProductMultiply {
  def apply(
      a: ComplexSInt,
      b: ComplexSInt,
      config: ExternalProductConfig
  ): (SInt, SInt) = config.multiplier match {
    case ExternalProductMultiplier.Schoolbook =>
      val ac = a.real * b.real
      val bd = a.imag * b.imag
      val ad = a.real * b.imag
      val bc = a.imag * b.real
      (ac -& bd, ad +& bc)
    case ExternalProductMultiplier.ExactGaussDsp =>
      val multiply = Module(
        new ExactGaussComplexMultiply(
          config.spectrum.width,
          config.bootstrappingKey.width
        )
      )
      multiply.io.a := a
      multiply.io.b := b
      (multiply.io.productReal, multiply.io.productImag)
    case ExternalProductMultiplier.ExactPipelinedSchoolbookDsp =>
      // This fallback keeps the configuration numerically usable in simple
      // reference accumulators. The synchronous physical accumulator below
      // replaces it with the explicitly registered DSP implementation.
      val ac = a.real * b.real
      val bd = a.imag * b.imag
      val ad = a.real * b.imag
      val bc = a.imag * b.real
      (ac -& bd, ad +& bc)
    case ExternalProductMultiplier.ExactGaussTwoLimbDsp =>
      val multiply = Module(
        new ExactGaussTwoLimbComplexMultiply(
          config.spectrum.width,
          config.bootstrappingKey.width
        )
      )
      multiply.io.a := a
      multiply.io.b := b
      (multiply.io.productReal, multiply.io.productImag)
  }
}

/** Lane-parallel frequency-domain accumulator for a TFHE External Product.
  * One transformed decomposition row is supplied over `inputFrameBeats`
  * cycles, followed immediately by the next row. The output lane count is
  * independent so the paper's wider forward and narrower inverse streams can
  * meet here without another repacking memory. A start pulse precedes the
  * first input beat by one cycle. The first row overwrites stale storage, so
  * no multi-cycle memory clear is required.
  *
  * This reference uses a banked register array. The same interface can later
  * be backed by lane-banked RAM without changing CMUX scheduling.
  */
final class ExternalProductAccumulator(val config: ExternalProductConfig)
    extends Module {
  import TransformUtil._

  private val rowWidth = counterWidth(config.rows)
  private val inputBeatWidth = counterWidth(config.inputFrameBeats)
  private val outputBeatWidth = counterWidth(config.outputFrameBeats)
  private val pointWidth = counterWidth(config.points)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val decomposition = Input(
      Vec(config.inputLanes, new ComplexSInt(config.spectrum.width))
    )
    val bootstrappingKey = Input(
      Vec(
        config.outputComponents,
        Vec(
          config.inputLanes,
          new ComplexSInt(config.bootstrappingKey.width)
        )
      )
    )
    val keyRow = Output(UInt(rowWidth.W))
    val pointIndex = Output(Vec(config.inputLanes, UInt(pointWidth.W)))

    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val output = Output(
      Vec(
        config.outputComponents,
        Vec(config.outputLanes, new ComplexSInt(config.accumulator.width))
      )
    )
    val busy = Output(Bool())
    val done = Output(Bool())
  })

  // Store one point per physical input lane and input beat. Reorganizing the
  // banks as [output group][output lane][beat] makes every input write bank
  // static and limits output selection to inputLanes/outputLanes banks. In the
  // Set-II datapath this is a two-way selection, rather than a 512-way
  // dynamically indexed register Vec for every output.
  val accumulatorMemory = Reg(
    Vec(
      config.outputComponents,
      Vec(
        config.outputGroupsPerInputBeat,
        Vec(
          config.outputLanes,
          Vec(
            config.inputFrameBeats,
            new ComplexSInt(config.accumulator.width)
          )
        )
      )
    )
  )
  val inputActive = RegInit(false.B)
  val outputActive = RegInit(false.B)
  val row = RegInit(0.U(rowWidth.W))
  val inputBeat = RegInit(0.U(inputBeatWidth.W))
  val outputBeat = RegInit(0.U(outputBeatWidth.W))
  val doneReg = RegInit(false.B)

  io.inputReady := inputActive
  io.outputValid := outputActive
  io.busy := inputActive || outputActive
  io.done := doneReg
  io.keyRow := row
  doneReg := false.B

  val inputAddress = Wire(Vec(config.inputLanes, UInt(pointWidth.W)))
  for (lane <- 0 until config.inputLanes) {
    inputAddress(lane) := indexedAddress(
      inputBeat, config.inputLanes, lane, pointWidth
    )
    io.pointIndex(lane) := inputAddress(lane)
  }

  private val groupBits = log2Ceil(config.outputGroupsPerInputBeat)
  val outputGroup = if (config.outputGroupsPerInputBeat == 1) {
    0.U
  } else {
    outputBeat(groupBits - 1, 0)
  }
  val outputDepth = if (config.outputGroupsPerInputBeat == 1) {
    outputBeat
  } else {
    outputBeat >> groupBits
  }

  for (component <- 0 until config.outputComponents) {
    for (lane <- 0 until config.outputLanes) {
      val groupValues = VecInit(
        (0 until config.outputGroupsPerInputBeat).map(group =>
          accumulatorMemory(component)(group)(lane)(outputDepth)
        )
      )
      io.output(component)(lane) := groupValues(outputGroup)
    }
  }

  when(io.start) {
    assert(!io.busy, "External Product started while active")
    inputActive := true.B
    outputActive := false.B
    row := 0.U
    inputBeat := 0.U
  }

  when(io.inputValid) {
    assert(inputActive, "External Product input presented while idle")
  }

  when(io.inputValid && io.inputReady) {
    for (component <- 0 until config.outputComponents) {
      for (lane <- 0 until config.inputLanes) {
        val inputGroup = lane / config.outputLanes
        val outputLane = lane % config.outputLanes
        val a = io.decomposition(lane)
        val b = io.bootstrappingKey(component)(lane)
        val (productReal, productImag) =
          ExternalProductMultiply(a, b, config)
        val quantizedReal = FixedPointBits.shiftedLowSigned(
          productReal,
          config.productShift,
          config.accumulator.width
        )
        val quantizedImag = FixedPointBits.shiftedLowSigned(
          productImag,
          config.productShift,
          config.accumulator.width
        )
        val previousReal = Mux(
          row === 0.U,
          0.S(config.accumulator.width.W),
          accumulatorMemory(component)(inputGroup)(outputLane)(inputBeat).real
        )
        val previousImag = Mux(
          row === 0.U,
          0.S(config.accumulator.width.W),
          accumulatorMemory(component)(inputGroup)(outputLane)(inputBeat).imag
        )
        accumulatorMemory(component)(inputGroup)(outputLane)(inputBeat).real :=
          FixedPointBits.lowSigned(
            previousReal + quantizedReal,
            config.accumulator.width
          )
        accumulatorMemory(component)(inputGroup)(outputLane)(inputBeat).imag :=
          FixedPointBits.lowSigned(
            previousImag + quantizedImag,
            config.accumulator.width
          )
      }
    }

    when(inputBeat === (config.inputFrameBeats - 1).U) {
      inputBeat := 0.U
      when(row === (config.rows - 1).U) {
        row := 0.U
        inputActive := false.B
        outputActive := true.B
        outputBeat := 0.U
      }.otherwise {
        row := row + 1.U
      }
    }.otherwise {
      inputBeat := inputBeat + 1.U
    }
  }

  when(io.outputValid && io.outputReady) {
    when(outputBeat === (config.outputFrameBeats - 1).U) {
      outputBeat := 0.U
      outputActive := false.B
      doneReg := true.B
    }.otherwise {
      outputBeat := outputBeat + 1.U
    }
  }
}

/** Full-throughput External Product accumulator with the PISO double buffer
  * described in Section 3.3 of the FPT paper. One buffer accepts the next
  * transaction's wide forward-FFT stream while the other emits the previous
  * transaction through the narrower inverse-FFT interface.
  *
  * `inputFirst` marks the first decomposition beat and travels with `inputTag`.
  * Transactions may be adjacent without an idle cycle. The output tag allows
  * the downstream inverse pipeline to route its delayed result back to the
  * corresponding batch accumulator. The default register-array storage is
  * convenient for small simulations; `useSynchronousMemory` packs each wide
  * beat into a pair of inferred synchronous memories for paper-scale RTL.
  */
final class DoubleBufferedExternalProductAccumulator(
    val config: ExternalProductConfig,
    val tagWidth: Int,
    val serializeComponents: Boolean = false,
    val useSynchronousMemory: Boolean = false
) extends Module {
  import TransformUtil._
  require(tagWidth >= 1)
  require(!serializeComponents || config.outputComponents >= 2)

  private val rowWidth = counterWidth(config.rows)
  private val inputBeatWidth = counterWidth(config.inputFrameBeats)
  private val outputBeatWidth = counterWidth(config.outputFrameBeats)
  private val outputComponentWidth = counterWidth(config.outputComponents)
  private val pointWidth = counterWidth(config.points)
  private val bufferCount = 2

  val io = IO(new Bundle {
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val inputFirst = Input(Bool())
    val inputTag = Input(UInt(tagWidth.W))
    val decomposition = Input(
      Vec(config.inputLanes, new ComplexSInt(config.spectrum.width))
    )
    val bootstrappingKey = Input(
      Vec(
        config.outputComponents,
        Vec(
          config.inputLanes,
          new ComplexSInt(config.bootstrappingKey.width)
        )
      )
    )
    val keyRow = Output(UInt(rowWidth.W))
    val pointIndex = Output(Vec(config.inputLanes, UInt(pointWidth.W)))

    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val outputStart = Output(Bool())
    val outputFirst = Output(Bool())
    val outputLast = Output(Bool())
    val outputComponent = Output(UInt(outputComponentWidth.W))
    val outputTag = Output(UInt(tagWidth.W))
    val output = Output(
      Vec(
        config.outputComponents,
        Vec(config.outputLanes, new ComplexSInt(config.accumulator.width))
      )
    )
    val done = Output(Bool())
    val doneTag = Output(UInt(tagWidth.W))
    val busy = Output(Bool())
  })

  private val groupBits = log2Ceil(config.outputGroupsPerInputBeat)

  if (!useSynchronousMemory) {
    require(
      config.multiplier !=
        ExternalProductMultiplier.ExactPipelinedSchoolbookDsp,
      "the pipelined DSP multiplier requires synchronous accumulator storage"
    )
    val accumulatorMemory = Reg(
    Vec(
      bufferCount,
      Vec(
        config.outputComponents,
        Vec(
          config.outputGroupsPerInputBeat,
          Vec(
            config.outputLanes,
            Vec(
              config.inputFrameBeats,
              new ComplexSInt(config.accumulator.width)
            )
          )
        )
      )
    )
  )
  val bankTags = Reg(Vec(bufferCount, UInt(tagWidth.W)))
  val bankReady = RegInit(VecInit(Seq.fill(bufferCount)(false.B)))
  val writeBank = RegInit(0.U(1.W))
  val inputActive = RegInit(false.B)
  val row = RegInit(0.U(rowWidth.W))
  val inputBeat = RegInit(0.U(inputBeatWidth.W))
  val outputActive = RegInit(false.B)
  val outputBank = RegInit(0.U(1.W))
  val outputComponent = RegInit(0.U(outputComponentWidth.W))
  val outputBeat = RegInit(0.U(outputBeatWidth.W))
  val doneReg = RegInit(false.B)
  val doneTagReg = RegInit(0.U(tagWidth.W))

  val writeBankFree = !bankReady(writeBank) &&
    !(outputActive && outputBank === writeBank)
  io.inputReady := inputActive || (io.inputFirst && writeBankFree)
  val inputFire = io.inputValid && io.inputReady
  val activeRow = Mux(inputActive, row, 0.U)
  val activeInputBeat = Mux(inputActive, inputBeat, 0.U)
  val finalInputBeat = inputFire &&
    activeRow === (config.rows - 1).U &&
    activeInputBeat === (config.inputFrameBeats - 1).U

  io.keyRow := activeRow
  for (lane <- 0 until config.inputLanes) {
    io.pointIndex(lane) := indexedAddress(
      activeInputBeat, config.inputLanes, lane, pointWidth
    )
  }

  val outputGroup = if (config.outputGroupsPerInputBeat == 1) {
    0.U
  } else {
    outputBeat(groupBits - 1, 0)
  }
  val outputDepth = if (config.outputGroupsPerInputBeat == 1) {
    outputBeat
  } else {
    outputBeat >> groupBits
  }
  for (component <- 0 until config.outputComponents) {
    for (lane <- 0 until config.outputLanes) {
      val bankValues = VecInit((0 until bufferCount).map { buffer =>
        val groupValues = VecInit(
          (0 until config.outputGroupsPerInputBeat).map(group =>
            accumulatorMemory(buffer)(component)(group)(lane)(outputDepth)
          )
        )
        groupValues(outputGroup)
      })
      io.output(component)(lane) := bankValues(outputBank)
    }
  }

  io.outputValid := outputActive
  io.outputFirst := outputActive && outputComponent === 0.U &&
    outputBeat === 0.U
  val outputFrameLast = outputActive &&
    outputBeat === (config.outputFrameBeats - 1).U
  io.outputLast := outputFrameLast &&
    outputComponent === (config.outputComponents - 1).U
  io.outputComponent := outputComponent
  io.outputTag := bankTags(outputBank)
  io.done := doneReg
  io.doneTag := doneTagReg
  io.busy := inputActive || outputActive || bankReady.asUInt.orR
  doneReg := false.B

  when(io.inputValid) {
    assert(
      inputActive || io.inputFirst,
      "first External Product beat must carry inputFirst"
    )
  }
  when(io.inputFirst && io.inputValid) {
    assert(!inputActive, "inputFirst asserted inside a transaction")
  }

  when(inputFire) {
    when(io.inputFirst) {
      bankTags(writeBank) := io.inputTag
    }
    for (buffer <- 0 until bufferCount) {
      when(writeBank === buffer.U) {
        for (component <- 0 until config.outputComponents) {
          for (lane <- 0 until config.inputLanes) {
            val inputGroup = lane / config.outputLanes
            val outputLane = lane % config.outputLanes
            val a = io.decomposition(lane)
            val b = io.bootstrappingKey(component)(lane)
            val (productReal, productImag) =
              ExternalProductMultiply(a, b, config)
            val quantizedReal = FixedPointBits.shiftedLowSigned(
              productReal,
              config.productShift,
              config.accumulator.width
            )
            val quantizedImag = FixedPointBits.shiftedLowSigned(
              productImag,
              config.productShift,
              config.accumulator.width
            )
            val previousReal = Mux(
              activeRow === 0.U,
              0.S(config.accumulator.width.W),
              accumulatorMemory(buffer)(component)(inputGroup)(outputLane)(
                activeInputBeat
              ).real
            )
            val previousImag = Mux(
              activeRow === 0.U,
              0.S(config.accumulator.width.W),
              accumulatorMemory(buffer)(component)(inputGroup)(outputLane)(
                activeInputBeat
              ).imag
            )
            accumulatorMemory(buffer)(component)(inputGroup)(outputLane)(
              activeInputBeat
            ).real := FixedPointBits.lowSigned(
              previousReal + quantizedReal,
              config.accumulator.width
            )
            accumulatorMemory(buffer)(component)(inputGroup)(outputLane)(
              activeInputBeat
            ).imag := FixedPointBits.lowSigned(
              previousImag + quantizedImag,
              config.accumulator.width
            )
          }
        }
      }
    }

    when(finalInputBeat) {
      inputActive := false.B
      row := 0.U
      inputBeat := 0.U
      writeBank := ~writeBank
    }.otherwise {
      inputActive := true.B
      when(activeInputBeat === (config.inputFrameBeats - 1).U) {
        inputBeat := 0.U
        row := activeRow + 1.U
      }.otherwise {
        inputBeat := activeInputBeat + 1.U
        row := activeRow
      }
    }
  }

  val outputFire = io.outputValid && io.outputReady
  val finalOutputBeat = outputFire &&
    (if (serializeComponents) io.outputLast else outputFrameLast)
  val readyNext = Wire(Vec(bufferCount, Bool()))
  readyNext := bankReady
  for (buffer <- 0 until bufferCount) {
    when(finalInputBeat && writeBank === buffer.U) {
      readyNext(buffer) := true.B
    }
    when(finalOutputBeat && outputBank === buffer.U) {
      readyNext(buffer) := false.B
    }
  }
  bankReady := readyNext
  io.outputStart := (!outputActive && readyNext.asUInt.orR) ||
    (serializeComponents.B && outputFire && outputFrameLast &&
      (!io.outputLast || readyNext.asUInt.orR))

  def startReadyOutput(): Unit = {
    when(readyNext(0)) {
      outputActive := true.B
      outputBank := 0.U
      outputComponent := 0.U
      outputBeat := 0.U
    }.elsewhen(readyNext(1)) {
      outputActive := true.B
      outputBank := 1.U
      outputComponent := 0.U
      outputBeat := 0.U
    }.otherwise {
      outputActive := false.B
      outputComponent := 0.U
      outputBeat := 0.U
    }
  }

  when(!outputActive) {
    startReadyOutput()
  }.elsewhen(outputFire) {
    when(finalOutputBeat) {
      doneReg := true.B
      doneTagReg := bankTags(outputBank)
      startReadyOutput()
    }.elsewhen(
      serializeComponents.B && outputFrameLast
    ) {
      outputComponent := outputComponent + 1.U
      outputBeat := 0.U
    }.otherwise {
      outputBeat := outputBeat + 1.U
    }
  }
  } else {
    require(
      config.inputFrameBeats >= 2,
      "synchronous External Product storage needs at least two input beats"
    )

    def memoryWordType =
      Vec(
        config.outputComponents,
        Vec(
          config.outputGroupsPerInputBeat,
          Vec(
            config.outputLanes,
            new ComplexSInt(config.accumulator.width)
          )
        )
      )

    val memoryWordWidth = config.outputComponents *
      config.outputGroupsPerInputBeat * config.outputLanes * 2 *
      config.accumulator.width
    // Give the first ping-pong bank one harmless padding bit so CIRCT emits
    // distinct inferred-memory modules for the two banks. The U280 physical
    // emitter can then map only that bank into otherwise-unused UltraRAM,
    // instead of forcing both very shallow 15K-bit-wide banks into either
    // LUTRAM or the same RAM style. The padding bit is never read.
    val accumulatorMemoryWidths =
      Seq(memoryWordWidth + 1, memoryWordWidth)
    val accumulatorMemories = accumulatorMemoryWidths.map { width =>
      // The added accumulator commit register can write an address on the
      // same edge that the next row reads it. Explicit write-first behavior
      // forwards that just-completed sum into the next accumulation.
      SyncReadMem(
        config.inputFrameBeats,
        UInt(width.W),
        SyncReadMem.WriteFirst
      )
    }

    val bankTags = Reg(Vec(bufferCount, UInt(tagWidth.W)))
    val bankReady = RegInit(VecInit(Seq.fill(bufferCount)(false.B)))
    val writeBank = RegInit(0.U(1.W))
    val inputActive = RegInit(false.B)
    val row = RegInit(0.U(rowWidth.W))
    val inputBeat = RegInit(0.U(inputBeatWidth.W))
    val outputActive = RegInit(false.B)
    val outputBank = RegInit(0.U(1.W))
    val outputComponent = RegInit(0.U(outputComponentWidth.W))
    val outputBeat = RegInit(0.U(outputBeatWidth.W))
    val outputHeldValid = RegInit(false.B)
    val doneReg = RegInit(false.B)
    val doneTagReg = RegInit(0.U(tagWidth.W))

    val releasingWriteBank = Wire(Bool())
    val writeBankFree = (!bankReady(writeBank) &&
      !(outputActive && outputBank === writeBank)) || releasingWriteBank
    io.inputReady := inputActive || (io.inputFirst && writeBankFree)
    val inputFire = io.inputValid && io.inputReady
    val activeRow = Mux(inputActive, row, 0.U)
    val activeInputBeat = Mux(inputActive, inputBeat, 0.U)
    val finalInputBeat = inputFire &&
      activeRow === (config.rows - 1).U &&
      activeInputBeat === (config.inputFrameBeats - 1).U

    io.keyRow := activeRow
    for (lane <- 0 until config.inputLanes) {
      io.pointIndex(lane) := indexedAddress(
        activeInputBeat,
        config.inputLanes,
        lane,
        pointWidth
      )
    }

    def beatGroup(beat: UInt): UInt =
      if (config.outputGroupsPerInputBeat == 1) 0.U
      else beat(groupBits - 1, 0)
    def beatDepth(beat: UInt): UInt =
      if (config.outputGroupsPerInputBeat == 1) beat
      else beat >> groupBits

    val usePipelinedProduct = config.multiplier ==
      ExternalProductMultiplier.ExactPipelinedSchoolbookDsp
    val productPipelineCycles =
      if (usePipelinedProduct)
        PipelinedExactSchoolbookComplexMultiply.latency
      else 1

    val outputReadResponse = RegInit(false.B)
    val outputReadBank = RegInit(0.U(1.W))
    val outputReadGroup = RegInit(0.U(math.max(1, groupBits).W))
    io.outputValid := outputHeldValid || outputReadResponse
    io.outputFirst := io.outputValid && outputComponent === 0.U &&
      outputBeat === 0.U
    val outputFrameLast = io.outputValid &&
      outputBeat === (config.outputFrameBeats - 1).U
    io.outputLast := outputFrameLast &&
      outputComponent === (config.outputComponents - 1).U
    io.outputComponent := outputComponent
    io.outputTag := bankTags(outputBank)
    io.done := doneReg
    io.doneTag := doneTagReg
    io.busy := inputActive || outputActive || bankReady.asUInt.orR
    doneReg := false.B

    val outputFire = io.outputValid && io.outputReady
    val finalOutputBeat = outputFire &&
      (if (serializeComponents) io.outputLast else outputFrameLast)
    releasingWriteBank := finalOutputBeat && outputBank === writeBank
    val readyNext = Wire(Vec(bufferCount, Bool()))
    readyNext := bankReady
    for (buffer <- 0 until bufferCount) {
      when(finalInputBeat && writeBank === buffer.U) {
        readyNext(buffer) := true.B
      }
      when(finalOutputBeat && outputBank === buffer.U) {
        readyNext(buffer) := false.B
      }
    }
    bankReady := readyNext

    val readableReady = Wire(Vec(bufferCount, Bool()))
    for (buffer <- 0 until bufferCount) {
      // A bank becomes logically ready on its final input beat, but its last
      // synchronous read and following write still occupy the memory ports.
      readableReady(buffer) := readyNext(buffer) &&
        !(inputFire && writeBank === buffer.U)
    }
    val readyBank = Mux(readableReady(0), 0.U, 1.U)
    val idleRead = !outputActive && readableReady.asUInt.orR
    val nextTransactionRead = finalOutputBeat && readableReady.asUInt.orR
    val continuingRead = outputFire && !finalOutputBeat
    val outputReadIssue = idleRead || nextTransactionRead || continuingRead
    val startingRead = idleRead || nextTransactionRead
    val nextFrameRead = continuingRead && serializeComponents.B &&
      outputFrameLast
    val issueBank = Mux(startingRead, readyBank, outputBank)
    val issueBeat = Mux(
      startingRead || nextFrameRead,
      0.U,
      outputBeat + 1.U
    )
    io.outputStart := outputReadIssue &&
      (startingRead || nextFrameRead)

    val productWord = Wire(memoryWordType)
    for (component <- 0 until config.outputComponents) {
      for (group <- 0 until config.outputGroupsPerInputBeat) {
        for (lane <- 0 until config.outputLanes) {
          val inputLane = group * config.outputLanes + lane
          val a = io.decomposition(inputLane)
          val b = io.bootstrappingKey(component)(inputLane)
          val (productReal, productImag) =
            if (usePipelinedProduct) {
              val multiply = Module(
                new PipelinedExactSchoolbookComplexMultiply(
                  config.spectrum.width,
                  config.bootstrappingKey.width
                )
              )
              multiply.io.a := a
              multiply.io.b := b
              (multiply.io.productReal, multiply.io.productImag)
            } else {
              ExternalProductMultiply(a, b, config)
            }
          productWord(component)(group)(lane).real :=
            FixedPointBits.shiftedLowSigned(
              productReal,
              config.productShift,
              config.accumulator.width
            )
          productWord(component)(group)(lane).imag :=
            FixedPointBits.shiftedLowSigned(
              productImag,
              config.productShift,
              config.accumulator.width
            )
        }
      }
    }
    val pendingProduct =
      if (usePipelinedProduct) productWord
      else {
        val registered = Reg(memoryWordType)
        when(inputFire) {
          registered := productWord
        }
        registered
      }

    val firstPendingInputValid = RegNext(inputFire, false.B)
    val firstPendingFirstRow = RegEnable(
      activeRow === 0.U,
      false.B,
      inputFire
    )
    val firstPendingInputBeat = RegEnable(
      activeInputBeat,
      0.U(inputBeatWidth.W),
      inputFire
    )
    val firstPendingWriteBank = RegEnable(writeBank, 0.U(1.W), inputFire)
    val remainingProductCycles = productPipelineCycles - 1
    val pendingInputValid = ShiftRegister(
      firstPendingInputValid,
      remainingProductCycles,
      false.B,
      true.B
    )
    val pendingFirstRow = ShiftRegister(
      firstPendingFirstRow,
      remainingProductCycles
    )
    val pendingInputBeat = ShiftRegister(
      firstPendingInputBeat,
      remainingProductCycles
    )
    val pendingWriteBank = ShiftRegister(
      firstPendingWriteBank,
      remainingProductCycles
    )

    val memoryReadEnable = Wire(Vec(bufferCount, Bool()))
    val memoryReadAddress = Wire(
      Vec(bufferCount, UInt(inputBeatWidth.W))
    )
    for (buffer <- 0 until bufferCount) {
      // Row zero overwrites stale accumulator contents, so it does not need
      // a memory read.  Besides avoiding useless UltraRAM activity, this
      // removes the only cycle where bankReady participates in inputFire
      // and could otherwise feed the wide read-address decode.
      val inputRead = inputFire && activeRow =/= 0.U &&
        writeBank === buffer.U
      val outputRead = outputReadIssue && issueBank === buffer.U
      assert(!(inputRead && outputRead), "External Product bank read conflict")
      memoryReadEnable(buffer) := inputRead || outputRead
      memoryReadAddress(buffer) := Mux(
        inputRead,
        activeInputBeat,
        beatDepth(issueBeat)
      )
    }
    val memoryReadWords = accumulatorMemories.zipWithIndex.map {
      case (memory, buffer) =>
        memory
          .read(memoryReadAddress(buffer), memoryReadEnable(buffer))(
            memoryWordWidth - 1,
            0
          )
    }

    val firstPendingPreviousWord = VecInit(memoryReadWords)(
      firstPendingWriteBank
    ).asTypeOf(memoryWordType)
    val pendingPreviousWord = ShiftRegister(
      firstPendingPreviousWord,
      remainingProductCycles
    )
    val accumulatedWord = Wire(memoryWordType)
    for (component <- 0 until config.outputComponents) {
      for (group <- 0 until config.outputGroupsPerInputBeat) {
        for (lane <- 0 until config.outputLanes) {
          val previous = pendingPreviousWord(component)(group)(lane)
          val product = pendingProduct(component)(group)(lane)
          accumulatedWord(component)(group)(lane).real :=
            FixedPointBits.lowSigned(
              Mux(
                pendingFirstRow,
                0.S(config.accumulator.width.W),
                previous.real
              ) + product.real,
              config.accumulator.width
            )
          accumulatedWord(component)(group)(lane).imag :=
            FixedPointBits.lowSigned(
              Mux(
                pendingFirstRow,
                0.S(config.accumulator.width.W),
                previous.imag
              ) + product.imag,
              config.accumulator.width
            )
        }
      }
    }

    // The product modules already register their outputs, but placing the
    // accumulator carry chain directly on the 15K-bit UltraRAM write word
    // still creates long product-to-memory routes. For the U280 multiplier,
    // register that complete word once more: the product registers can stay
    // with their DSPs and the commit registers can stay with the UltraRAMs.
    // The non-pipelined reference configuration retains its original latency
    // so its serialized double-buffer handoff remains cycle-for-cycle.
    val (
      accumulationWriteValid,
      accumulationWriteBeat,
      accumulationWriteBank,
      accumulationWriteWord
    ) = if (usePipelinedProduct) {
      val commitValid = RegNext(pendingInputValid, false.B)
      val commitBeat = RegEnable(
        pendingInputBeat,
        0.U(inputBeatWidth.W),
        pendingInputValid
      )
      val commitBank = RegEnable(
        pendingWriteBank,
        0.U(1.W),
        pendingInputValid
      )
      // Global synthesis retiming otherwise absorbs a normal Chisel register
      // back into the multiplier outputs, recreating a DSP-side carry chain
      // followed by a long route to the UltraRAM write port.
      val commitRegister = Module(new PhysicalCutRegister(memoryWordWidth))
      commitRegister.io.clock := clock
      // Validity is already delayed independently in commitValid. Clocking
      // the data cut only on a valid product makes pendingInputValid the CE
      // for the complete accumulator word (7,684 loads after placement).
      // Capture unconditionally so that valid remains a narrow control path.
      commitRegister.io.enable := true.B
      commitRegister.io.inputData := accumulatedWord.asUInt
      val commitWord = commitRegister.io.outputData.asTypeOf(memoryWordType)
      (commitValid, commitBeat, commitBank, commitWord)
    } else {
      (
        pendingInputValid,
        pendingInputBeat,
        pendingWriteBank,
        accumulatedWord
      )
    }
    for (buffer <- 0 until bufferCount) {
      when(accumulationWriteValid && accumulationWriteBank === buffer.U) {
        val writeWord =
          if (accumulatorMemoryWidths(buffer) == memoryWordWidth)
            accumulationWriteWord.asUInt
          else Cat(0.U(1.W), accumulationWriteWord.asUInt)
        accumulatorMemories(buffer).write(accumulationWriteBeat, writeWord)
      }
    }

    outputReadResponse := outputReadIssue
    when(outputReadIssue) {
      outputReadBank := issueBank
      outputReadGroup := beatGroup(issueBeat)
    }
    val outputWord = VecInit(memoryReadWords)(outputReadBank)
      .asTypeOf(memoryWordType)
    for (component <- 0 until config.outputComponents) {
      for (lane <- 0 until config.outputLanes) {
        val groups = VecInit(
          (0 until config.outputGroupsPerInputBeat).map(group =>
            outputWord(component)(group)(lane)
          )
        )
        io.output(component)(lane) := groups(outputReadGroup)
      }
    }
    when(outputFire) {
      outputHeldValid := false.B
    }.elsewhen(outputReadResponse) {
      outputHeldValid := true.B
    }

    when(io.inputValid) {
      assert(
        inputActive || io.inputFirst,
        "first External Product beat must carry inputFirst"
      )
    }
    when(io.inputFirst && io.inputValid) {
      assert(!inputActive, "inputFirst asserted inside a transaction")
    }
    when(inputFire) {
      when(io.inputFirst) {
        bankTags(writeBank) := io.inputTag
      }
      when(finalInputBeat) {
        inputActive := false.B
        row := 0.U
        inputBeat := 0.U
        writeBank := ~writeBank
      }.otherwise {
        inputActive := true.B
        when(activeInputBeat === (config.inputFrameBeats - 1).U) {
          inputBeat := 0.U
          row := activeRow + 1.U
        }.otherwise {
          inputBeat := activeInputBeat + 1.U
          row := activeRow
        }
      }
    }

    when(!outputActive) {
      when(readableReady.asUInt.orR) {
        outputActive := true.B
        outputBank := readyBank
        outputComponent := 0.U
        outputBeat := 0.U
      }
    }.elsewhen(outputFire) {
      when(finalOutputBeat) {
        doneReg := true.B
        doneTagReg := bankTags(outputBank)
        when(readableReady.asUInt.orR) {
          outputActive := true.B
          outputBank := readyBank
          outputComponent := 0.U
          outputBeat := 0.U
        }.otherwise {
          outputActive := false.B
          outputComponent := 0.U
          outputBeat := 0.U
        }
      }.elsewhen(serializeComponents.B && outputFrameLast) {
        outputComponent := outputComponent + 1.U
        outputBeat := 0.U
      }.otherwise {
        outputBeat := outputBeat + 1.U
      }
    }
  }
}

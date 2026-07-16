package fpt

import chisel3._
import chisel3.util._

sealed trait ExternalProductMultiplier

object ExternalProductMultiplier {
  case object Schoolbook extends ExternalProductMultiplier
  case object ExactGaussDsp extends ExternalProductMultiplier
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
  * corresponding batch accumulator.
  */
final class DoubleBufferedExternalProductAccumulator(
    val config: ExternalProductConfig,
    val tagWidth: Int,
    val serializeComponents: Boolean = false
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
}

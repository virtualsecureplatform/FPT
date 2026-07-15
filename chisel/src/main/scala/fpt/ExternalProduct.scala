package fpt

import chisel3._
import chisel3.util._

final case class ExternalProductConfig(
    points: Int,
    inputLanes: Int,
    outputLanes: Int,
    rows: Int,
    outputComponents: Int,
    spectrum: FixedFormat,
    bootstrappingKey: FixedFormat,
    accumulator: FixedFormat
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
        val ac = a.real * b.real
        val bd = a.imag * b.imag
        val ad = a.real * b.imag
        val bc = a.imag * b.real
        val productReal = ac -& bd
        val productImag = ad +& bc
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

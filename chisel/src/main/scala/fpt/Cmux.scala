package fpt

import chisel3._
import chisel3.util._

final case class CmuxCoefficientConfig(
    polynomialSize: Int,
    forwardLanes: Int,
    inverseLanes: Int,
    components: Int,
    levels: Int,
    baseBits: Int,
    torusWidth: Int,
    forwardFormat: FixedFormat,
    inverseFormat: FixedFormat
) {
  require(polynomialSize >= 4 && isPow2(polynomialSize))
  require(forwardLanes >= 1 && isPow2(forwardLanes))
  require(inverseLanes >= 1 && isPow2(inverseLanes))
  require(components >= 1)
  require(levels >= 1)
  require(baseBits >= 2)
  require(levels * baseBits < torusWidth)
  require(forwardFormat.fractionalBits + baseBits <= forwardFormat.width)
  require(inverseFormat.fractionalBits <= torusWidth)

  val points: Int = polynomialSize / 2
  require(forwardLanes <= points && points % forwardLanes == 0)
  require(inverseLanes <= points && points % inverseLanes == 0)
  require(polynomialSize % inverseLanes == 0)

  val forwardBeats: Int = points / forwardLanes
  val inverseBeats: Int = points / inverseLanes
  val polynomialBeats: Int = polynomialSize / inverseLanes
  val exponentWidth: Int = log2Ceil(polynomialSize) + 1
  val remainingBits: Int = torusWidth - levels * baseBits
  val roundOffset: BigInt = BigInt(1) << (remainingBits - 1)
  val decompositionOffset: BigInt = (1 to levels).map { level =>
    BigInt(1) << (torusWidth - level * baseBits + baseBits - 1)
  }.sum
  val decompositionBias: BigInt =
    (decompositionOffset + roundOffset) & ((BigInt(1) << torusWidth) - 1)
  val torusShift: Int = torusWidth - inverseFormat.fractionalBits
}

/** Coefficient-side storage and arithmetic for CMUX.
  *
  * The row stream implements `(X^a - 1) * accumulator`, standard centered
  * gadget decomposition, and conversion of the digits to the forward FFT
  * fixed-point format. The update stream converts normalized inverse-FFT
  * coefficients back to Torus and accumulates them in place. Transform and
  * bootstrapping-key scheduling are intentionally outside this block.
  */
final class CmuxCoefficientStore(val config: CmuxCoefficientConfig)
    extends Module {
  import TransformUtil._

  private val indexWidth = log2Ceil(config.polynomialSize)
  private val pointWidth = log2Ceil(config.points)
  private val componentWidth = counterWidth(config.components)
  private val levelWidth = counterWidth(config.levels)
  private val forwardBeatWidth = counterWidth(config.forwardBeats)
  private val inverseBeatWidth = counterWidth(config.inverseBeats)
  private val polynomialBeatWidth = counterWidth(config.polynomialBeats)

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

    val rowStart = Input(Bool())
    val rowComponent = Input(UInt(componentWidth.W))
    val rowLevel = Input(UInt(levelWidth.W))
    val exponent = Input(UInt(config.exponentWidth.W))
    val pairValid = Output(Bool())
    val pairReady = Input(Bool())
    val coefficientLow = Output(
      Vec(config.forwardLanes, SInt(config.forwardFormat.width.W))
    )
    val coefficientHigh = Output(
      Vec(config.forwardLanes, SInt(config.forwardFormat.width.W))
    )
    val rowDone = Output(Bool())

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

    val loaded = Output(Bool())
    val idle = Output(Bool())
  })

  val idle :: loading :: rowStreaming :: updating :: draining :: Nil = Enum(5)
  val state = RegInit(idle)
  val loadedReg = RegInit(false.B)
  val selectedComponent = RegInit(0.U(componentWidth.W))
  val selectedLevel = RegInit(0.U(levelWidth.W))
  val selectedExponent = RegInit(0.U(config.exponentWidth.W))
  val forwardBeat = RegInit(0.U(forwardBeatWidth.W))
  val inverseBeat = RegInit(0.U(inverseBeatWidth.W))
  val polynomialBeat = RegInit(0.U(polynomialBeatWidth.W))
  val loadDoneReg = RegInit(false.B)
  val rowDoneReg = RegInit(false.B)
  val updateDoneReg = RegInit(false.B)
  val drainDoneReg = RegInit(false.B)
  val memory = Reg(
    Vec(
      config.components,
      Vec(config.polynomialSize, UInt(config.torusWidth.W))
    )
  )

  io.loadReady := state === loading
  io.pairValid := state === rowStreaming
  io.updateReady := state === updating
  io.drainValid := state === draining
  io.loaded := loadedReg
  io.idle := state === idle
  io.loadDone := loadDoneReg
  io.rowDone := rowDoneReg
  io.updateDone := updateDoneReg
  io.drainDone := drainDoneReg
  loadDoneReg := false.B
  rowDoneReg := false.B
  updateDoneReg := false.B
  drainDoneReg := false.B

  when(PopCount(Cat(io.loadStart, io.rowStart, io.updateStart, io.drainStart)) > 1.U) {
    assert(false.B, "CMUX coefficient operations must not start together")
  }

  def polynomialAddress(base: UInt, lanes: Int, lane: Int): UInt =
    indexedAddress(base, lanes, lane, indexWidth)

  val loadAddress = Wire(Vec(config.inverseLanes, UInt(indexWidth.W)))
  val updateLowAddress = Wire(Vec(config.inverseLanes, UInt(indexWidth.W)))
  val updateHighAddress = Wire(Vec(config.inverseLanes, UInt(indexWidth.W)))
  val drainAddress = Wire(Vec(config.inverseLanes, UInt(indexWidth.W)))
  for (lane <- 0 until config.inverseLanes) {
    loadAddress(lane) := polynomialAddress(
      polynomialBeat, config.inverseLanes, lane
    )
    updateLowAddress(lane) := polynomialAddress(
      inverseBeat, config.inverseLanes, lane
    )
    updateHighAddress(lane) :=
      (updateLowAddress(lane) + config.points.U)(indexWidth - 1, 0)
    drainAddress(lane) := polynomialAddress(
      polynomialBeat, config.inverseLanes, lane
    )
    for (component <- 0 until config.components) {
      io.drain(component)(lane) := memory(component)(drainAddress(lane))
    }
  }

  def decomposedCoefficient(index: UInt): SInt = {
    val rotation = selectedExponent(indexWidth - 1, 0)
    val sourceIndex = (index - rotation)(indexWidth - 1, 0)
    val source = memory(selectedComponent)(sourceIndex)
    val current = memory(selectedComponent)(index)
    val negative = Mux(
      selectedExponent(indexWidth),
      index >= rotation,
      index < rotation
    )
    val negated = (0.U((config.torusWidth + 1).W) - source)(
      config.torusWidth - 1,
      0
    )
    val rotated = Mux(negative, negated, source)
    val difference = (rotated - current)(config.torusWidth - 1, 0)
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
    val centered = (digitBits - (BigInt(1) << (config.baseBits - 1)).U)(
      config.baseBits - 1,
      0
    ).asSInt
    val fixed = Wire(SInt(config.forwardFormat.width.W))
    fixed := centered << config.forwardFormat.fractionalBits
    fixed
  }

  for (lane <- 0 until config.forwardLanes) {
    val point = indexedAddress(
      forwardBeat, config.forwardLanes, lane, pointWidth
    )
    val polynomialPoint = point.pad(indexWidth)
    io.coefficientLow(lane) := decomposedCoefficient(polynomialPoint)
    io.coefficientHigh(lane) := decomposedCoefficient(
      (polynomialPoint + config.points.U)(indexWidth - 1, 0)
    )
  }

  when(io.loadStart) {
    assert(state === idle, "CMUX accumulator load started while active")
    state := loading
    polynomialBeat := 0.U
    loadedReg := false.B
  }
  when(io.rowStart) {
    assert(state === idle && loadedReg, "CMUX row started before load or while active")
    assert(io.rowComponent < config.components.U, "invalid CMUX component")
    assert(io.rowLevel < config.levels.U, "invalid CMUX level")
    state := rowStreaming
    selectedComponent := io.rowComponent
    selectedLevel := io.rowLevel
    selectedExponent := io.exponent
    forwardBeat := 0.U
  }
  when(io.updateStart) {
    assert(state === idle && loadedReg, "CMUX update started before load or while active")
    state := updating
    inverseBeat := 0.U
  }
  when(io.drainStart) {
    assert(state === idle && loadedReg, "CMUX drain started before load or while active")
    state := draining
    polynomialBeat := 0.U
  }

  when(io.loadValid) {
    assert(io.loadReady, "CMUX load data presented while not loading")
  }
  when(io.loadValid && io.loadReady) {
    for (component <- 0 until config.components) {
      for (lane <- 0 until config.inverseLanes) {
        memory(component)(loadAddress(lane)) := io.load(component)(lane)
      }
    }
    when(polynomialBeat === (config.polynomialBeats - 1).U) {
      polynomialBeat := 0.U
      state := idle
      loadedReg := true.B
      loadDoneReg := true.B
    }.otherwise {
      polynomialBeat := polynomialBeat + 1.U
    }
  }

  when(io.pairValid && io.pairReady) {
    when(forwardBeat === (config.forwardBeats - 1).U) {
      forwardBeat := 0.U
      state := idle
      rowDoneReg := true.B
    }.otherwise {
      forwardBeat := forwardBeat + 1.U
    }
  }

  when(io.updateValid) {
    assert(io.updateReady, "CMUX update data presented while not updating")
  }
  when(io.updateValid && io.updateReady) {
    for (component <- 0 until config.components) {
      for (lane <- 0 until config.inverseLanes) {
        val lowTorus = (io.updateLow(component)(lane).asUInt <<
          config.torusShift)(config.torusWidth - 1, 0)
        val highTorus = (io.updateHigh(component)(lane).asUInt <<
          config.torusShift)(config.torusWidth - 1, 0)
        memory(component)(updateLowAddress(lane)) :=
          memory(component)(updateLowAddress(lane)) + lowTorus
        memory(component)(updateHighAddress(lane)) :=
          memory(component)(updateHighAddress(lane)) + highTorus
      }
    }
    when(inverseBeat === (config.inverseBeats - 1).U) {
      inverseBeat := 0.U
      state := idle
      updateDoneReg := true.B
    }.otherwise {
      inverseBeat := inverseBeat + 1.U
    }
  }

  when(io.drainValid && io.drainReady) {
    when(polynomialBeat === (config.polynomialBeats - 1).U) {
      polynomialBeat := 0.U
      state := idle
      drainDoneReg := true.B
    }.otherwise {
      polynomialBeat := polynomialBeat + 1.U
    }
  }
}

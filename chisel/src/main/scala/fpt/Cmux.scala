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
    inverseFormat: FixedFormat,
    rotatorPipelineEvery: Int = 0,
    windowedRotator: Boolean = false
) {
  require(rotatorPipelineEvery >= 0)
  require(
    !(windowedRotator && rotatorPipelineEvery > 0),
    "the windowed rotator is stream-width and needs no pipeline layers"
  )
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

/** Polynomial-wide coefficient barrel rotator for multiplication by X^a in
  * Z[X]/(X^N+1). Each low exponent bit conditionally applies a power-of-two
  * rotation and negates coefficients that wrap; the high bit applies X^N.
  */
final class NegacyclicBarrelRotator(
    val polynomialSize: Int,
    val coefficientWidth: Int,
    val pipelineEvery: Int = 0
) extends Module {
  require(polynomialSize >= 2 && isPow2(polynomialSize))
  require(coefficientWidth >= 1)
  require(pipelineEvery >= 0)
  private val indexWidth = log2Ceil(polynomialSize)

  /** Register layers between mux stages; outputs lag inputs by this count.
    * The full-width combinational rotator places as one unroutable block at
    * Set-II width, so pipelined users cut it into locally routable layers.
    */
  val latency: Int =
    if (pipelineEvery == 0) 0 else indexWidth / pipelineEvery

  val io = IO(new Bundle {
    val input = Input(Vec(polynomialSize, UInt(coefficientWidth.W)))
    val exponent = Input(UInt((indexWidth + 1).W))
    val output = Output(Vec(polynomialSize, UInt(coefficientWidth.W)))
  })
  /** Pipeline advance strobe; absent (always advancing) when combinational. */
  val enable: Option[Bool] =
    if (latency > 0) Some(IO(Input(Bool()))) else None

  def negate(value: UInt): UInt =
    (0.U((coefficientWidth + 1).W) - value)(coefficientWidth - 1, 0)

  private val advance = enable.getOrElse(true.B)
  var stage: Seq[UInt] = io.input.toSeq
  private var exponentStage: UInt = io.exponent
  for (bit <- 0 until indexWidth) {
    val shift = 1 << bit
    val previous = stage
    stage = (0 until polynomialSize).map { index =>
      val sourceIndex = (index - shift + polynomialSize) % polynomialSize
      val shifted = if (index < shift) {
        negate(previous(sourceIndex))
      } else {
        previous(sourceIndex)
      }
      Mux(exponentStage(bit), shifted, previous(index))
    }
    if (pipelineEvery > 0 && (bit + 1) % pipelineEvery == 0) {
      stage = stage.map(value => RegEnable(value, advance))
      exponentStage = RegEnable(exponentStage, advance)
    }
  }
  for (index <- 0 until polynomialSize) {
    io.output(index) := Mux(
      exponentStage(indexWidth),
      negate(stage(index)),
      stage(index)
    )
  }
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
    val pairLast = Output(Bool())
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
  // Lane-bank the accumulator so load, inverse update, and drain have static
  // banks and only select a shallow beat address. Rotation is implemented by
  // a shared logarithmic barrel network below; independently indexing this
  // storage once per forward coefficient would create hundreds of duplicated
  // polynomial-wide muxes at the paper's lane count.
  val memory = Reg(
    Vec(
      config.components,
      Vec(
        config.inverseLanes,
        Vec(config.polynomialBeats, UInt(config.torusWidth.W))
      )
    )
  )

  io.loadReady := state === loading
  io.pairValid := state === rowStreaming
  io.pairLast := io.pairValid && io.pairReady &&
    forwardBeat === (config.forwardBeats - 1).U
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

  for (lane <- 0 until config.inverseLanes) {
    for (component <- 0 until config.components) {
      io.drain(component)(lane) := memory(component)(lane)(polynomialBeat)
    }
  }

  // Reconstruct the selected component with only a components-to-one mux per
  // coefficient. Each exponent bit then conditionally applies X^(2^bit),
  // including the sign on coefficients that wrap around X^N = -1. The high
  // exponent bit applies X^N by negating the complete result.
  val selectedPolynomial: Seq[UInt] =
    (0 until config.polynomialSize).map { index =>
      val bank = index % config.inverseLanes
      val depth = index / config.inverseLanes
      VecInit(
        (0 until config.components).map(component =>
          memory(component)(bank)(depth)
        )
      )(selectedComponent)
    }
  val rotator = Module(
    new NegacyclicBarrelRotator(
      config.polynomialSize,
      config.torusWidth
    )
  )
  rotator.io.input := VecInit(selectedPolynomial)
  rotator.io.exponent := selectedExponent
  val rotatedPolynomial = rotator.io.output

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
    val centered = (digitBits - (BigInt(1) << (config.baseBits - 1)).U)(
      config.baseBits - 1,
      0
    ).asSInt
    val fixed = Wire(SInt(config.forwardFormat.width.W))
    fixed := centered << config.forwardFormat.fractionalBits
    fixed
  }

  def streamedCoefficient(lane: Int, high: Boolean): SInt = {
    val halfOffset = if (high) config.points else 0
    val sourceValues = VecInit(
      (0 until config.forwardBeats).map { beat =>
        rotatedPolynomial(halfOffset + beat * config.forwardLanes + lane)
      }
    )
    val currentValues = VecInit(
      (0 until config.forwardBeats).map { beat =>
        selectedPolynomial(halfOffset + beat * config.forwardLanes + lane)
      }
    )
    decompose(sourceValues(forwardBeat), currentValues(forwardBeat))
  }

  for (lane <- 0 until config.forwardLanes) {
    io.coefficientLow(lane) := streamedCoefficient(lane, high = false)
    io.coefficientHigh(lane) := streamedCoefficient(lane, high = true)
  }

  when(io.loadStart) {
    assert(state === idle, "CMUX accumulator load started while active")
    state := loading
    polynomialBeat := 0.U
    loadedReg := false.B
  }
  when(io.rowStart) {
    assert(
      (state === idle || io.pairLast) && loadedReg,
      "CMUX row must start while idle or at the previous row boundary"
    )
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
        memory(component)(lane)(polynomialBeat) := io.load(component)(lane)
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
      state := Mux(io.rowStart, rowStreaming, idle)
      rowDoneReg := true.B
    }.otherwise {
      forwardBeat := forwardBeat + 1.U
    }
  }

  when(io.updateValid) {
    assert(io.updateReady, "CMUX update data presented while not updating")
  }
  when(io.updateValid && io.updateReady) {
    val updateLowDepth = inverseBeat.pad(polynomialBeatWidth)
    val updateHighDepth = updateLowDepth + config.inverseBeats.U
    for (component <- 0 until config.components) {
      for (lane <- 0 until config.inverseLanes) {
        val lowTorus = (io.updateLow(component)(lane).asUInt <<
          config.torusShift)(config.torusWidth - 1, 0)
        val highTorus = (io.updateHigh(component)(lane).asUInt <<
          config.torusShift)(config.torusWidth - 1, 0)
        memory(component)(lane)(updateLowDepth) :=
          memory(component)(lane)(updateLowDepth) + lowTorus
        memory(component)(lane)(updateHighDepth) :=
          memory(component)(lane)(updateHighDepth) + highTorus
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

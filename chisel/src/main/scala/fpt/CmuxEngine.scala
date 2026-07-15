package fpt

import chisel3._
import chisel3.util._

final case class CmuxEngineConfig(
    coefficient: CmuxCoefficientConfig,
    forwardTransform: TransformConfig,
    inverseTransform: TransformConfig,
    externalProduct: ExternalProductConfig,
    inverseNormalizeShift: Int,
    forwardSGen: Option[SGenBackendConfig] = None,
    inverseSGen: Option[SGenBackendConfig] = None
) {
  require(coefficient.points == forwardTransform.points)
  require(coefficient.points == inverseTransform.points)
  require(coefficient.points == externalProduct.points)
  require(coefficient.forwardLanes == forwardTransform.lanes)
  require(coefficient.inverseLanes == inverseTransform.lanes)
  require(coefficient.forwardLanes == externalProduct.inputLanes)
  require(coefficient.inverseLanes == externalProduct.outputLanes)
  require(coefficient.components == externalProduct.outputComponents)
  require(coefficient.components * coefficient.levels == externalProduct.rows)
  require(coefficient.forwardFormat.width == forwardTransform.dataWidth)
  require(coefficient.inverseFormat.width == inverseTransform.dataWidth)
  require(coefficient.forwardFormat == externalProduct.spectrum)
  require(coefficient.inverseFormat == externalProduct.accumulator)
  require(inverseNormalizeShift >= 0)
}

/** A complete, sequentially scheduled fixed-point CMUX reference engine.
  *
  * One forward tangent FFT is reused across all decomposition rows. The two
  * (or more) output components have parallel inverse tangent FFTs so the
  * External Product's asymmetric output stream can be consumed directly.
  * The continuous-flow SGen transforms can replace these native Chisel
  * transforms without changing the coefficient or key interfaces.
  */
final class CmuxEngine(val config: CmuxEngineConfig) extends Module {
  private val coefficientConfig = config.coefficient
  private val externalConfig = config.externalProduct
  private val componentWidth = TransformUtil.counterWidth(
    coefficientConfig.components
  )
  private val levelWidth = TransformUtil.counterWidth(coefficientConfig.levels)
  private val rowWidth = TransformUtil.counterWidth(externalConfig.rows)

  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val loadValid = Input(Bool())
    val loadReady = Output(Bool())
    val load = Input(
      Vec(
        coefficientConfig.components,
        Vec(
          coefficientConfig.inverseLanes,
          UInt(coefficientConfig.torusWidth.W)
        )
      )
    )
    val loadDone = Output(Bool())

    val commandValid = Input(Bool())
    val commandReady = Output(Bool())
    val exponent = Input(UInt(coefficientConfig.exponentWidth.W))

    val bootstrappingKey = Input(
      Vec(
        externalConfig.outputComponents,
        Vec(
          externalConfig.inputLanes,
          new ComplexSInt(externalConfig.bootstrappingKey.width)
        )
      )
    )
    val keyRow = Output(UInt(rowWidth.W))
    val keyPoint = Output(
      Vec(
        externalConfig.inputLanes,
        UInt(log2Ceil(externalConfig.points).W)
      )
    )

    val forwardTwistIndex = Output(
      Vec(config.forwardTransform.lanes, UInt(config.forwardTransform.logPoints.W))
    )
    val forwardTwist = Input(
      Vec(
        config.forwardTransform.lanes,
        new GaussTwiddle(config.forwardTransform.twiddleWidth)
      )
    )
    val forwardFftTwiddleIndex = Output(
      Vec(
        config.forwardTransform.lanes,
        UInt(config.forwardTransform.twiddleIndexWidth.W)
      )
    )
    val forwardFftTwiddle = Input(
      Vec(
        config.forwardTransform.lanes,
        new GaussTwiddle(config.forwardTransform.twiddleWidth)
      )
    )

    val inverseFftTwiddleIndex = Output(
      Vec(
        config.inverseTransform.lanes,
        UInt(config.inverseTransform.twiddleIndexWidth.W)
      )
    )
    val inverseFftTwiddle = Input(
      Vec(
        config.inverseTransform.lanes,
        new GaussTwiddle(config.inverseTransform.twiddleWidth)
      )
    )
    val inverseUntwistIndex = Output(
      Vec(config.inverseTransform.lanes, UInt(config.inverseTransform.logPoints.W))
    )
    val inverseUntwist = Input(
      Vec(
        config.inverseTransform.lanes,
        new GaussTwiddle(config.inverseTransform.twiddleWidth)
      )
    )

    val drainStart = Input(Bool())
    val drainValid = Output(Bool())
    val drainReady = Input(Bool())
    val drain = Output(
      Vec(
        coefficientConfig.components,
        Vec(
          coefficientConfig.inverseLanes,
          UInt(coefficientConfig.torusWidth.W)
        )
      )
    )
    val drainDone = Output(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
  })

  val coefficientStore = Module(new CmuxCoefficientStore(coefficientConfig))
  val forward: ForwardTangentBackend = config.forwardSGen match {
    case Some(generated) =>
      Module(
        new SGenForwardTangentBackend(config.forwardTransform, generated)
      )
    case None => Module(new NativeForwardTangentBackend(config.forwardTransform))
  }
  val external = Module(new ExternalProductAccumulator(externalConfig))
  val inverses: Seq[InverseTangentBackend] = Seq.fill(
    coefficientConfig.components
  ) {
    config.inverseSGen match {
      case Some(generated) =>
        Module(
          new SGenInverseTangentBackend(
            config.inverseTransform,
            config.inverseNormalizeShift,
            generated
          )
        )
      case None =>
        Module(
          new NativeInverseTangentBackend(
            config.inverseTransform,
            config.inverseNormalizeShift
          )
        )
    }
  }

  coefficientStore.io.loadStart := io.loadStart
  coefficientStore.io.loadValid := io.loadValid
  coefficientStore.io.load := io.load
  io.loadReady := coefficientStore.io.loadReady
  io.loadDone := coefficientStore.io.loadDone
  coefficientStore.io.drainStart := io.drainStart
  coefficientStore.io.drainReady := io.drainReady
  io.drainValid := coefficientStore.io.drainValid
  io.drain := coefficientStore.io.drain
  io.drainDone := coefficientStore.io.drainDone

  val idle :: processingRows :: processingInverse :: Nil = Enum(3)
  val state = RegInit(idle)
  val exponentReg = RegInit(0.U(coefficientConfig.exponentWidth.W))
  val row = RegInit(0.U(rowWidth.W))
  val commandFire = io.commandValid && io.commandReady
  val finalRow = row === (externalConfig.rows - 1).U
  val forwardRowBoundary = if (config.forwardSGen.isDefined) {
    coefficientStore.io.pairLast
  } else {
    forward.io.done
  }
  val launchFollowingRow =
    state === processingRows && forwardRowBoundary && !finalRow
  val launchFinalInverse =
    state === processingRows && external.io.outputValid
  val followingRow = Mux(finalRow, 0.U, row + 1.U)
  val launchRowIndex = Mux(commandFire, 0.U, followingRow)
  val rowComponents = VecInit(
    (0 until externalConfig.rows).map(index =>
      (index / coefficientConfig.levels).U(componentWidth.W)
    )
  )
  val rowLevels = VecInit(
    (0 until externalConfig.rows).map(index =>
      (index % coefficientConfig.levels).U(levelWidth.W)
    )
  )

  io.commandReady :=
    state === idle && coefficientStore.io.loaded && coefficientStore.io.idle
  io.busy := state =/= idle || !coefficientStore.io.idle
  io.done := coefficientStore.io.updateDone

  coefficientStore.io.rowStart := commandFire || launchFollowingRow
  coefficientStore.io.rowComponent := rowComponents(launchRowIndex)
  coefficientStore.io.rowLevel := rowLevels(launchRowIndex)
  coefficientStore.io.exponent := Mux(commandFire, io.exponent, exponentReg)
  coefficientStore.io.pairReady := forward.io.pairReady
  forward.io.start := commandFire || launchFollowingRow
  forward.io.pairValid := coefficientStore.io.pairValid
  forward.io.coefficientLow := coefficientStore.io.coefficientLow
  forward.io.coefficientHigh := coefficientStore.io.coefficientHigh
  forward.io.twist := io.forwardTwist
  forward.io.fftTwiddle := io.forwardFftTwiddle
  io.forwardTwistIndex := forward.io.twistIndex
  io.forwardFftTwiddleIndex := forward.io.fftTwiddleIndex

  external.io.start := commandFire
  external.io.inputValid := forward.io.outputValid
  external.io.decomposition := forward.io.output
  external.io.bootstrappingKey := io.bootstrappingKey
  forward.io.outputReady := external.io.inputReady
  io.keyRow := external.io.keyRow
  io.keyPoint := external.io.pointIndex

  val allInverseInputReady = inverses.map(_.io.inputReady).reduce(_ && _)
  external.io.outputReady := allInverseInputReady
  for ((inverse, component) <- inverses.zipWithIndex) {
    inverse.io.start := launchFinalInverse
    inverse.io.inputValid := external.io.outputValid && allInverseInputReady
    inverse.io.input := external.io.output(component)
    inverse.io.fftTwiddle := io.inverseFftTwiddle
    inverse.io.untwist := io.inverseUntwist
  }
  io.inverseFftTwiddleIndex := inverses.head.io.fftTwiddleIndex
  io.inverseUntwistIndex := inverses.head.io.untwistIndex

  coefficientStore.io.updateStart := launchFinalInverse
  val allInverseOutputValid = inverses.map(_.io.outputValid).reduce(_ && _)
  coefficientStore.io.updateValid := allInverseOutputValid
  for ((inverse, component) <- inverses.zipWithIndex) {
    inverse.io.outputReady :=
      coefficientStore.io.updateReady && allInverseOutputValid
    coefficientStore.io.updateLow(component) := inverse.io.coefficientLow
    coefficientStore.io.updateHigh(component) := inverse.io.coefficientHigh
  }

  when(commandFire) {
    exponentReg := io.exponent
    row := 0.U
    state := processingRows
  }
  when(launchFollowingRow) {
    row := row + 1.U
  }
  when(launchFinalInverse) {
    state := processingInverse
  }
  when(state === processingInverse && coefficientStore.io.updateDone) {
    state := idle
  }
}

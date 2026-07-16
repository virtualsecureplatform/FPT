package fpt

import chisel3._

/** Host-facing buffered Blind Rotate boundary.
  *
  * The generated FPT SGen transforms integrate the tangent twist and untwist,
  * so the wide unused twiddle ports and transform/key diagnostic buses of the
  * verification engine are deliberately absent here. The remaining ports are
  * the state-loading, key-loading, run-control, and extracted-result streams
  * needed by an accelerator shell.
  */
final class BufferedBlindRotateAccelerator(
    val config: BufferedBlindRotateConfig
) extends Module {
  private val blindConfig = config.blindRotate
  private val base = blindConfig.cmux.engine
  private val coefficient = base.coefficient
  private val external = base.externalProduct
  private val contextWidth = blindConfig.contextWidth

  require(
    base.forwardSGen.exists(_.integratedTangent) &&
      base.inverseSGen.exists(_.integratedTangent),
    "the accelerator boundary requires integrated tangent transforms"
  )

  val io = IO(new Bundle {
    val inputStart = Input(Bool())
    val inputStartReady = Output(Bool())
    val inputContext = Input(UInt(contextWidth.W))
    val testVector = Input(UInt(coefficient.torusWidth.W))
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val inputCoefficient = Input(UInt(blindConfig.inputTorusWidth.W))
    val inputDone = Output(Bool())
    val inputDoneContext = Output(UInt(contextWidth.W))

    val keyLoadStart = Input(Bool())
    val keyLoadStartReady = Output(Bool())
    val keyLoadIndex = Input(UInt(blindConfig.dimensionWidth.W))
    val keyLoadValid = Input(Bool())
    val keyLoadReady = Output(Bool())
    val keyLoad = Input(
      Vec(
        config.keyBuffer.loadLanes,
        new ComplexSInt(external.bootstrappingKey.width)
      )
    )
    val keyLoadDone = Output(Bool())
    val keyLoadDoneIndex = Output(UInt(blindConfig.dimensionWidth.W))

    val runStart = Input(Bool())
    val runReady = Output(Bool())
    val active = Output(Bool())
    val computeDone = Output(Bool())

    val resultValid = Output(Bool())
    val resultReady = Input(Bool())
    val result = Output(UInt(coefficient.torusWidth.W))
    val resultContext = Output(UInt(contextWidth.W))
    val resultLast = Output(Bool())
    val done = Output(Bool())
  })

  val engine = Module(
    new BufferedBatchedBlindRotateSampleExtractEngine(config)
  )

  engine.io.inputStart := io.inputStart
  io.inputStartReady := engine.io.inputStartReady
  engine.io.inputContext := io.inputContext
  engine.io.testVector := io.testVector
  engine.io.inputValid := io.inputValid
  io.inputReady := engine.io.inputReady
  engine.io.inputCoefficient := io.inputCoefficient
  io.inputDone := engine.io.inputDone
  io.inputDoneContext := engine.io.inputDoneContext

  engine.io.keyLoadStart := io.keyLoadStart
  io.keyLoadStartReady := engine.io.keyLoadStartReady
  engine.io.keyLoadIndex := io.keyLoadIndex
  engine.io.keyLoadValid := io.keyLoadValid
  io.keyLoadReady := engine.io.keyLoadReady
  engine.io.keyLoad := io.keyLoad
  io.keyLoadDone := engine.io.keyLoadDone
  io.keyLoadDoneIndex := engine.io.keyLoadDoneIndex

  engine.io.runStart := io.runStart
  io.runReady := engine.io.runReady
  io.active := engine.io.active
  io.computeDone := engine.io.computeDone

  // These buses are intentionally absent at the physical boundary. The SGen
  // backends ignore their values when the tangent operations are integrated.
  engine.io.forwardTwist := 0.U.asTypeOf(engine.io.forwardTwist)
  engine.io.inverseUntwist := 0.U.asTypeOf(engine.io.inverseUntwist)

  io.resultValid := engine.io.resultValid
  engine.io.resultReady := io.resultReady
  io.result := engine.io.result
  io.resultContext := engine.io.resultContext
  io.resultLast := engine.io.resultLast
  io.done := engine.io.done
}

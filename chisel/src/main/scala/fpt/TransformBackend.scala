package fpt

import chisel3._

final case class SGenBackendConfig(
    moduleName: String,
    verilogPath: String,
    inputLeadCycles: Int = 1
) {
  require(moduleName.nonEmpty)
  require(verilogPath.nonEmpty)
  require(inputLeadCycles >= 1)
}

final class ForwardTangentBackendIO(val config: TransformConfig)
    extends Bundle {
  val start = Input(Bool())
  val pairValid = Input(Bool())
  val pairReady = Output(Bool())
  val coefficientLow = Input(Vec(config.lanes, SInt(config.dataWidth.W)))
  val coefficientHigh = Input(Vec(config.lanes, SInt(config.dataWidth.W)))
  val twistIndex = Output(Vec(config.lanes, UInt(config.logPoints.W)))
  val twist = Input(Vec(config.lanes, new GaussTwiddle(config.twiddleWidth)))
  val fftTwiddleIndex = Output(
    Vec(config.lanes, UInt(config.twiddleIndexWidth.W))
  )
  val fftTwiddle = Input(
    Vec(config.lanes, new GaussTwiddle(config.twiddleWidth))
  )
  val outputValid = Output(Bool())
  val outputReady = Input(Bool())
  val output = Output(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
  val done = Output(Bool())
}

abstract class ForwardTangentBackend(val transformConfig: TransformConfig)
    extends Module {
  val io = IO(new ForwardTangentBackendIO(transformConfig))
}

final class NativeForwardTangentBackend(config: TransformConfig)
    extends ForwardTangentBackend(config) {
  val core = Module(new TangentFftCore(config))
  core.io.pairValid := io.pairValid
  core.io.coefficientLow := io.coefficientLow
  core.io.coefficientHigh := io.coefficientHigh
  core.io.twist := io.twist
  core.io.fftTwiddle := io.fftTwiddle
  core.io.outputReady := io.outputReady
  io.pairReady := core.io.pairReady
  io.twistIndex := core.io.twistIndex
  io.fftTwiddleIndex := core.io.fftTwiddleIndex
  io.outputValid := core.io.outputValid
  io.output := core.io.output
  io.done := core.io.done
}

final class SGenForwardTangentBackend(
    config: TransformConfig,
    generated: SGenBackendConfig
) extends ForwardTangentBackend(config) {
  val core = Module(
    new SGenTangentFft(
      config,
      generated.moduleName,
      generated.verilogPath,
      generated.inputLeadCycles
    )
  )
  core.io.start := io.start
  core.io.pairValid := io.pairValid
  core.io.coefficientLow := io.coefficientLow
  core.io.coefficientHigh := io.coefficientHigh
  core.io.twist := io.twist
  core.io.outputReady := io.outputReady
  io.pairReady := core.io.pairReady
  io.twistIndex := core.io.twistIndex
  io.fftTwiddleIndex := 0.U.asTypeOf(io.fftTwiddleIndex)
  io.outputValid := core.io.outputValid
  io.output := core.io.output
  io.done := core.io.done
}

final class InverseTangentBackendIO(
    val config: TransformConfig
) extends Bundle {
  val start = Input(Bool())
  val inputValid = Input(Bool())
  val inputReady = Output(Bool())
  val input = Input(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
  val fftTwiddleIndex = Output(
    Vec(config.lanes, UInt(config.twiddleIndexWidth.W))
  )
  val fftTwiddle = Input(
    Vec(config.lanes, new GaussTwiddle(config.twiddleWidth))
  )
  val untwistIndex = Output(Vec(config.lanes, UInt(config.logPoints.W)))
  val untwist = Input(Vec(config.lanes, new GaussTwiddle(config.twiddleWidth)))
  val outputValid = Output(Bool())
  val outputReady = Input(Bool())
  val coefficientLow = Output(Vec(config.lanes, SInt(config.dataWidth.W)))
  val coefficientHigh = Output(Vec(config.lanes, SInt(config.dataWidth.W)))
  val done = Output(Bool())
}

abstract class InverseTangentBackend(
    val transformConfig: TransformConfig,
    val normalizeShift: Int
) extends Module {
  val io = IO(new InverseTangentBackendIO(transformConfig))
}

final class NativeInverseTangentBackend(
    config: TransformConfig,
    normalization: Int
) extends InverseTangentBackend(config, normalization) {
  val core = Module(new TangentIfftCore(config, normalization))
  core.io.inputValid := io.inputValid
  core.io.input := io.input
  core.io.fftTwiddle := io.fftTwiddle
  core.io.untwist := io.untwist
  core.io.outputReady := io.outputReady
  io.inputReady := core.io.inputReady
  io.fftTwiddleIndex := core.io.fftTwiddleIndex
  io.untwistIndex := core.io.untwistIndex
  io.outputValid := core.io.outputValid
  io.coefficientLow := core.io.coefficientLow
  io.coefficientHigh := core.io.coefficientHigh
  io.done := core.io.done
}

final class SGenInverseTangentBackend(
    config: TransformConfig,
    normalization: Int,
    generated: SGenBackendConfig
) extends InverseTangentBackend(config, normalization) {
  val core = Module(
    new SGenTangentIfft(
      config,
      normalization,
      generated.moduleName,
      generated.verilogPath,
      generated.inputLeadCycles
    )
  )
  core.io.start := io.start
  core.io.inputValid := io.inputValid
  core.io.input := io.input
  core.io.untwist := io.untwist
  core.io.outputReady := io.outputReady
  io.inputReady := core.io.inputReady
  io.fftTwiddleIndex := 0.U.asTypeOf(io.fftTwiddleIndex)
  io.untwistIndex := core.io.untwistIndex
  io.outputValid := core.io.outputValid
  io.coefficientLow := core.io.coefficientLow
  io.coefficientHigh := core.io.coefficientHigh
  io.done := core.io.done
}

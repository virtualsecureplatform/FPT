package fpt

import chisel3._
import chisel3.util._

/** Test-only scalar-lane view of wide results. ChiselTest 6's Verilator
  * harness cannot access current Verilator VlWide ports directly; splitting
  * this port avoids changing either the production interface or toolchain.
  */
final class NumericalBufferedBlindRotateHarness(config: BufferedBlindRotateConfig) extends Module {
  private val core = Module(new BufferedBlindRotateAccelerator(config))
  private val blind = config.blindRotate
  private val width = blind.cmux.engine.coefficient.torusWidth
  val io = IO(new Bundle {
    val inputStart = Input(Bool())
    val inputStartReady = Output(Bool())
    val inputContext = Input(UInt(blind.contextWidth.W))
    val testVector = Input(UInt(width.W))
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val inputCoefficient = Input(UInt(blind.inputTorusWidth.W))
    val inputDone = Output(Bool())
    val inputDoneContext = Output(UInt(blind.contextWidth.W))
    val keyLoadStart = Input(Bool())
    val keyLoadStartReady = Output(Bool())
    val keyLoadIndex = Input(UInt(blind.dimensionWidth.W))
    val keyLoadValid = Input(Bool())
    val keyLoadReady = Output(Bool())
    val keyLoad = Input(Vec(config.keyBuffer.loadLanes,
      new ComplexSInt(blind.cmux.engine.externalProduct.bootstrappingKey.width)))
    val keyLoadDone = Output(Bool())
    val keyLoadDoneIndex = Output(UInt(blind.dimensionWidth.W))
    val runStart = Input(Bool())
    val runReady = Output(Bool())
    val active = Output(Bool())
    val computeDone = Output(Bool())
    val resultValid = Output(Bool())
    val resultReady = Input(Bool())
    val result = Output(Vec(blind.sampleExtractLanes, UInt(width.W)))
    val resultCount = Output(UInt(log2Ceil(blind.sampleExtractLanes + 1).W))
    val resultContext = Output(UInt(blind.contextWidth.W))
    val resultLast = Output(Bool())
    val done = Output(Bool())
  })
  for ((name, port) <- core.io.elements if name != "result") {
    port <> io.elements(name)
  }
  io.result := core.io.result.asTypeOf(io.result)
}

package fpt

import chisel3._
import chisel3.util._

final class WideOutputResourceHarness(lanes: Int) extends Module {
  val io = IO(new Bundle {
    val inputStart = Input(Bool())
    val inputStartReady = Output(Bool())
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val inputA = Input(Vec(64, UInt(32.W)))
    val inputB = Input(Vec(64, UInt(32.W)))
    val output = Decoupled(UInt((32 * lanes).W))
    val outputKeep = Output(UInt((4 * lanes).W))
    val outputLast = Output(Bool())
  })
  val extract = Module(new SampleExtractIndexZero(SampleExtractConfig(1024, 64, 32, lanes)))
  extract.io.inputStart := io.inputStart
  extract.io.inputValid := io.inputValid
  extract.io.inputA := io.inputA
  extract.io.inputB := io.inputB
  io.inputStartReady := extract.io.inputStartReady
  io.inputReady := extract.io.inputReady
  if (lanes == 1) {
    io.output.valid := extract.io.outputValid
    io.output.bits := extract.io.output
    io.outputLast := extract.io.outputLast
    io.outputKeep := 15.U
    extract.io.outputReady := io.output.ready
  } else {
    val packer = Module(new ResultBeatCompactor(lanes))
    packer.io.input.valid := extract.io.outputValid
    packer.io.input.bits.data := extract.io.output
    packer.io.input.bits.count := extract.io.outputCount
    packer.io.input.bits.last := extract.io.outputLast
    extract.io.outputReady := packer.io.input.ready
    io.output.valid := packer.io.output.valid
    io.output.bits := packer.io.output.bits.data
    io.outputLast := packer.io.output.bits.last
    io.outputKeep := packer.io.output.bits.keep
    packer.io.output.ready := io.output.ready
  }
}

object EmitWideOutputResourceHarness extends App {
  require(args.length == 2, "OUTPUT_DIR LANES")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new WideOutputResourceHarness(args(1).toInt),
    args = Array("--target-dir", args(0)),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
}

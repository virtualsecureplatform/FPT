package fpt

import chisel3._

class EpSubtileHarness extends Module {
  val io = IO(new Bundle {
    val pendingWrite = Input(Bool())
    val writeAddress = Input(UInt(2.W))
    val nextReadEnable = Input(Bool())
    val nextReadAddress = Input(UInt(2.W))
    val data = Input(UInt(600.W))
    val output = Output(UInt(600.W))
  })
  val wide = Module(new FinalAccumulatorLocalBank(360, false, 180))
  val tail = Module(new FinalAccumulatorLocalBank(240, false, 180))
  for (bank <- Seq(wide, tail)) {
    bank.io.clock := clock; bank.io.reset := reset.asBool
    bank.io.pendingWrite := io.pendingWrite; bank.io.pendingWriteAddress := io.writeAddress
    bank.io.nextReadEnable := io.nextReadEnable; bank.io.nextReadAddress := io.nextReadAddress
  }
  wide.io.inputData := io.data(359, 0); tail.io.inputData := io.data(599, 360)
  io.output := chisel3.util.Cat(tail.io.outputData, wide.io.outputData)
}

object EmitEpSubtiles extends App {
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new EpSubtileHarness,
    Array("--target-dir", args(0)), SynthesisEmitter.firtoolOptions)
  SynthesisEmitter.removeInlineFileList(java.nio.file.Path.of(args(0)).resolve("EpSubtileHarness.sv"))
}

import chisel3._

import java.nio.file.Path

/** Standalone wrappers around the exact arithmetic cores used by HOGE's
  * current HomGate design. This file is compiled in a staged copy of HOGE so
  * the reference checkout remains unmodified.
  */
final class HOGEForwardINTTBaseline(implicit val conf: Config) extends Module {
  val io = IO(new Bundle {
    val inputValid = Input(Bool())
    val input = Input(Vec(conf.chunk, Vec(conf.radix, UInt(64.W))))
    val outputValid = Output(Bool())
    val output = Output(Vec(conf.chunk, Vec(conf.radix, UInt(64.W))))
  })

  val core = Module(new INTT)
  core.io.validin := io.inputValid
  core.io.in := io.input
  io.outputValid := core.io.validout
  io.output := core.io.out
}

final class HOGEInverseNTTBaseline(implicit val conf: Config) extends Module {
  val io = IO(new Bundle {
    val inputValid = Input(Bool())
    val input = Input(Vec(conf.chunk, Vec(conf.radix, UInt(64.W))))
    val outputValid = Output(Bool())
    val output = Output(Vec(conf.chunk, Vec(conf.radix, UInt(64.W))))
  })

  val core = Module(new NTT)
  core.io.validin := io.inputValid
  core.io.in := io.input
  io.outputValid := core.io.validout
  io.output := core.io.out
}

/** HOGE's Blind Rotate boundary without IKS or Vitis DataMover IP.
  *
  * This retains the current two-context BR memory, polynomial rotation,
  * decomposition, INTT, modular external product, NTT, feedback, and sample
  * extraction. TLWE input, bootstrapping-key streams, and result remain
  * external, matching the boundary of FPT's out-of-context Blind Rotate top.
  */
final class HOGEBlindRotateBaseline(implicit val conf: Config) extends Module {
  val io = IO(new Bundle {
    val tlwe = new AXI4StreamSubordinate(conf.buswidth)
    val bootstrappingKey = Vec(
      conf.bknumbus,
      new AXI4StreamSubordinate(conf.buswidth)
    )
    val result = new AXI4StreamManager(conf.Qbit, withTLast = true)
  })

  val former = Module(new AXISBRFormer)
  val later = Module(new AXISBRLater)

  former.io.axi4sout <> later.io.axi4sin
  later.io.axi4sout <> former.io.axi4sin

  former.io.axi4sglobalin.TVALID := io.tlwe.TVALID
  former.io.axi4sglobalin.TDATA := io.tlwe.TDATA
  io.tlwe.TREADY := former.io.axi4sglobalin.TREADY

  io.result.TVALID := former.io.axi4sglobalout.TVALID
  io.result.TDATA := former.io.axi4sglobalout.TDATA
  io.result.TLAST.get := former.io.axi4sglobalout.TLAST.get
  former.io.axi4sglobalout.TREADY := io.result.TREADY

  for (index <- 0 until conf.bknumbus) {
    later.io.axi4bkin(index).TVALID := io.bootstrappingKey(index).TVALID
    later.io.axi4bkin(index).TDATA := io.bootstrappingKey(index).TDATA
    io.bootstrappingKey(index).TREADY :=
      later.io.axi4bkin(index).TREADY
  }
}

object HOGEBaselineEmit extends App {
  require(args.length == 1, "usage: HOGEBaselineEmit OUTPUT_DIR")
  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize.toString
  implicit val conf: Config = Config()
  val stage = new chisel3.stage.ChiselStage
  val stageArgs = Array("--target-dir", outputDirectory)

  stage.emitVerilog(new HOGEForwardINTTBaseline, stageArgs)
  stage.emitVerilog(new HOGEInverseNTTBaseline, stageArgs)
  stage.emitVerilog(new HOGEBlindRotateBaseline, stageArgs)
}

package fpt

import chisel3._
import chisel3.util._

/** Full-width synthesis fixture: preserve the production hierarchy names. */
class FixedInverseLinkHarness extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val outputStart = Output(Bool())
    val enq = Flipped(Decoupled(new BatchedCmuxInverseBeat(4, 64, 30)))
    val deq = Decoupled(new BatchedCmuxInverseBeat(4, 64, 30))
  })
  val externalOutputPipeline = Module(new BatchedCmuxExternalOutputPipeline(4, 64, 30, true))
  val inverseBoundary = Module(new BatchedCmuxInverseBoundary(4, 64, 30, true))
  externalOutputPipeline.io.start := io.start
  externalOutputPipeline.io.enq.valid := io.enq.valid
  externalOutputPipeline.io.enq.bits := io.enq.bits.asTypeOf(externalOutputPipeline.io.enq.bits)
  io.enq.ready := externalOutputPipeline.io.enq.ready
  inverseBoundary.io.start := externalOutputPipeline.io.outputStart
  inverseBoundary.io.enq.valid := externalOutputPipeline.io.deq.valid
  inverseBoundary.io.enq.bits := externalOutputPipeline.io.deq.bits.asTypeOf(inverseBoundary.io.enq.bits)
  externalOutputPipeline.io.deq.ready := inverseBoundary.io.enq.ready
  io.deq <> inverseBoundary.io.deq
  io.outputStart := inverseBoundary.io.outputStart
}

object EmitFixedInverseLink extends App {
  require(args.length == 1)
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new FixedInverseLinkHarness,
    Array("--target-dir", args(0)), SynthesisEmitter.firtoolOptions)
  SynthesisEmitter.removeInlineFileList(java.nio.file.Path.of(args(0)).resolve("FixedInverseLinkHarness.sv"))
}

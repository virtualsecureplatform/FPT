package fpt

import chisel3._
import chisel3.util._

/** The sole raw output-status sampling boundary in buffered mode. */
final class BufferedDmaEvent extends Module {
  val io = IO(new Bundle {
    val status = Flipped(Valid(UInt(8.W)))
    val error = Input(Bool())
    val sampled = Valid(UInt(8.W))
    val sampledError = Output(Bool())
  })
  val payload = Module(new PhysicalCutRegister(8))
  payload.io.clock := clock; payload.io.enable := true.B
  payload.io.inputData := io.status.bits
  val token = Module(new PhysicalControlRegister(2))
  token.io.clock := clock; token.io.reset := reset.asBool
  token.io.inputData := Cat(io.error, io.status.valid)
  io.sampled.bits := payload.io.outputData
  io.sampled.valid := token.io.outputData(0)
  io.sampledError := token.io.outputData(1)
}

final class BufferedCompletion extends Bundle {
  val id = Bool()
  val result = new KernelCompletion
  val stages = Vec(5, UInt(32.W))
}

private[fpt] final class BufferedCompletionBoundary extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Valid(new BufferedCompletion))
    val output = Valid(new BufferedCompletion)
  })
  val payload = Module(new PhysicalCutRegister(321))
  payload.io.clock := clock; payload.io.enable := true.B
  payload.io.inputData := io.input.bits.asUInt
  val token = Module(new PhysicalControlRegister(1))
  token.io.clock := clock; token.io.reset := reset.asBool
  token.io.inputData := io.input.valid
  io.output.bits := payload.io.outputData.asTypeOf(new BufferedCompletion)
  io.output.valid := token.io.outputData(0)
}

/** Two invocation credits reserve downstream capacity; no ready crosses SLRs. */
final class BufferedCompletionLink extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Valid(new BufferedCompletion))
    val output = Valid(new BufferedCompletion)
    val pending = Output(UInt(2.W))
  })
  val sourceTx = Module(new BufferedCompletionBoundary)
  val destinationRx = Module(new BufferedCompletionBoundary)
  sourceTx.io.input := io.input
  destinationRx.io.input := sourceTx.io.output
  io.output := destinationRx.io.output
  io.pending := sourceTx.io.output.valid.asUInt +& destinationRx.io.output.valid.asUInt
}

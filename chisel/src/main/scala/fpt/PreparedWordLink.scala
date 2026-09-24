package fpt

import chisel3._
import chisel3.util._

/** One physical end of the narrow, fixed-rate preparation crossing. */
private[fpt] final class PreparedWordBoundary extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Valid(UInt(32.W)))
    val output = Valid(UInt(32.W))
  })
  val payload = Module(new PhysicalCutRegister(32))
  payload.io.clock := clock
  payload.io.enable := true.B
  payload.io.inputData := io.input.bits
  io.output.bits := payload.io.outputData
  val token = Module(new PhysicalControlRegister(1))
  token.io.clock := clock
  token.io.reset := reset.asBool
  token.io.inputData := io.input.valid.asUInt
  io.output.valid := token.io.outputData(0)
}

/** The bank-start handshake reserves an always-ready destination until the
  * last frame word. No combinational ready or payload-enable crosses the SLR.
  * On failure, the source stops issuing; up to two words may still drain into
  * the reserved inactive bank. Only reset flushes tokens and bank ownership.
  */
final class PreparedWordLink extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(UInt(32.W)))
    val output = Decoupled(UInt(32.W))
  })
  val sourceTx = Module(new PreparedWordBoundary)
  val destinationRx = Module(new PreparedWordBoundary)
  io.input.ready := true.B
  sourceTx.io.input.valid := io.input.fire
  sourceTx.io.input.bits := io.input.bits
  destinationRx.io.input := sourceTx.io.output
  io.output.valid := destinationRx.io.output.valid
  io.output.bits := destinationRx.io.output.bits
  when(io.output.valid) {
    assert(io.output.ready, "prepared word destination must remain ready through the final word")
  }
}

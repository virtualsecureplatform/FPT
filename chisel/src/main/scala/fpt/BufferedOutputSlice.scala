package fpt

import chisel3._
import chisel3.util._

/** Two unreset payload slots. Neither valid/data nor ready bypass storage.
  * A steady occupancy of one sustains one transfer per cycle.
  */
final class BufferedOutputSlice[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(gen))
    val output = Decoupled(gen)
  })
  val slot0 = Reg(UInt(gen.getWidth.W))
  val slot1 = Reg(UInt(gen.getWidth.W))
  dontTouch(slot0); dontTouch(slot1)
  val head = RegInit(false.B)
  val tail = RegInit(false.B)
  val count = RegInit(0.U(2.W))
  io.input.ready := count < 2.U
  io.output.valid := count =/= 0.U
  io.output.bits := Mux(head, slot1, slot0).asTypeOf(gen)
  when(io.input.fire) {
    when(tail) { slot1 := io.input.bits.asUInt }.otherwise { slot0 := io.input.bits.asUInt }
    tail := !tail
  }
  when(io.output.fire) { head := !head }
  when(io.input.fire =/= io.output.fire) {
    count := Mux(io.input.fire, count + 1.U, count - 1.U)
  }
  assert(count <= 2.U, "output slice overflow")
}

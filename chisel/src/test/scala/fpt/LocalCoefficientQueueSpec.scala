package fpt

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

class LocalQueueMiter(width: Int, entries: Int) extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool()); val ready = Input(Bool())
    // Narrow ports avoid the test backend's wide UInt port limitation.
    val data = Input(Vec((width + 31) / 32, UInt(32.W)))
  })
  val ref = Module(new Queue(UInt(width.W), entries, pipe = true, flow = true))
  val dut = Module(new LocalCoefficientQueue(UInt(width.W), entries))
  Seq(ref.io, dut.io).foreach { q =>
    q.enq.valid := io.valid; q.enq.bits := io.data.asUInt(width - 1, 0)
    q.deq.ready := io.ready
  }
  assert(dut.io.enq.ready === ref.io.enq.ready)
  assert(dut.io.deq.valid === ref.io.deq.valid)
  assert(dut.io.count === ref.io.count)
  when(ref.io.deq.valid) { assert(dut.io.deq.bits === ref.io.deq.bits) }
}

class LocalCoefficientQueueSpec extends AnyFlatSpec with ChiselScalatestTester {
  for ((width, entries) <- Seq((1,2), (512,8), (513,2), (7685,8), (4097,2))) {
    it should s"match flow and pipe queue width=$width depth=$entries cycle for cycle" in {
      test(new LocalQueueMiter(width,entries)).withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { d =>
        val random = new scala.util.Random(41 + width)
        def cycle(valid: Boolean, ready: Boolean): Unit = {
          d.io.valid.poke(valid.B); d.io.ready.poke(ready.B)
          d.io.data.foreach(_.poke(BigInt(32,random).U)); d.clock.step()
        }
        d.io.valid.poke(false.B); d.io.ready.poke(false.B)
        d.io.data.foreach(_.poke(0.U))
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        for (_ <- 0 until 5) {
          for (_ <- 0 until entries + 3) cycle(true,false)
          for (_ <- 0 until entries * 3) cycle(true,true)
          for (_ <- 0 until entries + 3) cycle(false,true)
          for (_ <- 0 until 10) cycle(true,true)
          for (_ <- 0 until 200) cycle(random.nextBoolean(),random.nextBoolean())
          d.reset.poke(true.B); cycle(false,false); d.reset.poke(false.B)
        }
      }
    }
  }
}

package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

private class InverseLinkComparison extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val valid = Input(Bool())
    val ready = Input(Bool())
    val first = Input(Bool())
    val tag = Input(UInt(4.W))
    val data = Input(Vec(2, new ComplexSInt(30)))
    val equal = Output(Bool())
  })
  val paths = Seq(false, true).map { fixed =>
    val tx = Module(new BatchedCmuxExternalOutputPipeline(4, 2, 30, fixed))
    val rx = Module(new BatchedCmuxInverseBoundary(4, 2, 30, fixed))
    tx.io.start := io.start
    tx.io.enq.valid := io.valid
    tx.io.enq.bits.first := io.first
    tx.io.enq.bits.tag := io.tag
    tx.io.enq.bits.input := io.data
    rx.io.start := tx.io.outputStart
    rx.io.enq.valid := tx.io.deq.valid
    rx.io.enq.bits := tx.io.deq.bits.asTypeOf(rx.io.enq.bits)
    tx.io.deq.ready := rx.io.enq.ready
    rx.io.deq.ready := io.ready
    rx
  }
  io.equal := paths(0).io.outputStart === paths(1).io.outputStart &&
    paths(0).io.deq.valid === paths(1).io.deq.valid &&
    (!paths(1).io.deq.valid || paths(0).io.deq.bits.asUInt === paths(1).io.deq.bits.asUInt)
}

class FixedRateInverseLinkSpec extends AnyFlatSpec with ChiselScalatestTester {
  "fixed-rate inverse link" should "preserve data, tags and start cycles across frames and resets" in {
    test(new InverseLinkComparison).withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { d =>
      val random = new scala.util.Random(0x5457524c)
      for (epoch <- 0 until 3) {
        d.io.start.poke(false.B); d.io.valid.poke(false.B); d.io.ready.poke(true.B)
        d.io.first.poke(false.B); d.io.tag.poke(0.U)
        d.io.data.foreach { c => c.real.poke(0.S); c.imag.poke(0.S) }
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        for (cycle <- 0 until 179) {
          val phase = cycle % 40
          d.io.ready.poke((phase >= 3 && phase <= 34).B)
          d.io.start.poke(Set(0, 8, 16, 24).contains(phase).B)
          d.io.valid.poke((phase >= 1 && phase <= 32).B)
          d.io.first.poke(Set(1, 17).contains(phase).B)
          d.io.tag.poke(((cycle / 16 + epoch) & 15).U)
          d.io.data.foreach { c =>
            c.real.poke((random.nextInt(1 << 29) - (1 << 28)).S)
            c.imag.poke((random.nextInt(1 << 29) - (1 << 28)).S)
          }
          d.io.equal.expect(true.B)
          d.clock.step()
        }
      }
    }
  }

  it should "reject backpressure on a valid receiver beat" in {
    assertThrows[ChiselAssertionError] {
      test(new BatchedCmuxInverseBoundary(4, 2, 30, true))
        .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { d =>
          d.io.start.poke(false.B); d.io.enq.valid.poke(false.B)
          d.io.deq.ready.poke(false.B)
          d.io.enq.bits.first.poke(false.B); d.io.enq.bits.tag.poke(0.U)
          d.io.enq.bits.input.foreach { c => c.real.poke(0.S); c.imag.poke(0.S) }
          d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
          d.io.enq.valid.poke(true.B); d.clock.step(2)
        }
    }
  }
}

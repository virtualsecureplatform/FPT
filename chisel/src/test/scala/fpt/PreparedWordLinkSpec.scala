package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class PreparedWordLinkSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val backend = Seq(VerilatorBackendAnnotation, PaperVerilator.flags)
  it should "delay words and gaps by two cycles and flush both occupied stages on reset" in {
    test(new PreparedWordLink).withAnnotations(backend) { d =>
      d.io.input.valid.poke(false.B); d.io.input.bits.poke(0.U)
      d.io.output.ready.poke(true.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val history = scala.collection.mutable.Queue[Option[BigInt]](None, None)
      val random = new scala.util.Random(4271)
      for (cycle <- 0 until 300) {
        val valid = cycle % 17 < 12
        val value = BigInt(32, random)
        d.io.input.valid.poke(valid.B); d.io.input.bits.poke(value.U)
        d.io.input.ready.expect(true.B)
        val expected = history.dequeue()
        d.io.output.valid.expect(expected.isDefined.B)
        expected.foreach(x => d.io.output.bits.expect(x.U))
        history.enqueue(if (valid) Some(value) else None)
        d.clock.step()
      }
      for (occupied <- 1 to 2) {
        d.io.input.valid.poke(true.B); d.clock.step(occupied)
        d.reset.poke(true.B); d.clock.step()
        d.io.input.valid.poke(false.B); d.reset.poke(false.B)
        for (_ <- 0 until 3) { d.io.output.valid.expect(false.B); d.clock.step() }
      }
    }
  }
  it should "reject backpressure on a reserved destination" in {
    assertThrows[ChiselAssertionError] {
      test(new PreparedWordLink).withAnnotations(backend) { d =>
        d.io.input.valid.poke(false.B); d.io.input.bits.poke(13.U)
        d.io.output.ready.poke(true.B)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        d.io.input.valid.poke(true.B); d.clock.step(2)
        d.io.output.ready.poke(false.B); d.clock.step()
      }
    }
  }
}

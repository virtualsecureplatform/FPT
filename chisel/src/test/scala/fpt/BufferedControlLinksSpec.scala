package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

final class BufferedControlLinksSpec extends AnyFlatSpec with ChiselScalatestTester {
  private val backend = Seq(VerilatorBackendAnnotation, PaperVerilator.flags)
  it should "sample status and error together and flush events on reset" in {
    test(new BufferedDmaEvent).withAnnotations(backend) { d =>
      d.io.status.valid.poke(false.B); d.io.status.bits.poke(0.U); d.io.error.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      for (i <- 0 until 32) {
        d.io.status.valid.poke((i % 3 != 0).B)
        d.io.status.bits.poke((i + 128).U); d.io.error.poke((i % 5 == 0).B)
        d.clock.step()
        d.io.sampled.valid.expect((i % 3 != 0).B)
        d.io.sampled.bits.expect((i + 128).U); d.io.sampledError.expect((i % 5 == 0).B)
      }
      d.reset.poke(true.B); d.clock.step()
      d.io.sampled.valid.expect(false.B); d.io.sampledError.expect(false.B)
    }
  }
  it should "transport consecutive completions without mixing snapshots and flush pending tokens" in {
    test(new BufferedCompletionLink).withAnnotations(backend) { d =>
      def input(i: Int, valid: Boolean): Unit = {
        d.io.input.valid.poke(valid.B); d.io.input.bits.id.poke((i % 2 == 1).B)
        d.io.input.bits.result.status.poke(i.U)
        d.io.input.bits.result.keyBeats.poke((i + 100).U)
        d.io.input.bits.result.keyStarved.poke((i + 200).U)
        d.io.input.bits.result.keyBlocked.poke((i + 300).U)
        d.io.input.bits.result.runCycles.poke((i + 400).U)
        d.io.input.bits.stages.zipWithIndex.foreach { case (p, n) => p.poke((i + 500 + n).U) }
      }
      input(0, false); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val history = scala.collection.mutable.Queue[Option[Int]](None, None)
      for (i <- 0 until 40) {
        val valid = i % 4 < 2 // includes adjacent ordered error completions
        input(i, valid)
        val expected = history.dequeue()
        d.io.output.valid.expect(expected.isDefined.B)
        expected.foreach { n =>
          d.io.output.bits.id.expect((n % 2 == 1).B)
          d.io.output.bits.result.status.expect(n.U)
          d.io.output.bits.result.keyBeats.expect((n + 100).U)
          d.io.output.bits.result.keyStarved.expect((n + 200).U)
          d.io.output.bits.result.keyBlocked.expect((n + 300).U)
          d.io.output.bits.result.runCycles.expect((n + 400).U)
          d.io.output.bits.stages.zipWithIndex.foreach { case (p, j) => p.expect((n + 500 + j).U) }
        }
        history.enqueue(if (valid) Some(i) else None); d.clock.step()
      }
      input(99, true); d.clock.step(2); d.io.pending.expect(2.U)
      d.reset.poke(true.B); d.clock.step(); input(0, false); d.reset.poke(false.B)
      for (_ <- 0 until 3) { d.io.output.valid.expect(false.B); d.io.pending.expect(0.U); d.clock.step() }
    }
  }
}

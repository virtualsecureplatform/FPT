package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BufferedOutputSliceSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  for (width <- Seq(132, 145)) {
    it should s"cut both directions and preserve every stalled payload at width $width" in {
      test(new BufferedOutputSlice(UInt(width.W))) { d =>
        val q = scala.collection.mutable.Queue.empty[BigInt]
        val random = new scala.util.Random(width)
        def reset(): Unit = {
          d.io.input.valid.poke(false.B); d.io.output.ready.poke(false.B)
          d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); q.clear()
        }
        def cycle(valid: Boolean, ready: Boolean): Unit = {
          val value = BigInt(width, random)
          d.io.input.valid.poke(valid.B); d.io.input.bits.poke(value.U)
          d.io.output.ready.poke(ready.B)
          d.io.input.ready.expect((q.size < 2).B)
          d.io.output.valid.expect(q.nonEmpty.B)
          if (q.nonEmpty) d.io.output.bits.expect(q.front.U)
          val push = valid && q.size < 2
          val pop = ready && q.nonEmpty
          if (pop) q.dequeue()
          if (push) q.enqueue(value)
          d.clock.step()
        }
        d.io.input.bits.poke(0.U); reset()
        cycle(true, true) // Empty storage must not flow through.
        for (_ <- 0 until 100) cycle(true, true) // One beat per cycle.
        for (_ <- 0 until 5) cycle(true, false)
        for (_ <- 0 until 20) cycle(false, false)
        for (_ <- 0 until 600) cycle(random.nextBoolean(), random.nextBoolean())
        for (_ <- 0 until 4) cycle(false, true)
        q should be(empty)
        cycle(true, false); cycle(true, false); reset()
        for (_ <- 0 until 4) cycle(false, true)
        cycle(true, true); cycle(false, true)
        q should be(empty)
      }
    }
  }
}

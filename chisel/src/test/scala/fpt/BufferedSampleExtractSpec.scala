package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer

final class BufferedSampleExtractSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  it should "discard partial captures and stalled output on reset without clearing payload RAM" in {
    test(new BufferedSampleExtract(1, 64)) { d =>
      d.io.start.valid.poke(false.B); d.io.start.bits.poke(false.B)
      d.io.contextStart.valid.poke(false.B); d.io.contextStart.bits.poke(0.U)
      d.io.input.valid.poke(false.B); d.io.retire.poke(false.B); d.io.output.ready.poke(false.B)
      d.io.input.bits.a.foreach(_.poke(99.U)); d.io.input.bits.b.foreach(_.poke(71.U))
      for (slices <- Seq(1, 2, 3, 4)) {
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        d.io.output.valid.expect(false.B); d.io.start.ready.expect(true.B)
        d.io.start.valid.poke(true.B); d.clock.step(); d.io.start.valid.poke(false.B)
        d.io.contextStart.valid.poke(true.B); d.clock.step(); d.io.contextStart.valid.poke(false.B)
        d.io.input.valid.poke(true.B); d.clock.step(slices); d.io.input.valid.poke(false.B)
        if (slices == 4) { d.clock.step(10); d.io.output.valid.expect(true.B) }
      }
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.output.valid.expect(false.B); d.io.start.ready.expect(true.B)
    }
  }
  it should "snapshot all masks, survive stalls, and retain ownership until explicit retirement" in {
    test(new BufferedSampleExtract(2, 128)) { d =>
      d.clock.setTimeout(0)
      d.io.start.valid.poke(false.B); d.io.start.bits.poke(false.B)
      d.io.contextStart.valid.poke(false.B); d.io.contextStart.bits.poke(0.U)
      d.io.input.valid.poke(false.B); d.io.retire.poke(false.B)
      d.io.output.ready.poke(false.B)
      d.io.input.bits.a.foreach(_.poke(0.U)); d.io.input.bits.b.foreach(_.poke(0.U))
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val mask = (BigInt(1) << 32) - 1
      for (batch <- 0 until 3) {
        def a(c: Int, i: Int): BigInt = (BigInt("80000000", 16) + batch * 10000 + c * 1000 + i) & mask
        def b(c: Int): BigInt = BigInt(17 + batch * 100 + c)
        d.io.start.ready.expect(true.B); d.io.start.valid.poke(true.B)
        d.clock.step(); d.io.start.valid.poke(false.B)
        for (c <- 0 until 2) {
          d.io.contextStart.ready.expect(true.B)
          d.io.contextStart.bits.poke(c.U); d.io.contextStart.valid.poke(true.B)
          d.clock.step(); d.io.contextStart.valid.poke(false.B)
          for (beat <- 0 until 2) {
            d.io.input.valid.poke(false.B); d.clock.step(2)
            for (i <- 0 until 64) {
              d.io.input.bits.a(i).poke(a(c, beat * 64 + i).U)
              d.io.input.bits.b(i).poke((if (beat == 0 && i == 0) b(c) else BigInt(999)).U)
            }
            d.io.input.valid.poke(true.B)
            for (s <- 0 until 4) {
              d.io.input.ready.expect((s == 3).B)
              d.io.captured.expect((c == 1 && beat == 1 && s == 3).B)
              d.clock.step()
            }
            d.io.input.valid.poke(false.B)
          }
        }
        d.clock.step(20); d.io.start.ready.expect(false.B)
        val actual = ArrayBuffer.empty[BigInt]
        var cycles = 0
        var lastCount = 0
        while (actual.size < 258 && cycles < 1000) {
          val ready = cycles % 7 > 2
          d.io.output.ready.poke(ready.B)
          if (ready && d.io.output.valid.peek().litToBoolean) {
            val count = d.io.output.bits.count.peek().litValue.toInt
            val data = d.io.output.bits.data.peek().litValue
            d.io.output.bits.context.expect((actual.size / 129).U)
            for (i <- 0 until count) actual += (data >> (32 * i)) & mask
            if (d.io.output.bits.last.peek().litToBoolean) { lastCount += 1; actual.size should be(258) }
          }
          d.clock.step(); cycles += 1
        }
        val expected = (0 until 2).flatMap(c => Seq(a(c, 0)) ++ (127 to 1 by -1).map(i => (-a(c, i)) & mask) ++ Seq(b(c)))
        actual.toSeq should be(expected); lastCount should be(1)
        d.io.start.ready.expect(false.B); d.clock.step(5)
        d.io.retire.poke(true.B); d.clock.step(); d.io.retire.poke(false.B)
        d.io.output.ready.poke(false.B)
      }
    }
  }
}

package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class PreparedBatchInputsSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val mask = (BigInt(1) << 32) - 1
  private def switched(x: BigInt): BigInt = ((x + (1 << 20)) & mask) >> 21
  private def reference(words: Seq[BigInt]): (Seq[BigInt], BigInt) = {
    val masks = words.slice(1, words.size - 1)
    val sum = masks.map(x => x - (switched(x) << 21)).sum & mask
    val signed = if (sum.testBit(31)) sum - (BigInt(1) << 32) else sum
    (masks.map(switched), (-switched((words.last - signed / 2) & mask)) & 2047)
  }
  private def exercise(contexts: Int, dimension: Int): Unit = {
    test(new PreparedBatchInputs(contexts, dimension)) { d =>
      d.clock.setTimeout(0)
      d.io.start.valid.poke(false.B); d.io.start.bits.poke(false.B)
      d.io.word.valid.poke(false.B); d.io.word.bits.poke(0.U)
      d.io.activate.valid.poke(false.B); d.io.activate.bits.poke(false.B)
      d.io.release.valid.poke(false.B); d.io.release.bits.poke(false.B)
      d.io.read.valid.poke(false.B); d.io.read.bits.poke(0.U)
      d.io.descriptorRequest.valid.poke(false.B); d.io.descriptorRequest.bits.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val rng = new scala.util.Random(78123)
      val edge = Seq(BigInt(0), BigInt(1), mask, BigInt(1 << 20) - 1,
        BigInt(1 << 20), BigInt(1 << 20) + 1, BigInt("80000001", 16))
      val batches = Seq.tabulate(4) { batch =>
        Seq.tabulate(contexts) { context =>
          Seq(BigInt(batch * 313 + context * 17 + 1)) ++
            Seq.tabulate(dimension) { i =>
              if (batch == 0) edge((i + context) % edge.size)
              else if (batch == 1) mask - i % 3
              else BigInt(32, rng)
            } ++ Seq(BigInt(32, rng))
        }
      }
      def check(batch: Int): Unit = {
        for (c <- 0 until contexts) {
          d.io.descriptorRequest.valid.poke(true.B); d.io.descriptorRequest.bits.poke(c.U)
          d.clock.step(); d.io.descriptorRequest.valid.poke(false.B)
          d.io.descriptor.valid.expect(true.B)
          d.io.descriptor.bits.testVector.expect(batches(batch)(c).head.U)
          d.io.descriptor.bits.exponent.expect(reference(batches(batch)(c))._2.U)
          for (i <- 0 until dimension) {
            d.io.read.valid.poke(true.B); d.io.read.bits.poke((c * dimension + i).U)
            d.clock.step(); d.io.exponent.expect(reference(batches(batch)(c))._1(i).U)
          }
          d.io.read.valid.poke(false.B)
        }
      }
      for (batch <- 0 until 4) {
        val bank = (batch % 2 == 1)
        d.io.start.bits.poke(bank.B); d.io.start.valid.poke(true.B)
        d.io.start.ready.expect(true.B); d.clock.step(); d.io.start.valid.poke(false.B)
        d.io.start.ready.expect(false.B)
        val words = batches(batch).flatten
        for ((word, index) <- words.zipWithIndex) {
          if (index % 19 == 0) { d.io.word.valid.poke(false.B); d.clock.step() }
          // Read the active bank concurrently with writes to the other bank.
          if (batch > 0) {
            d.io.read.valid.poke(true.B); d.io.read.bits.poke(0.U)
          }
          d.io.word.valid.poke(true.B); d.io.word.bits.poke(word.U)
          d.io.word.ready.expect(true.B)
          d.io.prepared.valid.expect((index == words.size - 1).B)
          d.clock.step()
          if (batch > 0) d.io.exponent.expect(reference(batches(batch - 1)(0))._1.head.U)
        }
        d.io.word.valid.poke(false.B); d.io.read.valid.poke(false.B)
        d.io.word.ready.expect(false.B)
        if (batch > 0) {
          check(batch - 1)
          d.io.release.valid.poke(true.B); d.io.release.bits.poke((!bank).B)
          d.clock.step(); d.io.release.valid.poke(false.B)
        }
        d.io.activate.bits.poke(bank.B); d.io.activate.valid.poke(true.B)
        d.io.activate.ready.expect(true.B); d.clock.step(); d.io.activate.valid.poke(false.B)
        check(batch)
      }
      // Reset releases ownership, without relying on reset contents of RAM.
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.start.bits.poke(false.B); d.io.start.ready.expect(true.B)
      d.io.activate.valid.poke(true.B); d.io.activate.ready.expect(false.B)
    }
  }
  it should "match scalar corrected modulus switching through stalls and four bank reuses" in {
    exercise(3, 9)
  }
  it should "prepare full 16-context 630-dimensional batches without corrupting the active bank" in {
    exercise(16, 630)
  }
}

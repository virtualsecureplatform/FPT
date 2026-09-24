package fpt

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

private final class PreparedInputArithmeticMiter(width: Int, exponents: Seq[Int]) extends Module {
  val word = IO(Input(UInt(width.W)))
  val matches = IO(Output(Bool()))
  matches := exponents.map { exponent =>
    val shift = width - exponent
    val rounded = (word +& (BigInt(1) << (shift - 1)).U)(width - 1, 0)
    val switched = rounded(width - 1, shift)
    val reconstructed = (switched.pad(width) << shift)(width - 1, 0)
    val residual = word - reconstructed
    (PreparedInputArithmetic.switched(word, exponent) === switched) &&
      (PreparedInputArithmetic.residual(word, exponent) === residual)
  }.reduce(_ && _)
}

final class PreparedInputArithmeticSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "match full-width arithmetic exhaustively for every reduced-width exponent split" in {
    test(new PreparedInputArithmeticMiter(10, 1 until 10)) { d =>
      for (word <- 0 until 1024) { d.word.poke(word.U); d.matches.expect(true.B) }
    }
  }
  it should "preserve U280 rounding ties and torus wraparound for randomized raw words" in {
    test(new PreparedInputArithmeticMiter(32, Seq(11))) { d =>
      val mask = (BigInt(1) << 32) - 1
      val ties = for (high <- Seq(0, 1, 1023, 1024, 2047); delta <- -2 to 2)
        yield ((BigInt(high) << 21) + (1 << 20) + delta) & mask
      val rng = new scala.util.Random(7319)
      for (word <- Seq(BigInt(0), BigInt(1), mask, mask - 1, BigInt(1) << 31) ++
          ties ++ Seq.fill(10000)(BigInt(32, rng))) {
        d.word.poke(word.U); d.matches.expect(true.B)
      }
    }
  }
}

package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class BitwiseDecomposeSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  behavior of "the bitwise CMUX decomposition frontend"

  it should "match full-width rotation and centered decomposition" in {
    val config = CmuxCoefficientConfig(
      polynomialSize = 16,
      forwardLanes = 4,
      inverseLanes = 4,
      components = 1,
      levels = 2,
      baseBits = 3,
      torusWidth = 8,
      forwardFormat = FixedFormat(5, 3),
      inverseFormat = FixedFormat(5, 3)
    )
    val bitsPerCycle = 1
    val modulus = BigInt(1) << config.torusWidth
    val mask = modulus - 1
    val baseMask = (BigInt(1) << config.baseBits) - 1
    val halfBase = BigInt(1) << (config.baseBits - 1)
    val input = Seq.tabulate(config.polynomialSize) {
      case 0 => BigInt(0)
      case 1 => BigInt(1)
      case 2 => mask
      case index => BigInt((index * 53 + 29) & mask.toInt)
    }

    def rotate(exponent: Int): Seq[BigInt] = {
      val shift = exponent & (config.polynomialSize - 1)
      val highNegate = (exponent & config.polynomialSize) != 0
      Seq.tabulate(config.polynomialSize) { position =>
        val source = (position - shift + config.polynomialSize) %
          config.polynomialSize
        val wraps = position < shift
        if (wraps ^ highNegate) (-input(source)) & mask else input(source)
      }
    }

    def expected(exponent: Int, level: Int, position: Int): BigInt = {
      val rotated = rotate(exponent)
      val biased = (rotated(position) - input(position) +
        config.decompositionBias) & mask
      val shift = config.torusWidth - (level + 1) * config.baseBits
      ((biased >> shift) & baseMask) - halfBase
    }

    test(new BitwiseCmuxDecompositionFrontend(config, bitsPerCycle)) { dut =>
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(false.B)
      dut.io.rotateStart.poke(false.B)
      dut.io.updateStart.poke(false.B)
      dut.io.updateValid.poke(false.B)
      dut.io.drainStart.poke(false.B)
      dut.io.drainReady.poke(false.B)
      dut.io.exponent.poke(0.U)
      for (lane <- 0 until config.inverseLanes) {
        dut.io.load(lane).poke(0.U)
        dut.io.updateLow(lane).poke(0.U)
        dut.io.updateHigh(lane).poke(0.U)
      }
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.loadStart.poke(true.B)
      dut.clock.step()
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(true.B)
      for (beat <- 0 until config.polynomialBeats) {
        for (lane <- 0 until config.inverseLanes) {
          dut.io.load(lane).poke(input(beat * config.inverseLanes + lane).U)
        }
        dut.clock.step()
      }
      dut.io.loadValid.poke(false.B)
      dut.io.loadDone.expect(true.B)
      dut.clock.step()

      for (exponent <- 0 until 2 * config.polynomialSize) {
        dut.io.exponent.poke(exponent.U)
        dut.io.rotateStart.poke(true.B)
        dut.clock.step()
        dut.io.rotateStart.poke(false.B)
        while (!dut.io.done.peek().litToBoolean) {
          dut.clock.step()
        }
        for (level <- 0 until config.levels) {
          for (position <- 0 until config.polynomialSize) {
            dut.io.centeredDigit(level)(position).expect(
              expected(exponent, level, position).S
            )
          }
        }
        dut.clock.step()
      }
    }
  }
}

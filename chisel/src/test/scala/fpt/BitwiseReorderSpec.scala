package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class BitwiseReorderSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  behavior of "the coefficient-to-bitwise negacyclic reorder"

  it should "rotate and serially negate every coefficient exactly" in {
    val polynomialSize = 16
    val loadLanes = 4
    val coefficientWidth = 8
    val bitsPerCycle = 2
    val modulus = BigInt(1) << coefficientWidth
    val mask = modulus - 1
    val input = Seq.tabulate(polynomialSize) {
      case 0 => BigInt(0)
      case 1 => BigInt(1)
      case 2 => mask
      case 3 => BigInt(1) << (coefficientWidth - 1)
      case index => BigInt((index * 37 + 91) & mask.toInt)
    }

    def expected(
        exponent: Int,
        sourcePolynomial: Seq[BigInt] = input
    ): Seq[BigInt] = {
      val shift = exponent & (polynomialSize - 1)
      val highNegate = (exponent & polynomialSize) != 0
      Seq.tabulate(polynomialSize) { position =>
        val source = (position - shift + polynomialSize) % polynomialSize
        val wraps = position < shift
        if (wraps ^ highNegate)
          (-sourcePolynomial(source)) & mask
        else sourcePolynomial(source)
      }
    }

    test(
      new BitwiseNegacyclicReorder(
        polynomialSize,
        loadLanes,
        coefficientWidth,
        bitsPerCycle
      )
    ) { dut =>
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(false.B)
      dut.io.rotateStart.poke(false.B)
      dut.io.updateStart.poke(false.B)
      dut.io.updateValid.poke(false.B)
      dut.io.drainStart.poke(false.B)
      dut.io.drainReady.poke(false.B)
      dut.io.exponent.poke(0.U)
      dut.io.bitReady.poke(false.B)
      for (lane <- 0 until loadLanes) {
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
      for (beat <- 0 until polynomialSize / loadLanes) {
        dut.io.loadReady.expect(true.B)
        for (lane <- 0 until loadLanes) {
          dut.io.load(lane).poke(input(beat * loadLanes + lane).U)
        }
        dut.clock.step()
      }
      dut.io.loadValid.poke(false.B)
      dut.io.loadDone.expect(true.B)
      dut.clock.step()
      dut.io.loaded.expect(true.B)

      for (exponent <- 0 until 2 * polynomialSize) {
        dut.io.exponent.poke(exponent.U)
        dut.io.rotateStart.poke(true.B)
        dut.clock.step()
        dut.io.rotateStart.poke(false.B)

        val reconstructed = Array.fill(polynomialSize)(BigInt(0))
        for (chunk <- 0 until coefficientWidth / bitsPerCycle) {
          dut.io.bitValid.expect(true.B)
          dut.io.bitIndex.expect(chunk.U)
          for (position <- 0 until polynomialSize) {
            reconstructed(position) |=
              dut.io.bitChunk(position).peek().litValue <<
                (chunk * bitsPerCycle)
          }
          // Exercise carry preservation under output stalls.
          if (chunk == 1 && (exponent & 1) != 0) {
            dut.io.bitReady.poke(false.B)
            dut.clock.step()
            dut.io.bitIndex.expect(chunk.U)
          }
          dut.io.bitReady.poke(true.B)
          dut.clock.step()
          dut.io.bitReady.poke(false.B)
        }
        dut.io.rotateDone.expect(true.B)
        reconstructed.toSeq should be(expected(exponent))
        dut.clock.step()
      }

      val updated = input.toArray
      val updateBeats = polynomialSize / (2 * loadLanes)
      dut.io.updateStart.poke(true.B)
      dut.clock.step()
      dut.io.updateStart.poke(false.B)
      dut.io.updateValid.poke(true.B)
      for (beat <- 0 until updateBeats) {
        for (lane <- 0 until loadLanes) {
          val lowDelta = BigInt(beat * 19 + lane * 7 + 3) & mask
          val highDelta = BigInt(beat * 23 + lane * 11 + 5) & mask
          dut.io.updateLow(lane).poke(lowDelta.U)
          dut.io.updateHigh(lane).poke(highDelta.U)
          val lowIndex = beat * loadLanes + lane
          val highIndex = polynomialSize / 2 + lowIndex
          updated(lowIndex) = (updated(lowIndex) + lowDelta) & mask
          updated(highIndex) = (updated(highIndex) + highDelta) & mask
        }
        dut.io.updateReady.expect(true.B)
        dut.clock.step()
      }
      dut.io.updateValid.poke(false.B)
      dut.io.updateDone.expect(true.B)
      dut.clock.step()

      val updatedExponent = 11
      dut.io.exponent.poke(updatedExponent.U)
      dut.io.rotateStart.poke(true.B)
      dut.clock.step()
      dut.io.rotateStart.poke(false.B)
      dut.io.bitReady.poke(true.B)
      val updatedRotation = Array.fill(polynomialSize)(BigInt(0))
      for (chunk <- 0 until coefficientWidth / bitsPerCycle) {
        dut.io.bitValid.expect(true.B)
        for (position <- 0 until polynomialSize) {
          updatedRotation(position) |=
            dut.io.bitChunk(position).peek().litValue <<
              (chunk * bitsPerCycle)
        }
        dut.clock.step()
      }
      dut.io.bitReady.poke(false.B)
      updatedRotation.toSeq should be(expected(updatedExponent, updated.toSeq))
      dut.clock.step()

      dut.io.drainStart.poke(true.B)
      dut.clock.step()
      dut.io.drainStart.poke(false.B)
      dut.io.drainReady.poke(true.B)
      for (beat <- 0 until polynomialSize / loadLanes) {
        dut.io.drainValid.expect(true.B)
        for (lane <- 0 until loadLanes) {
          dut.io.drain(lane).expect(updated(beat * loadLanes + lane).U)
        }
        dut.clock.step()
      }
      dut.io.drainDone.expect(true.B)
    }
  }
}

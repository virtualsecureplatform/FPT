package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class InverseComponentJoinSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  behavior of "the serialized inverse component join"

  it should "re-pair distinct frames and hold them across backpressure" in {
    val frameBeats = 4
    val lanes = 2
    val width = 9
    val transactions = 3
    val low = Seq.tabulate(transactions, 2, frameBeats, lanes) {
      (transaction, component, beat, lane) =>
        BigInt(80 * transaction + 30 * component + 4 * beat + lane - 100)
    }
    val high = Seq.tabulate(transactions, 2, frameBeats, lanes) {
      (transaction, component, beat, lane) =>
        BigInt(70 * transaction + 20 * component + 3 * beat + lane - 90)
    }

    test(new InverseComponentJoin(frameBeats, lanes, width)) { dut =>
      dut.io.inputValid.poke(false.B)
      dut.io.outputReady.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      val inputs = for {
        transaction <- 0 until transactions
        component <- 0 until 2
        beat <- 0 until frameBeats
      } yield (transaction, component, beat)
      val expected = for {
        transaction <- 0 until transactions
        beat <- 0 until frameBeats
      } yield (transaction, beat)

      var inputIndex = 0
      var outputIndex = 0
      var cycle = 0
      while (outputIndex < expected.size && cycle < 200) {
        val outputReady = cycle % 5 != 1 && cycle % 7 != 3
        dut.io.outputReady.poke(outputReady.B)
        if (inputIndex < inputs.size) {
          val (transaction, component, beat) = inputs(inputIndex)
          dut.io.inputValid.poke(true.B)
          for (lane <- 0 until lanes) {
            dut.io.inputLow(lane).poke(low(transaction)(component)(beat)(lane).S)
            dut.io.inputHigh(lane).poke(high(transaction)(component)(beat)(lane).S)
          }
        } else {
          dut.io.inputValid.poke(false.B)
        }

        val inputFire = inputIndex < inputs.size &&
          dut.io.inputReady.peek().litToBoolean
        val outputValid = dut.io.outputValid.peek().litToBoolean
        if (outputValid) {
          val (transaction, beat) = expected(outputIndex)
          dut.io.outputFirst.expect((beat == 0).B)
          dut.io.outputLast.expect((beat == frameBeats - 1).B)
          for (lane <- 0 until lanes) {
            dut.io.outputLow(0)(lane).expect(low(transaction)(0)(beat)(lane).S)
            dut.io.outputHigh(0)(lane).expect(high(transaction)(0)(beat)(lane).S)
            dut.io.outputLow(1)(lane).expect(low(transaction)(1)(beat)(lane).S)
            dut.io.outputHigh(1)(lane).expect(high(transaction)(1)(beat)(lane).S)
          }
        }
        dut.clock.step()
        if (inputFire) inputIndex += 1
        if (outputValid && outputReady) outputIndex += 1
        cycle += 1
      }
      outputIndex shouldBe expected.size
      inputIndex shouldBe inputs.size
    }
  }
}

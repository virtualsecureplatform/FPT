package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer

final class SampleExtractSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val config = SampleExtractConfig(
    polynomialSize = 32,
    lanes = 4,
    torusWidth = 8
  )
  private val mask = Seq.tabulate(config.polynomialSize)(identity)
  private val body = Seq.tabulate(config.polynomialSize)(index => 128 + index)
  private val expected =
    Seq(mask.head) ++ mask.tail.reverse.map(value => (-value) & 0xff) ++
      Seq(body.head)

  private def initialize(dut: SampleExtractIndexZero): Unit = {
    dut.io.inputStart.poke(false.B)
    dut.io.inputValid.poke(false.B)
    dut.io.outputReady.poke(false.B)
    for (lane <- 0 until config.lanes) {
      dut.io.inputA(lane).poke(0.U)
      dut.io.inputB(lane).poke(0.U)
    }
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def load(dut: SampleExtractIndexZero): Unit = {
    dut.io.inputStartReady.expect(true.B)
    dut.io.inputStart.poke(true.B)
    dut.clock.step()
    dut.io.inputStart.poke(false.B)
    dut.io.inputValid.poke(true.B)
    for (beat <- 0 until config.inputBeats) {
      dut.io.inputReady.expect(true.B)
      for (lane <- 0 until config.lanes) {
        val index = beat * config.lanes + lane
        dut.io.inputA(lane).poke(mask(index).U)
        dut.io.inputB(lane).poke(body(index).U)
      }
      if (beat == config.inputBeats - 1) dut.io.inputDone.expect(true.B)
      dut.clock.step()
    }
    dut.io.inputValid.poke(false.B)
  }

  behavior of "index-zero TRLWE sample extraction"

  it should "stream the exact TLWE ordering at one coefficient per cycle" in {
    test(new SampleExtractIndexZero(config)) { dut =>
      initialize(dut)
      load(dut)
      dut.io.outputReady.poke(true.B)

      val actual = ArrayBuffer.empty[Int]
      val fireCycles = ArrayBuffer.empty[Int]
      var cycle = 0
      var sawDone = false
      while (!sawDone) {
        if (dut.io.outputValid.peek().litToBoolean) {
          actual += dut.io.output.peek().litValue.toInt
          fireCycles += cycle
          sawDone = dut.io.done.peek().litToBoolean
          dut.io.outputLast.expect(sawDone.B)
        }
        dut.clock.step()
        cycle += 1
        cycle should be < 100
      }

      actual.toSeq should be(expected)
      fireCycles.sliding(2).foreach { window =>
        window(1) - window(0) should be(1)
      }
      dut.io.inputStartReady.expect(true.B)
    }
  }

  it should "hold each coefficient stable across output backpressure" in {
    test(new SampleExtractIndexZero(config)) { dut =>
      initialize(dut)
      load(dut)

      val actual = ArrayBuffer.empty[Int]
      var stalled: Option[(BigInt, Boolean)] = None
      var cycle = 0
      while (actual.size < expected.size) {
        val ready = cycle % 4 != 1 && cycle % 7 != 3
        dut.io.outputReady.poke(ready.B)
        val valid = dut.io.outputValid.peek().litToBoolean
        if (valid && !ready) {
          val current = (
            dut.io.output.peek().litValue,
            dut.io.outputLast.peek().litToBoolean
          )
          stalled.foreach(_ should be(current))
          stalled = Some(current)
        } else if (valid && ready) {
          stalled.foreach { held =>
            held should be((
              dut.io.output.peek().litValue,
              dut.io.outputLast.peek().litToBoolean
            ))
          }
          stalled = None
          actual += dut.io.output.peek().litValue.toInt
        }
        dut.clock.step()
        cycle += 1
        cycle should be < 200
      }

      actual.toSeq should be(expected)
      dut.io.inputStartReady.expect(true.B)
    }
  }
}

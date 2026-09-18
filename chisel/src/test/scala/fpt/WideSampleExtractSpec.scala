package fpt

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer

final class WideSampleExtractSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  for ((n, lanes, width) <- Seq((32, 4, 8), (1024, 64, 32))) {
    it should s"extract $n coefficients in order with stalls, reset, and repeated frames" in {
      val config = SampleExtractConfig(n, lanes, width, 4)
      test(new SampleExtractIndexZero(config)) { dut =>
        val modulus = (BigInt(1) << width) - 1
        val random = new scala.util.Random(91)
        dut.io.inputStart.poke(false.B); dut.io.inputValid.poke(false.B); dut.io.outputReady.poke(false.B)
        dut.io.inputA.foreach(_.poke(0.U)); dut.io.inputB.foreach(_.poke(0.U))
        dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
        def load(a: Seq[BigInt], b: BigInt): Unit = {
          dut.io.inputStartReady.expect(true.B)
          dut.io.inputStart.poke(true.B); dut.clock.step(); dut.io.inputStart.poke(false.B)
          for (beat <- 0 until n / lanes) {
            dut.io.inputValid.poke(false.B); if (beat % 3 != 0) dut.clock.step(beat % 3)
            dut.io.inputValid.poke(true.B); dut.io.inputReady.expect(true.B)
            for (lane <- 0 until lanes) {
              dut.io.inputA(lane).poke(a(beat * lanes + lane).U)
              dut.io.inputB(lane).poke(b.U)
            }
            dut.io.inputDone.expect((beat == n / lanes - 1).B)
            dut.clock.step()
          }
          dut.io.inputValid.poke(false.B)
        }
        // Interrupt an output with queued reads/results and reload immediately.
        load(Seq.fill(n)(modulus), modulus)
        dut.clock.step(12); dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
        for (frame <- 0 until 3) {
          val a = Seq.fill(n)(BigInt(width, random)); val b = BigInt(width, random)
          load(a, b)
          val expected = Seq(a.head) ++ a.tail.reverse.map(x => (-x) & modulus) ++ Seq(b)
          val actual = ArrayBuffer.empty[BigInt]
          var held = Option.empty[(BigInt, BigInt, Boolean)]
          val cycles = ArrayBuffer.empty[Int]
          var cycle = 0
          while (actual.size < n + 1 && cycle < n * 3) {
            val ready = frame == 0 || (cycle > 12 && random.nextInt(4) != 0)
            dut.io.outputReady.poke(ready.B)
            if (dut.io.outputValid.peek().litToBoolean) {
              val value = (dut.io.output.peek().litValue, dut.io.outputCount.peek().litValue, dut.io.outputLast.peek().litToBoolean)
              held.foreach(_ should be(value))
              if (!ready) held = Some(value)
              else {
                held = None
                value._2.toInt should be(if (actual.isEmpty) 1 else 4)
                for (lane <- 0 until value._2.toInt) actual += (value._1 >> (lane * width)) & modulus
                value._3 should be(actual.size == n + 1)
                dut.io.done.expect(value._3.B)
                cycles += cycle
              }
            } else held should be(empty)
            dut.clock.step(); cycle += 1
          }
          actual.toSeq should be(expected)
          dut.io.inputStartReady.expect(true.B)
          if (frame == 0) {
            cycles.size should be(n / 4 + 1)
            cycles.drop(1).sliding(2).foreach(xs => xs(1) - xs(0) should be(1))
            info(s"n=$n wide output cycles=$cycle beats=${cycles.size}")
          }
          dut.io.outputReady.poke(false.B)
        }
      }
    }
  }

  it should "compact partial contexts without padding and preserve data/keep/last under stalls" in {
    test(new ResultBeatCompactor()) { dut =>
      val random = new scala.util.Random(17)
      dut.io.input.valid.poke(false.B); dut.io.input.bits.data.poke(0.U)
      dut.io.input.bits.count.poke(1.U); dut.io.input.bits.last.poke(false.B)
      dut.io.output.ready.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      // Partial queued packet must disappear on reset, despite unreset payload.
      dut.io.input.valid.poke(true.B); dut.io.input.bits.data.poke(123.U); dut.clock.step()
      dut.io.input.valid.poke(false.B); dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      for (length <- Seq(1, 3, 4, 5, 16 * 1025, 7, 1025)) {
        val continuous = length == 1025
        val words = Seq.fill(length)(BigInt(32, random))
        val actual = ArrayBuffer.empty[BigInt]
        var sent = 0; var cycle = 0; var done = false
        var pending = Option.empty[Seq[BigInt]]
        var held = Option.empty[(BigInt, BigInt, Boolean)]
        while (!done && cycle < length * 10 + 100) {
          if (pending.isEmpty && sent < length && (continuous || random.nextInt(4) != 0))
            pending = Some(words.slice(sent, sent + (if (sent % 1025 == 0) 1 else 4)))
          dut.io.input.valid.poke(pending.nonEmpty.B)
          pending.foreach { batch =>
            // Poison invalid lanes to ensure they never enter the packed output.
            val padded = batch ++ Seq.fill(4 - batch.size)(BigInt("deadbeef", 16))
            dut.io.input.bits.data.poke(padded.zipWithIndex.map{case (x,i) => x << (32*i)}.reduce(_ | _).U)
            dut.io.input.bits.count.poke(batch.size.U)
            dut.io.input.bits.last.poke((sent + batch.size == length).B)
          }
          val ready = continuous || (cycle > 8 && random.nextInt(3) != 0)
          dut.io.output.ready.poke(ready.B)
          if (continuous && sent >= 9 && !done && actual.size + 4 < length)
            dut.io.output.valid.expect(true.B)
          if (dut.io.output.valid.peek().litToBoolean) {
            val value = (dut.io.output.bits.data.peek().litValue, dut.io.output.bits.keep.peek().litValue, dut.io.output.bits.last.peek().litToBoolean)
            held.foreach(_ should be(value))
            if (!ready) held = Some(value)
            else {
              held = None
              val count = math.min(4, length - actual.size)
              value._2 should be((BigInt(1) << (4 * count)) - 1)
              for (lane <- 0 until count) actual += (value._1 >> (32 * lane)) & ((BigInt(1) << 32) - 1)
              value._3 should be(actual.size == length)
              done = value._3
            }
          } else held should be(empty)
          if (pending.nonEmpty && dut.io.input.ready.peek().litToBoolean) {
            sent += pending.get.size; pending = None
          }
          dut.clock.step(); cycle += 1
        }
        done should be(true)
        actual.toSeq should be(words)
        dut.io.input.valid.poke(false.B)
        dut.io.output.valid.expect(false.B)
      }
    }
  }
}

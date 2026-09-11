package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

final class CoefficientDigitLinkSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "the lossless coefficient digit SLR link"

  it should "preserve all signed digits and 256 lane positions with four-cycle start/data latency" in {
    test(new BatchedCmuxCoefficientDigitLink(128, 10, 30, 12))
      .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
        dut.io.sourceStart.poke(false.B)
        dut.io.sourceValid.poke(false.B)
        dut.io.destinationReady.poke(true.B)
        dut.io.sourceLow.foreach(_.poke(0.S))
        dut.io.sourceHigh.foreach(_.poke(0.S))
        val seen = scala.collection.mutable.Set.empty[Int]
        def digit(cycle: Int, component: Int, lane: Int, epoch: Int): Int =
          ((cycle * 256 + component * 128 + lane + epoch * 17) & 1023) - 512
        for (epoch <- 0 until 2) {
          // The previous epoch ends with live data in the pipeline.
          dut.reset.poke(true.B)
          dut.clock.step()
          dut.io.destinationStart.expect(false.B)
          dut.io.destinationValid.expect(false.B)
          dut.reset.poke(false.B)
          val history = scala.collection.mutable.ArrayBuffer.empty[(Boolean, Boolean)]
          for (cycle <- 0 until 61) {
            val phase = cycle % 24
            val start = Set(0, 4, 8, 12).contains(phase)
            val valid = phase >= 1 && phase <= 16
            dut.io.sourceStart.poke(start.B)
            dut.io.sourceValid.poke(valid.B)
            dut.io.sourceReady.expect(true.B)
            for ((port, component) <- Seq((dut.io.sourceLow, 0), (dut.io.sourceHigh, 1));
                 lane <- 0 until 128) {
              val value = digit(cycle, component, lane, epoch)
              port(lane).poke((BigInt(value) << 12).S)
              if (valid) seen += value
            }
            history += ((start, valid))
            dut.clock.step()
            val expected = if (cycle >= 3) history(cycle - 3) else (false, false)
            dut.io.destinationStart.expect(expected._1.B)
            dut.io.destinationValid.expect(expected._2.B)
            if (expected._2) {
              for ((port, component) <- Seq((dut.io.destinationLow, 0), (dut.io.destinationHigh, 1));
                   lane <- 0 until 128) {
                port(lane).expect((BigInt(digit(cycle - 3, component, lane, epoch)) << 12).S)
              }
            }
          }
        }
        assert(seen.toSet == (-512 until 512).toSet)
      }
  }

  it should "reject a format too narrow to represent the digit exactly" in {
    assertThrows[IllegalArgumentException] {
      circt.stage.ChiselStage.emitCHIRRTL(new BatchedCmuxCoefficientDigitLink(1, 10, 21, 12))
    }
  }
}

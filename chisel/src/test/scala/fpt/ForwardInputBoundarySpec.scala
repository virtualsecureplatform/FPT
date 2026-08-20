package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

final class ForwardInputBoundarySpec
    extends AnyFlatSpec
    with ChiselScalatestTester {
  behavior of "the forward-SLR destination boundary"

  it should "delay start, valid, and the complete payload by one cycle" in {
    test(
      new BatchedCmuxForwardInputBoundary(
        lanes = 3,
        dataWidth = 9,
        preservePhysicalRegister = false
      )
    ) { dut =>
        dut.io.destinationReady.poke(true.B)
        dut.io.sourceStart.poke(false.B)
        dut.io.sourceValid.poke(false.B)
        for (lane <- 0 until 3) {
          dut.io.sourceLow(lane).poke(0.S)
          dut.io.sourceHigh(lane).poke(0.S)
        }
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        val beats = Seq(
          (true, Seq(-3, 4, 17), Seq(9, -12, 31)),
          (false, Seq(7, -8, 2), Seq(-1, 6, -15)),
          (false, Seq(11, 12, -13), Seq(14, -15, 16))
        )
        for (((start, low, high), beat) <- beats.zipWithIndex) {
          dut.io.sourceReady.expect(true.B)
          dut.io.sourceStart.poke(start.B)
          dut.io.sourceValid.poke(true.B)
          for (lane <- 0 until 3) {
            dut.io.sourceLow(lane).poke(low(lane).S)
            dut.io.sourceHigh(lane).poke(high(lane).S)
          }
          dut.clock.step()
          dut.io.destinationValid.expect(true.B)
          dut.io.destinationStart.expect((beat == 0).B)
          for (lane <- 0 until 3) {
            dut.io.destinationLow(lane).expect(low(lane).S)
            dut.io.destinationHigh(lane).expect(high(lane).S)
          }
        }

        dut.io.sourceStart.poke(false.B)
        dut.io.sourceValid.poke(false.B)
        dut.clock.step()
        dut.io.destinationValid.expect(false.B)
        dut.io.destinationStart.expect(false.B)
      }
  }
}

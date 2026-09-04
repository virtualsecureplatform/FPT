package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

final class InverseOutputBoundarySpec
    extends AnyFlatSpec
    with ChiselScalatestTester {
  behavior of "the inverse-SLR receiver boundary"

  it should "delay valid and the complete payload by one cycle" in {
    test(
      new BatchedCmuxInverseOutputBoundary(
        lanes = 3,
        dataWidth = 9,
        preservePhysicalRegister = false
      )
    ) { dut =>
      dut.io.destinationReady.poke(true.B)
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
        (true, Seq(7, -8, 2), Seq(-1, 6, -15)),
        (false, Seq(11, 12, -13), Seq(14, -15, 16)),
        (true, Seq(-21, 22, 23), Seq(24, -25, 26))
      )
      for ((valid, low, high) <- beats) {
        dut.io.sourceReady.expect(true.B)
        dut.io.sourceValid.poke(valid.B)
        for (lane <- 0 until 3) {
          dut.io.sourceLow(lane).poke(low(lane).S)
          dut.io.sourceHigh(lane).poke(high(lane).S)
        }
        dut.clock.step()
        dut.io.destinationValid.expect(valid.B)
        if (valid) {
          for (lane <- 0 until 3) {
            dut.io.destinationLow(lane).expect(low(lane).S)
            dut.io.destinationHigh(lane).expect(high(lane).S)
          }
        }
      }
    }
  }
}

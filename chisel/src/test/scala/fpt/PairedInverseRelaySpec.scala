package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

private[fpt] class PairedInverseRelayHarness extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val low = Input(Vec(3, SInt(9.W)))
    val high = Input(Vec(3, SInt(9.W)))
    val outputValid = Output(Bool())
    val outputLow = Output(Vec(3, SInt(9.W)))
    val outputHigh = Output(Vec(3, SInt(9.W)))
  })
  val relays = Seq.fill(4)(Module(new BatchedCmuxInverseOutputBoundary(
    3, 9, resetPhysicalValid = true)))
  relays.head.io.sourceValid := io.valid
  relays.head.io.sourceLow := io.low
  relays.head.io.sourceHigh := io.high
  relays.foreach(_.io.destinationReady := true.B)
  relays.sliding(2).foreach { p =>
    p(1).io.sourceValid := p(0).io.destinationValid
    p(1).io.sourceLow := p(0).io.destinationLow
    p(1).io.sourceHigh := p(0).io.destinationHigh
  }
  io.outputValid := relays.last.io.destinationValid
  io.outputLow := relays.last.io.destinationLow
  io.outputHigh := relays.last.io.destinationHigh
}

final class PairedInverseRelaySpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "paired inverse SLR relays"
  it should "preserve every beat with four-cycle latency and flush validity on reset" in {
    test(new PairedInverseRelayHarness)
      .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
        dut.io.valid.poke(false.B)
        dut.io.low.foreach(_.poke(0.S))
        dut.io.high.foreach(_.poke(0.S))
        for (epoch <- 0 until 4) {
          dut.reset.poke(true.B)
          dut.clock.step()
          dut.io.outputValid.expect(false.B)
          dut.reset.poke(false.B)
          val history = scala.collection.mutable.ArrayBuffer.empty[(Boolean, Int)]
          for (cycle <- 0 until 80) {
            val valid = cycle % 13 < 9
            val value = cycle - 40 + epoch
            dut.io.valid.poke(valid.B)
            for (lane <- 0 until 3) {
              dut.io.low(lane).poke((value + lane).S)
              dut.io.high(lane).poke((-value - lane).S)
            }
            history += ((valid, value))
            dut.clock.step()
            val expected = if (cycle >= 3) history(cycle - 3) else (false, 0)
            dut.io.outputValid.expect(expected._1.B)
            if (expected._1) for (lane <- 0 until 3) {
              dut.io.outputLow(lane).expect((expected._2 + lane).S)
              dut.io.outputHigh(lane).expect((-expected._2 - lane).S)
            }
          }
        }
      }
  }
}

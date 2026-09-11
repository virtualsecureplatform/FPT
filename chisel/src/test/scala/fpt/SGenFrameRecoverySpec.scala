package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

private class FrameAdmissionHarness extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val accepted = Output(Bool())
  })
  val ready = SGenFrameRecovery.ready(5)
  io.accepted := io.start && ready
}

final class SGenFrameRecoverySpec extends AnyFlatSpec with ChiselScalatestTester {
  "reset recovery" should "retain a pending start until the exact recovery boundary" in {
    test(new FrameAdmissionHarness) { dut =>
      dut.io.start.poke(true.B)
      for (_ <- 0 until 2) {
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        for (_ <- 0 until 5) { dut.io.accepted.expect(false.B); dut.clock.step() }
        dut.io.accepted.expect(true.B)
        dut.clock.step(3); dut.io.accepted.expect(true.B)
      }
    }
  }
}

package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class BufferedOutputRetirementSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private def init(d: BufferedOutputRetirement): Unit = {
    d.io.launch.valid.poke(false.B); d.io.launch.bits.id.poke(false.B)
    d.io.launch.bits.pointer.poke(0.U); d.io.launch.bits.preparationCycles.poke(100.U)
    d.io.runStart.poke(false.B); d.io.computeDone.poke(false.B); d.io.captured.poke(false.B)
    d.io.executionReleased.poke(false.B)
    d.io.executionStats.status.poke(0.U); d.io.executionStats.keyBeats.poke(0.U)
    d.io.executionStats.keyStarved.poke(0.U); d.io.executionStats.keyBlocked.poke(0.U)
    d.io.executionStats.runCycles.poke(0.U)
    d.io.failure.valid.poke(false.B); d.io.failure.bits.poke(0.U)
    d.io.command.ready.poke(false.B); d.io.input.valid.poke(false.B)
    d.io.input.bits.data.poke(0.U); d.io.input.bits.count.poke(4.U); d.io.input.bits.last.poke(false.B)
    d.io.output.ready.poke(false.B); d.io.status.valid.poke(false.B)
    d.io.status.bits.poke(128.U); d.io.error.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  private def pulse(d: BufferedOutputRetirement, p: Bool): Unit = {
    p.poke(true.B); d.clock.step(); p.poke(false.B)
  }
  private def launch(d: BufferedOutputRetirement, id: Boolean, pointer: Int): Unit = {
    d.io.launch.bits.id.poke(id.B); d.io.launch.bits.pointer.poke(pointer.U)
    d.io.ready.expect(true.B); pulse(d, d.io.launch.valid)
    pulse(d, d.io.runStart); d.clock.step(3)
  }
  private def capture(d: BufferedOutputRetirement, count: Int): Unit = {
    pulse(d, d.io.captured)
    d.io.executionStats.keyBeats.poke(count.U)
    pulse(d, d.io.executionReleased)
  }
  private def drain(d: BufferedOutputRetirement, pointer: Int, payload: BigInt, error: Boolean = false): Unit = {
    d.io.command.valid.expect(true.B)
    ((d.io.command.bits.peek().litValue >> 32) & ((BigInt(1) << 64) - 1)) should be(BigInt(pointer))
    d.io.command.ready.poke(true.B); d.clock.step(); d.io.command.ready.poke(false.B)
    d.io.input.valid.poke(true.B); d.io.input.bits.data.poke(payload.U)
    d.io.input.bits.last.poke(true.B); d.io.input.ready.expect(true.B)
    d.clock.step(); d.io.input.valid.poke(false.B)
    d.clock.step(10); d.io.output.valid.expect(true.B)
    d.io.output.bits.data.expect(payload.U); d.io.output.bits.last.expect(true.B)
    d.io.completion.valid.expect(false.B); d.io.retire.expect(false.B)
    d.io.output.ready.poke(true.B); d.clock.step(); d.io.output.ready.poke(false.B)
    d.clock.step(10); d.io.retire.expect(false.B)
    d.io.error.poke(error.B)
    pulse(d, d.io.status.valid)
    d.io.error.poke(false.B)
    d.clock.step() // sampled status precedes the sticky retirement decision
  }
  it should "retain old pointers and counters during the next execution and wait for real DMA status" in {
    test(new BufferedOutputRetirement(16)).withAnnotations(Seq(PhysicalRegisterModel.annotation)) { d =>
      init(d); launch(d, false, 4096); pulse(d, d.io.computeDone); capture(d, 101)
      launch(d, true, 8192); pulse(d, d.io.computeDone)
      drain(d, 4096, BigInt("123456789abcdef", 16))
      d.io.retire.expect(true.B); d.io.completion.valid.expect(true.B)
      d.io.completion.bits.id.expect(false.B); d.io.completion.bits.result.keyBeats.expect(101.U)
      d.io.completion.bits.stages(2).expect(4.U)
      d.clock.step(); d.io.retire.expect(false.B)
      capture(d, 202); drain(d, 8192, BigInt("fedcba987654321", 16))
      d.io.completion.bits.id.expect(true.B); d.io.completion.bits.result.keyBeats.expect(202.U)
      d.io.completion.bits.stages(2).expect(4.U)
      val completedStages = d.io.completion.bits.stages.map(_.peek().litValue)
      d.clock.step(7); d.io.completion.valid.expect(false.B)
      d.io.completion.bits.result.keyBeats.expect(202.U)
      d.io.completion.bits.stages.zip(completedStages).foreach { case (p,v) => p.expect(v.U) }
      launch(d, false, 12288)
    }
  }
  it should "hold a sliced final partial beat until DMA acceptance even after status arrives" in {
    test(new BufferedOutputRetirement(20)).withAnnotations(Seq(PhysicalRegisterModel.annotation)) { d =>
      init(d); launch(d, false, 4096); pulse(d, d.io.computeDone); capture(d, 101)
      d.io.command.ready.poke(true.B); d.clock.step(); d.io.command.ready.poke(false.B)
      // A one-word header and four-word tail compact into one full beat and
      // one partial beat. Delay DMA acceptance after both have been buffered.
      for ((data, count, last) <- Seq((BigInt(1), 1, false),
          ((BigInt(5) << 96) | (BigInt(4) << 64) | (BigInt(3) << 32) | 2, 4, true))) {
        d.io.input.valid.poke(true.B); d.io.input.bits.data.poke(data.U)
        d.io.input.bits.count.poke(count.U); d.io.input.bits.last.poke(last.B)
        var waited = 0
        while (!d.io.input.ready.peek().litToBoolean) { d.clock.step(); waited += 1; assert(waited < 20) }
        d.clock.step()
      }
      d.io.input.valid.poke(false.B); d.clock.step(12)
      pulse(d, d.io.status.valid)
      for (_ <- 0 until 8) {
        d.io.output.valid.expect(true.B); d.io.output.bits.keep.expect(65535.U)
        d.io.output.bits.data.expect(((BigInt(4) << 96) | (BigInt(3) << 64) | (BigInt(2) << 32) | 1).U)
        d.io.output.bits.last.expect(false.B); d.io.retire.expect(false.B); d.clock.step()
      }
      d.io.output.ready.poke(true.B); d.clock.step(); d.io.output.ready.poke(false.B)
      for (_ <- 0 until 8) {
        d.io.output.valid.expect(true.B); d.io.output.bits.keep.expect(15.U)
        (d.io.output.bits.data.peek().litValue & ((BigInt(1) << 32) - 1)) should be(BigInt(5))
        d.io.output.bits.last.expect(true.B); d.io.retire.expect(false.B); d.clock.step()
      }
      d.io.output.ready.poke(true.B); d.clock.step()
      d.io.retire.expect(true.B); d.clock.step(); d.io.output.valid.expect(false.B)
      init(d); d.io.output.valid.expect(false.B)
    }
  }
  it should "fail overlapping owners in order without retiring or accepting work until reset" in {
    test(new BufferedOutputRetirement(16)).withAnnotations(Seq(PhysicalRegisterModel.annotation)) { d =>
      init(d); launch(d, false, 4096); pulse(d, d.io.computeDone); capture(d, 101)
      launch(d, true, 8192)
      pulse(d, d.io.error)
      d.clock.step()
      for (id <- Seq(false, true)) {
        d.io.completion.valid.expect(true.B); d.io.completion.bits.id.expect(id.B)
        (d.io.completion.bits.result.status.peek().litValue & 1) should be(BigInt(1))
        d.io.retire.expect(false.B); d.io.ready.expect(false.B); d.clock.step()
      }
      d.io.completion.valid.expect(false.B); d.io.ready.expect(false.B)
      // Late status cannot release the snapshot after failure.
      pulse(d, d.io.status.valid); d.io.retire.expect(false.B)
      init(d); launch(d, false, 16384)
    }
  }
  it should "give a sampled error priority over successful status and preserve the first fault" in {
    test(new BufferedOutputRetirement(16)).withAnnotations(Seq(PhysicalRegisterModel.annotation)) { d =>
      init(d); launch(d, false, 4096); pulse(d, d.io.computeDone); capture(d, 101)
      launch(d, true, 8192)
      drain(d, 4096, BigInt(123), error = true)
      d.io.retire.expect(false.B); d.io.completion.valid.expect(true.B)
      d.io.completion.bits.id.expect(false.B)
      val first = d.io.completion.bits.result.status.peek().litValue
      (first & 1) should be(BigInt(1))
      d.io.failure.valid.poke(true.B); d.io.failure.bits.poke(4083.U)
      d.clock.step(); d.io.completion.valid.expect(true.B)
      d.io.completion.bits.id.expect(true.B); d.io.completion.bits.result.status.expect(first.U)
      d.io.retire.expect(false.B); d.clock.step(); d.io.completion.valid.expect(false.B)
    }
  }
}

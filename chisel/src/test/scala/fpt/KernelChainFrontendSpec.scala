package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class KernelChainFrontendSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private def init(d: KernelChainFrontend): Unit = {
    d.io.start.poke(false.B); d.io.continue.poke(false.B)
    args(d, 0)
    d.io.launch.ready.poke(false.B)
    d.io.executionDone.poke(false.B)
    d.io.executionResult.status.poke(0.U)
    d.io.executionResult.keyBeats.poke(0.U)
    d.io.executionResult.keyStarved.poke(0.U)
    d.io.executionResult.keyBlocked.poke(0.U)
    d.io.executionResult.runCycles.poke(0.U)
    d.io.inputCommand.ready.poke(false.B)
    d.io.inputData.valid.poke(false.B); d.io.inputData.bits.poke(0.U)
    d.io.inputStatus.valid.poke(false.B); d.io.inputStatus.bits.poke(128.U)
    d.io.inputError.poke(false.B)
    d.io.coreInputCommand.valid.poke(false.B); d.io.coreInputCommand.bits.poke(0.U)
    d.io.coreInputData.ready.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  private def args(d: KernelChainFrontend, id: Int): Unit = {
    Seq(d.io.arguments.input, d.io.arguments.keyLow, d.io.arguments.keyHigh,
      d.io.arguments.keyLow1, d.io.arguments.keyHigh1, d.io.arguments.output)
      .zipWithIndex.foreach { case (p, i) => p.poke((id * 4096L + i * 64).U) }
  }
  private def submit(d: KernelChainFrontend, id: Int): Unit = {
    args(d, id); d.io.start.poke(true.B); d.io.ready.expect(true.B)
    d.clock.step(); d.io.start.poke(false.B); args(d, 999)
  }
  private def until(d: KernelChainFrontend)(condition: => Boolean): Unit = {
    var n = 0
    while (!condition && n < 100) { d.clock.step(); n += 1 }
    assert(condition, "bounded handshake timed out")
  }
  private def beat(id: Int, index: Int): BigInt =
    (0 until 16).foldLeft(BigInt(0)) { (v, lane) => v | (BigInt(id * 10000 + index * 16 + lane) << (32 * lane)) }
  private def fetch(d: KernelChainFrontend, id: Int, status: Boolean = true): Unit = {
    until(d)(d.io.inputCommand.valid.peek().litToBoolean)
    val command = d.io.inputCommand.bits.peek().litValue
    ((command >> 32) & ((BigInt(1) << 64) - 1)) should be(BigInt(id * 4096))
    (command & ((BigInt(1) << 23) - 1)) should be(BigInt(d.inputBeats * 64))
    d.clock.step(3); d.io.inputCommand.bits.expect(command.U)
    d.io.inputCommand.ready.poke(true.B); d.clock.step(); d.io.inputCommand.ready.poke(false.B)
    for (i <- 0 until d.inputBeats) {
      if (i % 3 == 0) d.clock.step()
      d.io.inputData.valid.poke(true.B); d.io.inputData.bits.poke(beat(id, i).U)
      until(d)(d.io.inputData.ready.peek().litToBoolean)
      d.clock.step(); d.io.inputData.valid.poke(false.B)
    }
    d.io.inputData.ready.expect(false.B)
    if (status) {
      d.io.inputStatus.valid.poke(true.B); d.clock.step(); d.io.inputStatus.valid.poke(false.B)
    }
  }
  private def launch(d: KernelChainFrontend, id: Int): Unit = {
    until(d)(d.io.launch.valid.peek().litToBoolean)
    Seq(d.io.launch.bits.input, d.io.launch.bits.keyLow, d.io.launch.bits.keyHigh,
      d.io.launch.bits.keyLow1, d.io.launch.bits.keyHigh1, d.io.launch.bits.output)
      .zipWithIndex.foreach { case (p, i) => p.expect((id * 4096L + i * 64).U) }
    d.io.launch.ready.poke(true.B); d.clock.step(); d.io.launch.ready.poke(false.B)
    d.io.coreInputCommand.valid.poke(true.B); d.io.coreInputCommand.ready.expect(true.B)
    d.clock.step(); d.io.coreInputCommand.valid.poke(false.B)
    d.io.coreInputStatus.valid.expect(true.B)
  }
  private def drain(d: KernelChainFrontend, id: Int): Unit = {
    for (i <- 0 until d.inputBeats) {
      until(d)(d.io.coreInputData.valid.peek().litToBoolean)
      d.io.coreInputData.bits.expect(beat(id, i).U)
      if (i % 2 == 0) {
        d.clock.step(2); d.io.coreInputData.bits.expect(beat(id, i).U)
      }
      d.io.coreInputData.ready.poke(true.B); d.clock.step(); d.io.coreInputData.ready.poke(false.B)
    }
    d.io.coreInputData.valid.expect(false.B)
  }
  private def complete(d: KernelChainFrontend, id: Int): Unit = {
    d.io.executionResult.runCycles.poke((1000 + id).U)
    d.io.executionResult.keyBeats.poke((100 + id).U)
    d.io.executionDone.poke(true.B); d.clock.step(); d.io.executionDone.poke(false.B)
  }

  it should "prefetch a distinct next batch while executing and retain two ordered completions" in {
    test(new KernelChainFrontend(8)) { d =>
      init(d); submit(d, 1); submit(d, 2)
      d.io.start.poke(true.B); args(d, 3); d.io.ready.expect(false.B)
      d.clock.step(3); d.io.start.poke(false.B)
      fetch(d, 1, status = false)
      d.io.launch.valid.expect(false.B); d.clock.step(8); d.io.launch.valid.expect(false.B)
      d.io.inputStatus.valid.poke(true.B); d.clock.step(); d.io.inputStatus.valid.poke(false.B)
      launch(d, 1); drain(d, 1)
      fetch(d, 2) // Batch 1 has not completed: this is real prefetch overlap.
      d.clock.step(); d.io.prefetchedCount.expect(1.U)
      d.io.launch.valid.expect(false.B); d.io.coreInputData.valid.expect(false.B)
      complete(d, 1)
      d.io.done.expect(true.B); d.io.completion.runCycles.expect(1001.U)
      launch(d, 2); drain(d, 2); complete(d, 2)
      d.io.start.poke(true.B); args(d, 3); d.io.ready.expect(false.B); d.io.start.poke(false.B)
      d.io.executionResult.runCycles.poke(9999.U)
      d.clock.step(12); d.io.done.expect(true.B); d.io.completion.runCycles.expect(1001.U)
      d.io.continue.poke(true.B); d.clock.step()
      d.io.done.expect(true.B); d.io.completion.runCycles.expect(1002.U)
      d.clock.step(); d.io.continue.poke(false.B)
      d.io.done.expect(false.B); d.io.idle.expect(true.B)
      d.io.acceptedCount.expect(2.U); d.io.completedCount.expect(2.U)
      submit(d, 3); fetch(d, 3); launch(d, 3); drain(d, 3); complete(d, 3)
      d.io.completion.runCycles.expect(1003.U)
    }
  }

  it should "carry a complete production-sized 632-beat input without truncation" in {
    test(new KernelChainFrontend()) { d =>
      init(d); submit(d, 7); fetch(d, 7); launch(d, 7); drain(d, 7); complete(d, 7)
      d.io.done.expect(true.B); d.io.completion.status.expect(0.U)
    }
  }

  it should "fail queued jobs in order on a prefetch error and require reset" in {
    test(new KernelChainFrontend(4)) { d =>
      init(d); submit(d, 1); submit(d, 2)
      d.io.inputError.poke(true.B); d.clock.step(); d.io.inputError.poke(false.B)
      until(d)(d.io.done.peek().litToBoolean)
      d.io.completion.status.expect(4083.U) // 0xff status, input channel 1, sticky error.
      d.io.launch.valid.expect(false.B)
      d.io.continue.poke(true.B); d.clock.step(4); d.io.continue.poke(false.B)
      d.io.idle.expect(true.B); d.io.faulted.expect(true.B)
      d.io.start.poke(true.B); d.io.ready.expect(false.B); d.io.start.poke(false.B)
      init(d); submit(d, 3); fetch(d, 3); launch(d, 3); drain(d, 3); complete(d, 3)
      d.io.completion.status.expect(0.U)
    }
  }

  it should "finish validated active input even when the next prefetch fails" in {
    test(new KernelChainFrontend(4)) { d =>
      init(d); submit(d, 1); submit(d, 2); fetch(d, 1)
      d.io.launch.ready.poke(true.B); d.clock.step(); d.io.launch.ready.poke(false.B)
      d.io.inputError.poke(true.B); d.clock.step(); d.io.inputError.poke(false.B)
      d.io.coreInputCommand.valid.poke(true.B); d.io.coreInputCommand.ready.expect(true.B)
      d.clock.step(); d.io.coreInputCommand.valid.poke(false.B)
      drain(d, 1); complete(d, 1)
      until(d)(d.io.done.peek().litToBoolean)
      d.io.completion.status.expect(4083.U)
      d.io.launch.valid.expect(false.B)
    }
  }
}

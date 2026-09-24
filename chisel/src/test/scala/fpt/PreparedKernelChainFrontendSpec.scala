package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class PreparedKernelChainFrontendSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private def init(d: PreparedKernelChainFrontend): Unit = {
    d.clock.setTimeout(0)
    d.io.start.poke(false.B); d.io.continue.poke(false.B)
    arguments(d, 0)
    d.io.launch.ready.poke(false.B); d.io.executionDone.poke(false.B)
    d.io.executionReleased.foreach(_.poke(false.B))
    d.io.completionId.foreach(_.poke(false.B))
    d.io.executionResult.status.poke(0.U); d.io.executionResult.keyBeats.poke(0.U)
    d.io.executionResult.keyStarved.poke(0.U); d.io.executionResult.keyBlocked.poke(0.U)
    d.io.executionResult.runCycles.poke(0.U)
    d.io.executionStages.foreach(_.poke(0.U))
    d.io.inputCommand.ready.poke(false.B); d.io.inputData.valid.poke(false.B)
    d.io.inputData.bits.poke(0.U); d.io.inputStatus.valid.poke(false.B)
    d.io.inputStatus.bits.poke(128.U); d.io.inputError.poke(false.B)
    d.io.prepareStart.ready.poke(true.B); d.io.prepareWord.ready.poke(false.B)
    d.io.prepareDone.valid.poke(false.B); d.io.prepareDone.bits.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  private def arguments(d: PreparedKernelChainFrontend, id: Int): Unit = {
    Seq(d.io.arguments.input, d.io.arguments.keyLow, d.io.arguments.keyHigh,
      d.io.arguments.keyLow1, d.io.arguments.keyHigh1, d.io.arguments.output)
      .zipWithIndex.foreach { case (p, i) => p.poke((id * 4096L + i * 64).U) }
  }
  private def until(d: PreparedKernelChainFrontend)(condition: => Boolean): Unit = {
    var cycles=0
    while (!condition && cycles<100) { d.clock.step(); cycles+=1 }
    assert(condition, "bounded handshake timed out")
  }
  private def submit(d: PreparedKernelChainFrontend, id: Int): Unit = {
    arguments(d,id); d.io.start.poke(true.B); d.io.ready.expect(true.B)
    d.clock.step(); d.io.start.poke(false.B); arguments(d,999)
  }
  private def prepare(d: PreparedKernelChainFrontend, id: Int, bank: Boolean): Unit = {
    until(d)(d.io.inputCommand.valid.peek().litToBoolean)
    val command=d.io.inputCommand.bits.peek().litValue
    ((command>>32)&((BigInt(1)<<64)-1)) should be(BigInt(id*4096))
    d.io.inputCommand.ready.poke(true.B); d.clock.step(); d.io.inputCommand.ready.poke(false.B)
    var sent=0; var words=0; var cycles=0
    while(words<d.inputBeats*16 && cycles<d.inputBeats*64) {
      d.io.inputData.valid.poke((sent<d.inputBeats).B)
      val data=(0 until 16).foldLeft(BigInt(0))((v,l) => v | (BigInt(id*10000+sent*16+l) << (l*32)))
      d.io.inputData.bits.poke(data.U)
      d.io.prepareWord.ready.poke((cycles%3!=0).B)
      val accept=sent<d.inputBeats && d.io.inputData.ready.peek().litToBoolean
      val consume=cycles%3!=0 && d.io.prepareWord.valid.peek().litToBoolean
      if(consume) d.io.prepareWord.bits.expect((id*10000+words).U)
      d.io.prepareDone.valid.poke(false.B)
      d.io.prepareDone.bits.poke(bank.B)
      d.clock.step()
      if(accept) sent+=1
      if(consume) words+=1
      cycles+=1
    }
    words should be(d.inputBeats*16)
    sent should be(d.inputBeats)
    d.io.inputData.valid.poke(false.B); d.io.prepareWord.ready.poke(false.B)
    // The physical link drains after the final source handshake.
    d.clock.step(2)
    d.io.launch.valid.expect(false.B)
    d.io.prepareDone.valid.poke(true.B); d.clock.step()
    d.io.prepareDone.valid.poke(false.B)
    d.io.launch.valid.expect(false.B) // A prepared frame still needs DMA success.
    d.io.inputStatus.valid.poke(true.B); d.clock.step(); d.io.inputStatus.valid.poke(false.B)
    d.clock.step(2)
  }
  private def launch(d: PreparedKernelChainFrontend, id: Int, bank: Boolean): Unit = {
    until(d)(d.io.launch.valid.peek().litToBoolean)
    d.io.launch.bits.input.expect((id*4096).U); d.io.launch.bits.output.expect((id*4096+320).U)
    d.io.launchBank.expect(bank.B)
    d.io.launch.ready.poke(true.B); d.clock.step(); d.io.launch.ready.poke(false.B)
  }
  private def complete(d: PreparedKernelChainFrontend, id: Int, bank: Boolean): Unit = {
    d.io.executionResult.runCycles.poke((1000+id).U)
    d.io.executionStages.zipWithIndex.foreach { case(p,i) => p.poke((id*100+i).U) }
    d.io.executionDone.poke(true.B); d.io.releaseBank.valid.expect(true.B)
    d.io.releaseBank.bits.expect(bank.B)
    d.clock.step(); d.io.executionDone.poke(false.B)
  }
  private def acknowledge(d: PreparedKernelChainFrontend, id: Int): Unit = {
    d.io.done.expect(true.B); d.io.completion.runCycles.expect((1000+id).U)
    d.io.completionStages.zipWithIndex.foreach { case(p,i) => p.expect((id*100+i).U) }
    d.io.continue.poke(true.B); d.clock.step(); d.io.continue.poke(false.B)
  }
  it should "prepare four jobs with two bank reuses, delayed acknowledgments and ordered counters" in {
    test(new PreparedKernelChainFrontend(8)) { d =>
      init(d); submit(d,1); submit(d,2)
      d.io.start.poke(true.B); d.io.ready.expect(false.B); d.io.start.poke(false.B)
      prepare(d,1,false); launch(d,1,false)
      prepare(d,2,true); d.io.launch.valid.expect(false.B)
      complete(d,1,false); launch(d,2,true)
      acknowledge(d,1); submit(d,3); prepare(d,3,false)
      complete(d,2,true); launch(d,3,false)
      // Completion 2 must survive execution 3 without changing its snapshot.
      d.clock.step(3); acknowledge(d,2); submit(d,4); prepare(d,4,true)
      complete(d,3,false); launch(d,4,true); complete(d,4,true)
      acknowledge(d,3); acknowledge(d,4)
      d.io.idle.expect(true.B); d.io.acceptedCount.expect(4.U)
      d.io.completedCount.expect(4.U); d.io.prefetchedCount.expect(3.U)
    }
  }
  it should "fail partial preparation in order and accept work only after reset" in {
    test(new PreparedKernelChainFrontend(8)) { d =>
      init(d); submit(d,1); submit(d,2)
      until(d)(d.io.inputCommand.valid.peek().litToBoolean)
      d.io.inputCommand.ready.poke(true.B); d.clock.step(); d.io.inputCommand.ready.poke(false.B)
      d.io.inputData.valid.poke(true.B); d.io.inputData.bits.poke(123.U)
      d.clock.step(); d.io.inputData.valid.poke(false.B)
      d.io.inputError.poke(true.B); d.clock.step(); d.io.inputError.poke(false.B)
      d.clock.step(4); d.io.launch.valid.expect(false.B)
      d.io.start.poke(true.B); d.io.ready.expect(false.B); d.io.start.poke(false.B)
      for (_ <- 0 until 2) {
        d.io.done.expect(true.B); d.io.completion.status.expect(4083.U)
        d.io.continue.poke(true.B); d.clock.step(); d.io.continue.poke(false.B)
      }
      d.io.idle.expect(true.B); d.io.faulted.expect(true.B)
      init(d); submit(d,3); prepare(d,3,false); launch(d,3,false)
    }
  }
  it should "release execution before output completion without losing either invocation" in {
    test(new PreparedKernelChainFrontend(8, bufferedOutput = true)) { d =>
      init(d); submit(d, 1); submit(d, 2)
      prepare(d, 1, false); launch(d, 1, false)
      prepare(d, 2, true); d.io.launch.valid.expect(false.B)
      d.io.executionReleased.get.poke(true.B)
      d.io.releaseBank.valid.expect(true.B); d.io.releaseBank.bits.expect(false.B)
      d.clock.step(); d.io.executionReleased.get.poke(false.B)
      d.io.done.expect(false.B); launch(d, 2, true)
      d.io.completionId.get.poke(false.B); d.io.executionDone.poke(true.B)
      d.io.executionResult.runCycles.poke(1001.U)
      d.io.executionStages.zipWithIndex.foreach { case(p,i) => p.poke((100+i).U) }
      d.io.releaseBank.valid.expect(false.B)
      d.clock.step(); d.io.executionDone.poke(false.B)
      acknowledge(d, 1); submit(d, 3); prepare(d, 3, false)
      d.io.launch.valid.expect(false.B) // Completion 1 did not release execution 2.
      d.io.executionReleased.get.poke(true.B)
      d.io.releaseBank.bits.expect(true.B)
      d.clock.step(); d.io.executionReleased.get.poke(false.B)
      launch(d, 3, false)
      d.io.completionId.get.poke(true.B); d.io.executionDone.poke(true.B)
      d.io.executionResult.runCycles.poke(1002.U)
      d.io.executionStages.zipWithIndex.foreach { case(p,i) => p.poke((200+i).U) }
      d.clock.step(); d.io.executionDone.poke(false.B); acknowledge(d, 2)
      d.io.executionReleased.get.poke(true.B); d.clock.step(); d.io.executionReleased.get.poke(false.B)
      d.io.completionId.get.poke(false.B); d.io.executionDone.poke(true.B)
      d.io.executionResult.runCycles.poke(1003.U)
      d.io.executionStages.zipWithIndex.foreach { case(p,i) => p.poke((300+i).U) }
      d.clock.step(); d.io.executionDone.poke(false.B); acknowledge(d, 3)
      d.io.idle.expect(true.B)
    }
  }
  it should "retain the active bank on a next-batch DMA fault and clear partial state on reset" in {
    test(new PreparedKernelChainFrontend(8)) { d =>
      init(d); submit(d,1); submit(d,2)
      prepare(d,1,false); launch(d,1,false)
      until(d)(d.io.inputCommand.valid.peek().litToBoolean)
      d.io.inputCommand.ready.poke(true.B); d.clock.step(); d.io.inputCommand.ready.poke(false.B)
      d.io.inputData.valid.poke(true.B); d.io.inputData.bits.poke(7.U)
      d.clock.step(); d.io.inputData.valid.poke(false.B)
      d.io.inputError.poke(true.B); d.clock.step(); d.io.inputError.poke(false.B)
      d.io.done.expect(false.B); d.io.releaseBank.valid.expect(false.B)
      d.io.executionDone.poke(true.B); d.io.releaseBank.valid.expect(false.B)
      d.clock.step(); d.io.executionDone.poke(false.B); d.clock.step(3)
      for (_ <- 0 until 2) {
        d.io.done.expect(true.B); d.io.completion.status.expect(4083.U)
        d.io.continue.poke(true.B); d.clock.step(); d.io.continue.poke(false.B)
      }
      init(d); submit(d,3)
      until(d)(d.io.inputCommand.valid.peek().litToBoolean)
      // Reset in the middle of an otherwise successful preparation as well.
      d.io.inputCommand.ready.poke(true.B); d.clock.step()
      d.io.inputData.valid.poke(true.B); d.io.inputData.bits.poke(99.U); d.clock.step()
      init(d); submit(d,4); prepare(d,4,false); launch(d,4,false)
    }
  }
}

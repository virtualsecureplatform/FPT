package fpt

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Real frontend, physical crossing and bank parser; only execution is modeled. */
private final class PreparedInputPipelineHarness extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val arguments = Input(new KernelInvocation)
    val continue = Input(Bool())
    val ready = Output(Bool())
    val done = Output(Bool())
    val status = Output(UInt(32.W))
    val allowStart = Input(Bool())
    // Scalar test ports avoid ChiselTest 6's legacy VlWide pointer ABI.
    val input = Flipped(Decoupled(Vec(16, UInt(32.W))))
    val inputStatus = Flipped(Valid(UInt(8.W)))
    val inputError = Input(Bool())
    val launchReady = Input(Bool())
    val launchValid = Output(Bool())
    val launchBank = Output(Bool())
    val executionDone = Input(Bool())
    val released = Output(Bool())
    val issued = Output(Bool())
    val consumed = Output(Bool())
    val prepared = Output(Bool())
    val read = Flipped(Valid(UInt(5.W)))
    val exponent = Output(UInt(11.W))
    val descriptorRequest = Flipped(Valid(UInt(1.W)))
    val descriptor = Valid(new PreparedInputDescriptor(32, 11))
    val accepted = Output(UInt(32.W))
    val prefetched = Output(UInt(32.W))
    val completed = Output(UInt(32.W))
  })
  val chain = Module(new PreparedKernelChainFrontend(2))
  val link = Module(new PreparedWordLink)
  val banks = Module(new PreparedBatchInputs(2, 14))
  chain.io.start := io.start; chain.io.arguments := io.arguments
  chain.io.continue := io.continue
  io.ready := chain.io.ready; io.done := chain.io.done
  io.status := chain.io.completion.status
  chain.io.inputCommand.ready := true.B
  chain.io.inputData.valid := io.input.valid
  chain.io.inputData.bits := io.input.bits.asUInt
  io.input.ready := chain.io.inputData.ready
  chain.io.inputStatus := io.inputStatus; chain.io.inputError := io.inputError
  banks.io.start.valid := chain.io.prepareStart.valid && io.allowStart
  banks.io.start.bits := chain.io.prepareStart.bits
  chain.io.prepareStart.ready := banks.io.start.ready && io.allowStart
  link.io.input <> chain.io.prepareWord
  banks.io.word <> link.io.output
  chain.io.prepareDone := banks.io.prepared
  chain.io.launch.ready := io.launchReady
  io.launchValid := chain.io.launch.valid; io.launchBank := chain.io.launchBank
  banks.io.activate.valid := chain.io.launch.fire
  banks.io.activate.bits := chain.io.launchBank
  when(chain.io.launch.fire) { assert(banks.io.activate.ready) }
  chain.io.executionDone := io.executionDone
  chain.io.executionResult := 0.U.asTypeOf(new KernelCompletion)
  chain.io.executionStages := 0.U.asTypeOf(chain.io.executionStages)
  banks.io.release := chain.io.releaseBank
  io.released := chain.io.releaseBank.valid
  io.issued := chain.io.prepareWord.fire
  io.consumed := banks.io.word.fire
  io.prepared := banks.io.prepared.valid
  banks.io.read := io.read; io.exponent := banks.io.exponent
  banks.io.descriptorRequest := io.descriptorRequest
  io.descriptor := banks.io.descriptor
  io.accepted := chain.io.acceptedCount; io.prefetched := chain.io.prefetchedCount
  io.completed := chain.io.completedCount
}

final class PreparedInputPipelineSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val backend = Seq(VerilatorBackendAnnotation, PaperVerilator.flags)
  private val mask = (BigInt(1) << 32) - 1
  private def words(id: Int): Seq[BigInt] = (0 until 2).flatMap { context =>
    Seq(BigInt(id * 100 + context)) ++ (0 until 14).map { i =>
      (BigInt(id * 7919 + context * 31 + i) * 1234567 + (1 << 20)) & mask
    } ++ Seq((mask - id * 171 - context) & mask)
  }
  private def switched(word: BigInt): BigInt = ((word + (1 << 20)) & mask) >> 21
  private def initialize(d: PreparedInputPipelineHarness): Unit = {
    d.clock.setTimeout(0)
    d.io.start.poke(false.B); d.io.continue.poke(false.B)
    Seq(d.io.arguments.input, d.io.arguments.output, d.io.arguments.keyLow,
      d.io.arguments.keyHigh, d.io.arguments.keyLow1, d.io.arguments.keyHigh1).foreach(_.poke(0.U))
    d.io.allowStart.poke(true.B); d.io.launchReady.poke(false.B)
    d.io.input.valid.poke(false.B); d.io.input.bits.foreach(_.poke(0.U))
    d.io.inputStatus.valid.poke(false.B); d.io.inputStatus.bits.poke(128.U)
    d.io.inputError.poke(false.B); d.io.executionDone.poke(false.B)
    d.io.read.valid.poke(false.B); d.io.read.bits.poke(0.U)
    d.io.descriptorRequest.valid.poke(false.B); d.io.descriptorRequest.bits.poke(0.U)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  private def submit(d: PreparedInputPipelineHarness, id: Int): Unit = {
    d.io.arguments.input.poke((id * 4096).U)
    d.io.start.poke(true.B); d.io.ready.expect(true.B)
    d.clock.step(); d.io.start.poke(false.B)
  }
  private def feed(d: PreparedInputPipelineHarness, id: Int, stopAfter: Int = 32,
      activeId: Option[Int] = None): Unit = {
    val data = words(id).grouped(16).toSeq
    var beat = 0; var issued = 0; var cycles = 0
    while (issued < stopAfter && cycles < 150) {
      d.io.input.valid.poke((beat < 2 && cycles % 5 != 0).B)
      d.io.input.bits.zip(data(math.min(beat, 1))).foreach { case (port, word) => port.poke(word.U) }
      activeId.foreach { _ => d.io.read.valid.poke(true.B); d.io.read.bits.poke(0.U) }
      val accepted = beat < 2 && cycles % 5 != 0 && d.io.input.ready.peek().litToBoolean
      if (d.io.issued.peek().litToBoolean) issued += 1
      d.io.launchValid.expect(false.B)
      d.clock.step()
      activeId.foreach(i => d.io.exponent.expect(switched(words(i)(1)).U))
      if (accepted) beat += 1
      cycles += 1
    }
    issued should be(stopAfter)
    d.io.input.valid.poke(false.B); d.io.read.valid.poke(false.B)
  }
  private def finishPreparation(d: PreparedInputPipelineHarness): Unit = {
    d.io.launchValid.expect(false.B)
    d.clock.step() // first remaining register-to-register transfer
    d.io.prepared.expect(true.B)
    d.io.launchValid.expect(false.B)
    d.clock.step() // final word is committed
    d.io.prepared.expect(false.B)
    d.io.launchValid.expect(false.B) // still needs successful DMA status
    d.io.inputStatus.valid.poke(true.B); d.clock.step()
    d.io.inputStatus.valid.poke(false.B); d.clock.step()
  }
  private def launchAndCheck(d: PreparedInputPipelineHarness, id: Int, bank: Boolean): Unit = {
    d.io.launchValid.expect(true.B); d.io.launchBank.expect(bank.B)
    d.io.launchReady.poke(true.B); d.clock.step(); d.io.launchReady.poke(false.B)
    for (context <- 0 until 2) {
      val frame = words(id).slice(context * 16, (context + 1) * 16)
      val sum = frame.slice(1, 15).map(w => w - (switched(w) << 21)).sum & mask
      val signed = if (sum.testBit(31)) sum - (BigInt(1) << 32) else sum
      val expected = (-switched((frame.last - signed / 2) & mask)) & 2047
      d.io.descriptorRequest.valid.poke(true.B); d.io.descriptorRequest.bits.poke(context.U)
      d.clock.step(); d.io.descriptorRequest.valid.poke(false.B)
      d.io.descriptor.valid.expect(true.B)
      d.io.descriptor.bits.testVector.expect(frame.head.U)
      d.io.descriptor.bits.exponent.expect(expected.U)
      for (index <- 0 until 14) {
        d.io.read.valid.poke(true.B); d.io.read.bits.poke((context * 14 + index).U)
        d.clock.step(); d.io.exponent.expect(switched(frame(index + 1)).U)
      }
      d.io.read.valid.poke(false.B)
    }
  }
  private def complete(d: PreparedInputPipelineHarness): Unit = {
    d.io.executionDone.poke(true.B); d.io.released.expect(true.B)
    d.clock.step(); d.io.executionDone.poke(false.B)
  }
  private def acknowledge(d: PreparedInputPipelineHarness): Unit = {
    d.io.done.expect(true.B); d.io.status.expect(0.U)
    d.io.continue.poke(true.B); d.clock.step(); d.io.continue.poke(false.B)
  }
  it should "preserve four ordered batches across delayed starts, gaps, overlap and bank reuse" in {
    test(new PreparedInputPipelineHarness).withAnnotations(backend) { d =>
      initialize(d); d.io.allowStart.poke(false.B)
      submit(d, 1); submit(d, 2)
      d.io.input.valid.poke(true.B)
      for (_ <- 0 until 4) {
        d.io.input.ready.expect(false.B); d.io.issued.expect(false.B); d.clock.step()
      }
      d.io.input.valid.poke(false.B); d.io.allowStart.poke(true.B)
      feed(d, 1); finishPreparation(d); launchAndCheck(d, 1, false)
      feed(d, 2, activeId = Some(1)); finishPreparation(d)
      complete(d); launchAndCheck(d, 2, true)
      acknowledge(d); submit(d, 3)
      feed(d, 3, activeId = Some(2)); finishPreparation(d)
      complete(d); launchAndCheck(d, 3, false)
      acknowledge(d); submit(d, 4)
      feed(d, 4, activeId = Some(3)); finishPreparation(d)
      complete(d); launchAndCheck(d, 4, true); complete(d)
      acknowledge(d); acknowledge(d)
      d.io.accepted.expect(4.U); d.io.completed.expect(4.U); d.io.prefetched.expect(3.U)
    }
  }
  it should "ignore faulted in-flight completion and flush occupied links on reset" in {
    test(new PreparedInputPipelineHarness).withAnnotations(backend) { d =>
      for (stop <- Seq(1, 2, 31, 32)) {
        initialize(d); submit(d, 1); submit(d, 2); feed(d, 1, stop)
        d.io.inputError.poke(true.B); d.clock.step(); d.io.inputError.poke(false.B)
        for (_ <- 0 until 6) {
          d.io.launchValid.expect(false.B); d.io.released.expect(false.B); d.clock.step()
        }
        for (_ <- 0 until 2) {
          d.io.done.expect(true.B); d.io.status.expect(4083.U)
          d.io.continue.poke(true.B); d.clock.step(); d.io.continue.poke(false.B)
        }
        initialize(d); submit(d, 3); feed(d, 3, stop)
        initialize(d)
        for (_ <- 0 until 4) { d.io.consumed.expect(false.B); d.io.prepared.expect(false.B); d.clock.step() }
        submit(d, 4); feed(d, 4); finishPreparation(d); launchAndCheck(d, 4, false)
      }
    }
  }
  it should "keep the active bank readable when the next frame faults during final-word drain" in {
    test(new PreparedInputPipelineHarness).withAnnotations(backend) { d =>
      initialize(d); submit(d, 1); submit(d, 2)
      feed(d, 1); finishPreparation(d); launchAndCheck(d, 1, false)
      feed(d, 2, activeId = Some(1))
      d.io.inputError.poke(true.B); d.clock.step(); d.io.inputError.poke(false.B)
      d.io.read.valid.poke(true.B); d.io.read.bits.poke(0.U)
      for (_ <- 0 until 5) {
        d.io.launchValid.expect(false.B); d.io.released.expect(false.B)
        d.clock.step(); d.io.exponent.expect(switched(words(1)(1)).U)
      }
      d.io.executionDone.poke(true.B); d.io.released.expect(false.B)
      d.clock.step(); d.io.executionDone.poke(false.B); d.clock.step(3)
      d.io.exponent.expect(switched(words(1)(1)).U)
      for (_ <- 0 until 2) {
        d.io.done.expect(true.B); d.io.status.expect(4083.U)
        d.io.continue.poke(true.B); d.clock.step(); d.io.continue.poke(false.B)
      }
    }
  }
}

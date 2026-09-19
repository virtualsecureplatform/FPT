package fpt

import chisel3._
import chisel3.util._

final class KernelInvocation extends Bundle {
  val input = UInt(64.W)
  val keyLow = UInt(64.W)
  val keyHigh = UInt(64.W)
  val keyLow1 = UInt(64.W)
  val keyHigh1 = UInt(64.W)
  val output = UInt(64.W)
}

final class KernelCompletion extends Bundle {
  val status = UInt(32.W)
  val keyBeats = UInt(32.W)
  val keyStarved = UInt(32.W)
  val keyBlocked = UInt(32.W)
  val runCycles = UInt(32.W)
}

/** Two invocation credits, one compute engine, one batch of input buffering.
  * Credits return on completion acknowledgement, not on dispatch. Input DMA
  * for the next descriptor may overlap execution, but never shares core state.
  * A DMA fault is fail-stop until reset: accepted jobs retire as errors without
  * launching another core transaction into a potentially undrained DataMover.
  */
final class KernelChainFrontend(val inputBeats: Int = 632) extends Module {
  require(inputBeats >= 2 && inputBeats * 64 < (1 << 23))
  val io = IO(new Bundle {
    val start = Input(Bool())
    val continue = Input(Bool())
    val arguments = Input(new KernelInvocation)
    val ready = Output(Bool())
    val idle = Output(Bool())
    val done = Output(Bool())
    val completion = Output(new KernelCompletion)
    val faulted = Output(Bool())
    val faultStatus = Output(UInt(32.W))
    val acceptedCount = Output(UInt(32.W))
    val prefetchedCount = Output(UInt(32.W))
    val completedCount = Output(UInt(32.W))
    val launch = Decoupled(new KernelInvocation)
    val executionDone = Input(Bool())
    val executionResult = Input(new KernelCompletion)
    val inputCommand = Decoupled(UInt(104.W))
    val inputData = Flipped(Decoupled(UInt(512.W)))
    val inputStatus = Flipped(Valid(UInt(8.W)))
    val inputError = Input(Bool())
    // Replace only the sequencer's input DataMover, not its serializer.
    val coreInputCommand = Flipped(Decoupled(UInt(104.W)))
    val coreInputData = Decoupled(UInt(512.W))
    val coreInputStatus = Valid(UInt(8.W))
  })
  val jobs = Module(new Queue(new KernelInvocation, 2))
  val results = Module(new Queue(new KernelCompletion, 2))
  // Synchronous inferred RAM; no reset on the payload and no wide mux register
  // bank. A complete input frame is 632 * 64 = 40448 bytes for n=630, b=16.
  val payload = Module(new Queue(UInt(512.W), inputBeats, useSyncReadMem = true))
  val outstanding = RegInit(0.U(2.W))
  val active = RegInit(false.B)
  val fault = RegInit(false.B)
  val faultStatus = RegInit(0.U(32.W))
  val commandSent = RegInit(false.B)
  val dataComplete = RegInit(false.B)
  val statusComplete = RegInit(false.B)
  val received = RegInit(0.U(log2Ceil(inputBeats).W))
  val delivered = RegInit(0.U(log2Ceil(inputBeats + 1).W))
  val inputAuthorized = RegInit(false.B)
  val acceptedCount = RegInit(0.U(32.W))
  val prefetchedCount = RegInit(0.U(32.W))
  val completedCount = RegInit(0.U(32.W))
  val prefetchCounted = RegInit(false.B)
  io.acceptedCount := acceptedCount
  io.prefetchedCount := prefetchedCount
  io.completedCount := completedCount
  io.faultStatus := faultStatus

  val inputFailure = io.inputError || (io.inputStatus.valid && !io.inputStatus.bits(7))
  val inputFailureStatus = Cat(0.U(20.W),
    Mux(io.inputStatus.valid, io.inputStatus.bits, 255.U(8.W)), 1.U(3.W), true.B)
  val failNow = fault || inputFailure || (io.executionDone && io.executionResult.status(0))
  when(!fault && (inputFailure || (io.executionDone && io.executionResult.status(0)))) {
    fault := true.B
    faultStatus := Mux(inputFailure, inputFailureStatus, io.executionResult.status)
  }
  io.faulted := fault
  results.io.deq.ready := io.continue
  io.done := results.io.deq.valid
  io.completion := results.io.deq.bits
  io.idle := outstanding === 0.U && !active
  jobs.io.enq.valid := io.start && outstanding < 2.U && !failNow
  jobs.io.enq.bits := io.arguments
  io.ready := jobs.io.enq.fire
  when(jobs.io.enq.fire) { acceptedCount := acceptedCount + 1.U }
  when(jobs.io.enq.fire =/= results.io.deq.fire) {
    outstanding := Mux(jobs.io.enq.fire, outstanding + 1.U, outstanding - 1.U)
  }

  io.inputCommand.valid := jobs.io.deq.valid && !commandSent && !failNow
  io.inputCommand.bits := Cat(0.U(8.W), jobs.io.deq.bits.input,
    false.B, true.B, 0.U(6.W), true.B, (inputBeats * 64).U(23.W))
  when(io.inputCommand.fire) { commandSent := true.B }
  payload.io.enq.valid := io.inputData.valid && commandSent && !dataComplete && !failNow
  payload.io.enq.bits := io.inputData.bits
  io.inputData.ready := payload.io.enq.ready && commandSent && !dataComplete && !failNow
  when(payload.io.enq.fire) {
    when(received === (inputBeats - 1).U) { dataComplete := true.B }
      .otherwise { received := received + 1.U }
  }
  when(io.inputStatus.valid && (commandSent || io.inputCommand.fire)) { statusComplete := true.B }
  val discardFailed = failNow && !active && jobs.io.deq.valid && results.io.enq.ready
  when(dataComplete && statusComplete && active && !prefetchCounted && !failNow) {
    prefetchedCount := prefetchedCount + 1.U
    prefetchCounted := true.B
  }
  io.launch.valid := jobs.io.deq.valid && commandSent && dataComplete &&
    statusComplete && !active && !failNow && results.io.enq.ready
  io.launch.bits := jobs.io.deq.bits
  jobs.io.deq.ready := io.launch.fire || discardFailed
  when(jobs.io.deq.fire) {
    commandSent := false.B
    dataComplete := false.B
    statusComplete := false.B
    received := 0.U
    prefetchCounted := false.B
  }
  when(io.launch.fire) {
    active := true.B
    delivered := 0.U
    inputAuthorized := false.B
  }
  io.coreInputCommand.ready := active && !inputAuthorized
  when(io.coreInputCommand.fire) { inputAuthorized := true.B }
  io.coreInputStatus.valid := RegNext(io.coreInputCommand.fire, false.B)
  io.coreInputStatus.bits := 128.U // The complete prefetched frame already passed DMA status.
  io.coreInputData.valid := payload.io.deq.valid && active && inputAuthorized && delivered < inputBeats.U
  io.coreInputData.bits := payload.io.deq.bits
  payload.io.deq.ready := io.coreInputData.ready && active && inputAuthorized && delivered < inputBeats.U
  when(io.coreInputData.fire) { delivered := delivered + 1.U }

  results.io.enq.valid := io.executionDone || discardFailed
  when(results.io.enq.fire) { completedCount := completedCount + 1.U }
  results.io.enq.bits := io.executionResult
  when(discardFailed) { results.io.enq.bits := 0.U.asTypeOf(new KernelCompletion) }
  when(failNow) {
    results.io.enq.bits.status := Mux(fault, faultStatus,
      Mux(inputFailure, inputFailureStatus, io.executionResult.status))
  }
  when(io.executionDone) {
    assert(active, "completion without active chained invocation")
    assert(results.io.enq.ready, "completion credit lost")
    when(!failNow) { assert(delivered === inputBeats.U, "completion before full input delivery") }
    active := false.B
  }
  assert(outstanding <= 2.U, "chained invocation credit overflow")
}

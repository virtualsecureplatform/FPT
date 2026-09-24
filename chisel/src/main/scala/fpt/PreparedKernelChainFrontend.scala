package fpt

import chisel3._
import chisel3.util._

/** The raw FIFO is only two beats deep: complete batches live in the core's
  * prepared exponent banks instead. Invocation credits and result snapshots
  * are independent of bank lifetime, so delayed host acknowledgments are safe.
  */
final class PreparedKernelChainFrontend(val inputBeats: Int = 632, val bufferedOutput: Boolean = false) extends Module {
  require(inputBeats >= 2 && inputBeats * 64 < (1 << 23))
  val io = IO(new Bundle {
    val launchId = if (bufferedOutput) Some(Output(Bool())) else None
    val completionId = if (bufferedOutput) Some(Input(Bool())) else None
    val executionReleased = if (bufferedOutput) Some(Input(Bool())) else None
    val start = Input(Bool())
    val continue = Input(Bool())
    val arguments = Input(new KernelInvocation)
    val ready = Output(Bool())
    val idle = Output(Bool())
    val done = Output(Bool())
    val completion = Output(new KernelCompletion)
    val completionStages = Output(Vec(5, UInt(32.W)))
    val faulted = Output(Bool())
    val faultStatus = Output(UInt(32.W))
    val acceptedCount = Output(UInt(32.W))
    val prefetchedCount = Output(UInt(32.W))
    val completedCount = Output(UInt(32.W))
    val launch = Decoupled(new KernelInvocation)
    val launchBank = Output(Bool())
    val executionDone = Input(Bool())
    val executionResult = Input(new KernelCompletion)
    val executionStages = Input(Vec(5, UInt(32.W)))
    val inputCommand = Decoupled(UInt(104.W))
    val inputData = Flipped(Decoupled(UInt(512.W)))
    val inputStatus = Flipped(Valid(UInt(8.W)))
    val inputError = Input(Bool())
    val prepareStart = Decoupled(Bool())
    val prepareWord = Decoupled(UInt(32.W))
    val prepareDone = Flipped(Valid(Bool()))
    val releaseBank = Valid(Bool())
  })
  val jobs = Module(new Queue(new KernelInvocation, 2))
  val results = Module(new Queue(new Bundle {
    val result = new KernelCompletion
    val stages = Vec(5, UInt(32.W))
  }, 2))
  val payload = Module(new Queue(UInt(512.W), 2))
  val occupied = RegInit(VecInit(Seq.fill(2)(false.B)))
  val active = RegInit(false.B)
  val activeBank = RegInit(false.B)
  val launchId = RegInit(false.B)
  val activeId = Reg(Bool())
  val completionId = RegInit(false.B)
  val inFlight = RegInit(0.U(2.W))
  io.launchId.foreach(_ := launchId)
  val bank = RegInit(false.B)
  val preparing = RegInit(false.B)
  val commandSent = RegInit(false.B)
  val prepared = RegInit(false.B)
  val statusComplete = RegInit(false.B)
  val received = RegInit(0.U(log2Ceil(inputBeats + 1).W))
  val delivered = RegInit(0.U(log2Ceil(inputBeats * 16 + 1).W))
  val lane = RegInit(0.U(4.W))
  val outstanding = RegInit(0.U(2.W))
  val fault = RegInit(false.B)
  val faultStatus = RegInit(0.U(32.W))
  val accepted = RegInit(0.U(32.W))
  val prefetched = RegInit(0.U(32.W))
  val completed = RegInit(0.U(32.W))
  val counted = RegInit(false.B)
  val inputFailure = io.inputError || (io.inputStatus.valid && !io.inputStatus.bits(7))
  val inputFailureStatus = Cat(0.U(20.W),
    Mux(io.inputStatus.valid, io.inputStatus.bits, 255.U(8.W)), 1.U(3.W), true.B)
  val failNow = fault || inputFailure || (io.executionDone && io.executionResult.status(0))
  val currentFaultStatus = Mux(fault, faultStatus,
    Mux(inputFailure, inputFailureStatus, io.executionResult.status))
  when(!fault && failNow) { fault := true.B; faultStatus := currentFaultStatus }
  io.faulted := fault
  io.faultStatus := faultStatus
  io.acceptedCount := accepted
  io.prefetchedCount := prefetched
  io.completedCount := completed
  jobs.io.enq.valid := io.start && outstanding < 2.U && !failNow
  jobs.io.enq.bits := io.arguments
  io.ready := jobs.io.enq.fire
  results.io.deq.ready := io.continue
  io.done := results.io.deq.valid
  io.completion := results.io.deq.bits.result
  io.completionStages := results.io.deq.bits.stages
  io.idle := outstanding === 0.U && !active
  when(jobs.io.enq.fire) { accepted := accepted + 1.U }
  when(jobs.io.enq.fire =/= results.io.deq.fire) {
    outstanding := Mux(jobs.io.enq.fire, outstanding + 1.U, outstanding - 1.U)
  }
  val freeBank = occupied(0)
  io.prepareStart.valid := jobs.io.deq.valid && !preparing && !occupied.asUInt.andR && !failNow
  io.prepareStart.bits := freeBank
  when(io.prepareStart.fire) {
    bank := freeBank
    occupied(freeBank) := true.B
    preparing := true.B
  }
  io.inputCommand.valid := preparing && !commandSent && !failNow
  io.inputCommand.bits := Cat(0.U(8.W), jobs.io.deq.bits.input,
    false.B, true.B, 0.U(6.W), true.B, (inputBeats * 64).U(23.W))
  when(io.inputCommand.fire) { commandSent := true.B }
  payload.io.enq.valid := io.inputData.valid && commandSent && received < inputBeats.U && !failNow
  payload.io.enq.bits := io.inputData.bits
  io.inputData.ready := payload.io.enq.ready && commandSent && received < inputBeats.U && !failNow
  when(payload.io.enq.fire) { received := received + 1.U }
  io.prepareWord.valid := payload.io.deq.valid && preparing && delivered < (inputBeats * 16).U && !failNow
  io.prepareWord.bits := payload.io.deq.bits.asTypeOf(Vec(16, UInt(32.W)))(lane)
  payload.io.deq.ready := io.prepareWord.fire && lane === 15.U
  when(io.prepareWord.fire) { lane := lane + 1.U; delivered := delivered + 1.U }
  // delivered counts words issued into the physical link, not destination
  // consumption. Completion returns only after its two registered stages drain.
  // A fault may retire this job before the last in-flight token arrives.
  when(io.prepareDone.valid && !failNow) {
    assert(preparing && io.prepareDone.bits === bank, "unexpected prepared bank")
    assert(delivered === (inputBeats * 16).U,
      "prepared before complete frame")
    assert(!prepared, "duplicate prepared completion")
    prepared := true.B
  }
  when(io.inputStatus.valid && (commandSent || io.inputCommand.fire)) { statusComplete := true.B }
  when(prepared && statusComplete && active && !counted && !failNow) {
    prefetched := prefetched + 1.U
    counted := true.B
  }
  io.launch.valid := jobs.io.deq.valid && prepared && statusComplete && !active && !failNow && results.io.enq.ready
  io.launch.bits := jobs.io.deq.bits
  io.launchBank := bank
  val discard = failNow && !active && (if (bufferedOutput) inFlight === 0.U else true.B) &&
    jobs.io.deq.valid && results.io.enq.ready
  jobs.io.deq.ready := io.launch.fire || discard
  when(jobs.io.deq.fire) {
    preparing := false.B
    commandSent := false.B
    prepared := false.B
    statusComplete := false.B
    received := 0.U
    delivered := 0.U
    lane := 0.U
    counted := false.B
  }
  when(io.launch.fire) { active := true.B; activeBank := bank; activeId := launchId; launchId := !launchId }
  // Failed executions may still have live core reads. Freeze their bank until
  // reset rather than releasing storage beneath an aborting computation.
  val executionReleased = if (bufferedOutput) io.executionReleased.get else io.executionDone
  io.releaseBank.valid := executionReleased && !failNow
  io.releaseBank.bits := activeBank
  when(executionReleased) {
    assert(active, "execution release without an active invocation")
    when(!failNow) { occupied(activeBank) := false.B }
    active := false.B
  }
  if (bufferedOutput) {
    when(io.launch.fire =/= io.executionDone) {
      inFlight := Mux(io.launch.fire, inFlight + 1.U, inFlight - 1.U)
    }
    when(io.executionDone) {
      assert(inFlight =/= 0.U && results.io.enq.ready, "completion without invocation credit")
      assert(io.completionId.get === completionId, "out-of-order output completion")
      completionId := !completionId
      when(failNow && active && io.completionId.get === activeId) { active := false.B }
    }
    assert(inFlight <= 2.U)
  } else {
    when(io.executionDone) { assert(active && results.io.enq.ready, "completion without invocation credit") }
  }
  results.io.enq.valid := io.executionDone || discard
  results.io.enq.bits.result := Mux(discard, 0.U.asTypeOf(new KernelCompletion), io.executionResult)
  results.io.enq.bits.stages := Mux(discard, 0.U.asTypeOf(io.executionStages), io.executionStages)
  when(failNow) { results.io.enq.bits.result.status := currentFaultStatus }
  when(results.io.enq.fire) { completed := completed + 1.U }
  assert(outstanding <= 2.U, "prepared invocation credit overflow")
}

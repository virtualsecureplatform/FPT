package fpt

import chisel3._
import chisel3.util._

/** Two invocation records, but only one snapshot/output DMA owner. No key
  * transaction survives execution release; output transactions may do so.
  */
final class BufferedOutputRetirement(val outputBytes: Int = 65600) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Valid(new Bundle {
      val id = Bool()
      val pointer = UInt(64.W)
      val preparationCycles = UInt(32.W)
    }))
    val ready = Output(Bool())
    val runStart = Input(Bool())
    val computeDone = Input(Bool())
    val captured = Input(Bool())
    val executionReleased = Input(Bool())
    val executionStats = Input(new KernelCompletion)
    val failure = Flipped(Valid(UInt(32.W)))
    val faulted = Output(Bool())
    val command = Decoupled(UInt(104.W))
    val input = Flipped(Decoupled(new Bundle {
      val data = UInt(128.W)
      val count = UInt(3.W)
      val last = Bool()
    }))
    val output = Decoupled(new Bundle {
      val data = UInt(128.W)
      val keep = UInt(16.W)
      val last = Bool()
    })
    val status = Flipped(Valid(UInt(8.W)))
    val error = Input(Bool())
    val retire = Output(Bool())
    val completion = Valid(new Bundle {
      val id = Bool()
      val result = new KernelCompletion
      val stages = Vec(5, UInt(32.W))
    })
  })
  class Record extends Bundle {
    val pointer = UInt(64.W)
    val preparation = UInt(32.W)
    val dispatch = UInt(32.W)
    val run = UInt(32.W)
    val compute = UInt(32.W)
    val last = UInt(32.W)
    val stats = new KernelCompletion
  }
  val records = Reg(Vec(2, new Record))
  val occupied = RegInit(VecInit(Seq.fill(2)(false.B)))
  val released = RegInit(VecInit(Seq.fill(2)(false.B)))
  val started = RegInit(VecInit(Seq.fill(2)(false.B)))
  val computed = RegInit(VecInit(Seq.fill(2)(false.B)))
  val produced = RegInit(VecInit(Seq.fill(2)(false.B)))
  val lastCompletedId = RegInit(false.B)
  val lastCompletionTime = Reg(UInt(32.W))
  val hasCompleted = RegInit(false.B)
  val current = Reg(Bool())
  val oldest = RegInit(false.B)
  val clockCount = RegInit(0.U(32.W))
  clockCount := clockCount + 1.U
  val outputActive = RegInit(false.B)
  val outputId = Reg(Bool())
  val commandSent = RegInit(false.B)
  val lastSeen = RegInit(false.B)
  val statusSeen = RegInit(false.B)
  val fault = RegInit(false.B)
  val faultStatus = Reg(UInt(32.W))
  val dmaEvent = Module(new BufferedDmaEvent)
  dmaEvent.io.status := io.status
  dmaEvent.io.error := io.error
  val status = dmaEvent.io.sampled
  val outputFailure = dmaEvent.io.sampledError || (status.valid && !status.bits(7))
  val failNow = fault || io.failure.valid || outputFailure
  val errorStatus = Mux(io.failure.valid, io.failure.bits,
    Cat(0.U(20.W), Mux(status.valid, status.bits, 255.U(8.W)), 6.U(3.W), true.B))
  when(!fault && failNow) { fault := true.B; faultStatus := errorStatus }
  def localStop(name: String): Bool = {
    val stop = Module(new PhysicalControlRegister(1)); stop.suggestName(name)
    stop.io.clock := clock; stop.io.reset := reset.asBool
    stop.io.inputData := failNow
    stop.io.outputData(0)
  }
  val stopAccept = localStop("stopAccept")
  val stopRecords = Seq.tabulate(2)(i => localStop(s"stopRecord$i"))
  val stopStream = localStop("stopStream")
  val stopRetire = localStop("stopRetire")
  io.faulted := fault
  io.ready := !occupied(io.launch.bits.id) && !stopAccept
  when(io.launch.valid) {
    assert(io.ready, "output invocation record reused while occupied")
    current := io.launch.bits.id
    occupied(io.launch.bits.id) := true.B
    released(io.launch.bits.id) := false.B
    started(io.launch.bits.id) := false.B
    computed(io.launch.bits.id) := false.B
    produced(io.launch.bits.id) := false.B
    records(io.launch.bits.id).pointer := io.launch.bits.pointer
    records(io.launch.bits.id).preparation := io.launch.bits.preparationCycles
    records(io.launch.bits.id).dispatch := clockCount
    records(io.launch.bits.id).run := clockCount
    records(io.launch.bits.id).compute := clockCount
    records(io.launch.bits.id).last := clockCount
    records(io.launch.bits.id).stats := 0.U.asTypeOf(new KernelCompletion)
  }
  for (i <- 0 until 2) {
    when(current === i.U && !stopRecords(i)) {
      when(io.runStart) {
        assert(occupied(i)); records(i).run := clockCount; started(i) := true.B
      }
      when(io.computeDone) {
        assert(occupied(i)); records(i).compute := clockCount; computed(i) := true.B
      }
      when(io.executionReleased) {
        assert(occupied(i) && !released(i))
        released(i) := true.B
        records(i).stats := io.executionStats
      }
    }
  }
  when(io.captured && !stopRetire) {
    assert(!outputActive && occupied(current), "snapshot overwrote an output owner")
    outputActive := true.B
    outputId := current
    commandSent := false.B
    lastSeen := false.B
    statusSeen := false.B
  }
  io.command.valid := outputActive && !commandSent && !stopAccept
  io.command.bits := Cat(0.U(8.W), records(outputId).pointer,
    false.B, true.B, 0.U(6.W), true.B, outputBytes.U(23.W))
  when(io.command.fire) { commandSent := true.B }
  val packer = Module(new ResultBeatCompactor)
  val inputSlice = Module(new BufferedOutputSlice(chiselTypeOf(io.input.bits)))
  val outputSlice = Module(new BufferedOutputSlice(chiselTypeOf(io.output.bits)))
  val authorized = outputActive && commandSent && !stopStream
  inputSlice.io.input.valid := io.input.valid && authorized
  inputSlice.io.input.bits := io.input.bits
  io.input.ready := inputSlice.io.input.ready && authorized
  packer.io.input.valid := inputSlice.io.output.valid && authorized
  packer.io.input.bits := inputSlice.io.output.bits
  inputSlice.io.output.ready := packer.io.input.ready && authorized
  outputSlice.io.input.valid := packer.io.output.valid && authorized
  outputSlice.io.input.bits := packer.io.output.bits
  packer.io.output.ready := outputSlice.io.input.ready && authorized
  io.output.valid := outputSlice.io.output.valid && authorized
  io.output.bits := outputSlice.io.output.bits
  outputSlice.io.output.ready := io.output.ready && authorized
  when(io.output.fire && io.output.bits.last) {
    assert(!lastSeen)
    lastSeen := true.B
    produced(outputId) := true.B
    records(outputId).last := clockCount
  }
  when(status.valid && !failNow) {
    assert(outputActive && (commandSent || io.command.fire), "unsolicited output status")
    assert(!statusSeen, "duplicate output status")
    statusSeen := true.B
  }
  val success = outputActive && lastSeen && statusSeen && released(outputId) && !failNow
  val failed = fault && occupied(oldest)
  io.completion.valid := success || failed
  io.completion.bits.id := oldest
  // Keep the latest completed counters readable even after ap_continue pops
  // the frontend queue. While busy, show the oldest unretired invocation.
  val visibleId = Mux(occupied.asUInt.orR, oldest, lastCompletedId)
  val visible = occupied.asUInt.orR || hasCompleted
  val end = Mux(occupied.asUInt.orR, clockCount, lastCompletionTime)
  val record = records(visibleId)
  val runEnd = Mux(produced(visibleId), record.last, end)
  val computeEnd = Mux(computed(visibleId), record.compute, end)
  val setupEnd = Mux(started(visibleId), record.run, end)
  io.completion.bits.result := record.stats
  when(occupied(visibleId) && !released(visibleId) && visibleId === current) {
    io.completion.bits.result := io.executionStats
  }
  io.completion.bits.result.status := Mux(fault, faultStatus, 0.U)
  io.completion.bits.result.runCycles := Mux(started(visibleId), runEnd - record.run, 0.U)
  io.completion.bits.stages(0) := record.preparation
  io.completion.bits.stages(1) := setupEnd - record.dispatch
  io.completion.bits.stages(2) := Mux(started(visibleId), computeEnd - record.run, 0.U)
  io.completion.bits.stages(3) := Mux(computed(visibleId), runEnd - record.compute, 0.U)
  io.completion.bits.stages(4) := Mux(produced(visibleId), end - record.last, 0.U)
  when(!visible) { io.completion.bits.result := 0.U.asTypeOf(new KernelCompletion) }
  when(fault || !visible) {
    io.completion.bits.result.runCycles := 0.U
    io.completion.bits.stages := 0.U.asTypeOf(io.completion.bits.stages)
  }
  io.retire := success
  when(io.completion.valid) {
    assert(occupied(oldest), "completion without invocation")
    when(success) { assert(outputId === oldest); outputActive := false.B }
    occupied(oldest) := false.B
    lastCompletedId := oldest
    lastCompletionTime := clockCount
    hasCompleted := true.B
    oldest := !oldest
  }
}

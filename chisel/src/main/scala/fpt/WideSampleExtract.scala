package fpt

import chisel3._
import chisel3.util._

private final class WideExtractSelected(width: Int, lanes: Int) extends Bundle {
  val data = Vec(lanes, UInt(width.W))
  val last = Bool()
}

/** Four coefficients per beat without replicating the polynomial store.
  * Emit a(0) separately, followed by descending, aligned groups. The final
  * group's last lane is b(0), not -a(0). A downstream compactor removes the
  * three unused lanes of the first beat across context boundaries.
  */
final class WideSampleExtractIndexZero(val config: SampleExtractConfig) extends Module {
  require(config.outputLanes == 4)
  private val lanes = config.outputLanes
  private val width = config.torusWidth
  private val groups = config.polynomialSize / lanes
  private val groupsPerWord = config.lanes / lanes
  val io = IO(new Bundle {
    val inputStart = Input(Bool())
    val inputStartReady = Output(Bool())
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val inputA = Input(Vec(config.lanes, UInt(width.W)))
    val inputB = Input(Vec(config.lanes, UInt(width.W)))
    val inputDone = Output(Bool())
    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val output = Output(UInt((width * lanes).W))
    val outputCount = Output(UInt(log2Ceil(lanes + 1).W))
    val outputLast = Output(Bool())
    val done = Output(Bool())
    val busy = Output(Bool())
  })
  private class Beat extends Bundle {
    val data = UInt((width * lanes).W)
    val count = UInt(log2Ceil(lanes + 1).W)
    val last = Bool()
  }
  val idle :: collect :: emit :: Nil = Enum(3)
  val state = RegInit(idle)
  val maskMemory = SyncReadMem(config.inputBeats, Vec(config.lanes, UInt(width.W)))
  val inputBeat = RegInit(0.U(config.beatWidth.W))
  val firstMask = Reg(UInt(width.W))
  val body = Reg(UInt(width.W))
  val firstQueued = RegInit(false.B)
  val nextGroup = RegInit(0.U(TransformUtil.counterWidth(groups + 1).W))
  val pending = RegInit(false.B)
  val pendingGroup = Reg(UInt(TransformUtil.counterWidth(groupsPerWord).W))
  val pendingLast = Reg(Bool())
  private val selected = Module(new Queue(new WideExtractSelected(width, lanes), 2, pipe = true))
  private val results = Module(new Queue(new Beat, 2, pipe = true))

  io.inputStartReady := state === idle
  io.inputReady := state === collect
  io.busy := state =/= idle
  io.inputDone := io.inputValid && io.inputReady && inputBeat === (config.inputBeats - 1).U
  when(io.inputStart) { assert(io.inputStartReady) }
  when(io.inputStart && io.inputStartReady) {
    state := collect
    inputBeat := 0.U
    firstQueued := false.B
    nextGroup := 0.U
    pending := false.B
  }
  when(io.inputValid && io.inputReady) {
    maskMemory.write(inputBeat, io.inputA)
    when(inputBeat === 0.U) { firstMask := io.inputA(0); body := io.inputB(0) }
    when(io.inputDone) { state := emit }.otherwise { inputBeat := inputBeat + 1.U }
  }
  val occupied = selected.io.count +& pending.asUInt - selected.io.deq.fire.asUInt
  val issue = state === emit && firstQueued && nextGroup < groups.U && occupied < 2.U
  val reverseGroup = (groups - 1).U - nextGroup
  val readWord = maskMemory.read(reverseGroup / groupsPerWord.U, issue)
  pending := issue
  when(issue) {
    pendingGroup := reverseGroup % groupsPerWord.U
    pendingLast := nextGroup === (groups - 1).U
    nextGroup := nextGroup + 1.U
  }
  val wordGroups = readWord.asUInt.asTypeOf(Vec(groupsPerWord, Vec(lanes, UInt(width.W))))
  val chosen = if (groupsPerWord == 1) wordGroups(0) else wordGroups(pendingGroup)
  selected.io.enq.valid := pending
  selected.io.enq.bits.last := pendingLast
  for (lane <- 0 until lanes) { selected.io.enq.bits.data(lane) := chosen(lanes - 1 - lane) }
  when(pending) { assert(selected.io.enq.ready, "wide extraction response overflow") }

  val first = state === emit && !firstQueued
  val values = Wire(Vec(lanes, UInt(width.W)))
  for (lane <- 0 until lanes) {
    values(lane) := (0.U(width.W) - selected.io.deq.bits.data(lane))
  }
  when(selected.io.deq.bits.last) { values(lanes - 1) := body }
  results.io.enq.valid := first || selected.io.deq.valid
  results.io.enq.bits.data := Mux(first, firstMask.pad(width * lanes), values.asUInt)
  results.io.enq.bits.count := Mux(first, 1.U, lanes.U)
  results.io.enq.bits.last := !first && selected.io.deq.bits.last
  selected.io.deq.ready := !first && results.io.enq.ready
  when(first && results.io.enq.ready) { firstQueued := true.B }
  io.outputValid := results.io.deq.valid
  results.io.deq.ready := io.outputReady
  io.output := results.io.deq.bits.data
  io.outputCount := results.io.deq.bits.count
  io.outputLast := results.io.deq.bits.last
  io.done := results.io.deq.fire && results.io.deq.bits.last
  when(io.done) { state := idle }
}

/** Compact counted words across contexts; only the final batch beat may
  * have partial byte enables. Ready depends on registered occupancy, never
  * on downstream ready, preserving a physical cut before the DataMover.
  */
final class ResultBeatCompactor(val lanes: Int = 4, val width: Int = 32) extends Module {
  require(lanes == 4 && width % 8 == 0)
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new Bundle {
      val data = UInt((lanes * width).W)
      val count = UInt(log2Ceil(lanes + 1).W)
      val last = Bool()
    }))
    val output = Decoupled(new Bundle {
      val data = UInt((lanes * width).W)
      val keep = UInt((lanes * width / 8).W)
      val last = Bool()
    })
  })
  // Three beats accommodate a partial-word residue plus a full incoming
  // beat even without relying on a simultaneous downstream transfer.
  val capacity = 3 * lanes
  val data = Reg(UInt((capacity * width).W))
  val count = RegInit(0.U(log2Ceil(capacity + 1).W))
  val last = RegInit(false.B)
  io.input.ready := count <= (capacity - lanes).U && !last
  io.output.valid := count >= lanes.U || (last && count =/= 0.U)
  io.output.bits.data := data(lanes * width - 1, 0)
  val outputCount = Mux(count >= lanes.U, lanes.U, count)
  io.output.bits.keep := ((1.U((lanes * width / 8 + 1).W) << (outputCount * (width / 8).U)) - 1.U)
  io.output.bits.last := last && count <= lanes.U
  val remaining = count - Mux(io.output.fire, outputCount, 0.U)
  // Mask stale upper storage, particularly on the first transaction after reset.
  val base = Mux(io.output.fire, data >> (lanes * width), data)
  val baseMask = (1.U((capacity * width + 1).W) << (remaining * width.U)) - 1.U
  val incomingMask = (1.U((lanes * width + 1).W) << (io.input.bits.count * width.U)) - 1.U
  when(io.input.fire) {
    assert(io.input.bits.count >= 1.U && io.input.bits.count <= lanes.U)
    data := (base & baseMask) | ((io.input.bits.data & incomingMask) << (remaining * width.U))
    count := remaining + io.input.bits.count
    last := io.input.bits.last
  }.elsewhen(io.output.fire) {
    data := base
    count := remaining
    when(io.output.bits.last) { last := false.B }
  }
}

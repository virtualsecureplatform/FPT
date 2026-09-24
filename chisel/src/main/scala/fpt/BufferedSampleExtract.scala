package fpt

import chisel3._
import chisel3.util._

final class BufferedOutputOwnership extends Bundle {
  val captured = Output(Bool())
  val retire = Input(Bool())
}

private[fpt] final class SnapshotMaskBank(val rows: Int) extends Module {
  val io = IO(new Bundle {
    val write = Flipped(Valid(new Bundle {
      val address = UInt(TransformUtil.counterWidth(rows).W)
      val data = UInt(32.W)
    }))
    val read = Flipped(Valid(UInt(TransformUtil.counterWidth(rows).W)))
    val data = Output(UInt(32.W))
  })
  val snapshotMaskMemory = SyncReadMem(rows, UInt(32.W))
  assert(!(io.write.valid && io.read.valid), "snapshot ownership mixed capture and emission")
  when(io.write.valid) { snapshotMaskMemory.write(io.write.bits.address, io.write.bits.data) }
  io.data := snapshotMaskMemory.read(io.read.bits, io.read.valid)
}

/** One complete batch snapshot. Capture and emission never share ownership.
  * The upstream drain queue holds each wide beat through its four slices;
  * consequently no additional wide payload holding register is required.
  */
final class BufferedSampleExtract(val contexts: Int = 16, val points: Int = 1024)
    extends Module {
  require(contexts > 0 && isPow2(contexts) && points >= 64 && isPow2(points))
  private val cw = TransformUtil.counterWidth(contexts)
  private val rows = contexts * points / 16
  private val groups = points / 4
  val io = IO(new Bundle {
    val start = Flipped(Decoupled(Bool()))
    val contextStart = Flipped(Decoupled(UInt(cw.W)))
    val input = Flipped(Decoupled(new Bundle {
      val a = Vec(64, UInt(32.W))
      val b = Vec(64, UInt(32.W))
    }))
    val contextDone = Output(Bool())
    val captured = Output(Bool())
    val retire = Input(Bool())
    val occupied = Output(Bool())
    val output = Decoupled(new Bundle {
      val data = UInt(128.W)
      val count = UInt(3.W)
      val context = UInt(cw.W)
      val last = Bool()
    })
  })
  val empty :: capture :: header :: headerWait :: first :: emit :: waitRetire :: Nil = Enum(7)
  val state = RegInit(empty)
  val maskBanks = Seq.fill(16)(Module(new SnapshotMaskBank(rows)))
  maskBanks.zipWithIndex.foreach { case (m, i) => m.suggestName(s"snapshotMaskBank$i") }
  val bodies = SyncReadMem(contexts, UInt(32.W))
  val context = RegInit(0.U(cw.W))
  val collecting = RegInit(false.B)
  val beat = RegInit(0.U(TransformUtil.counterWidth(points / 64).W))
  val slice = RegInit(0.U(2.W))
  val group = RegInit(0.U(TransformUtil.counterWidth(groups + 1).W))
  val body = Reg(UInt(32.W))
  val firstMask = Reg(UInt(32.W))
  val pending = RegInit(false.B)
  val pendingSelect = Reg(UInt(2.W))
  val pendingLast = Reg(Bool())
  val selected = Module(new Queue(new Bundle {
    val data = Vec(4, UInt(32.W))
    val first = Bool()
    val contextLast = Bool()
    val context = UInt(cw.W)
  }, 2, pipe = true))
  val results = Module(new Queue(chiselTypeOf(io.output.bits), 2, pipe = true))
  io.output <> results.io.deq
  io.start.ready := state === empty
  io.occupied := state =/= empty
  io.contextStart.ready := state === capture && !collecting
  io.input.ready := state === capture && collecting && slice === 3.U
  io.contextDone := io.input.fire && beat === (points / 64 - 1).U
  io.captured := io.contextDone && context === (contexts - 1).U
  when(io.start.fire) { state := capture; context := 0.U }
  when(io.contextStart.fire) {
    assert(io.contextStart.bits === context, "snapshot contexts must be captured in order")
    collecting := true.B
    beat := 0.U
    slice := 0.U
  }
  val captureSlice = state === capture && collecting && io.input.valid
  val writeAddress = context * (points / 16).U + beat * 4.U + slice
  for (i <- 0 until 16) {
    maskBanks(i).io.write.valid := captureSlice
    maskBanks(i).io.write.bits.address := writeAddress
    maskBanks(i).io.write.bits.data := io.input.bits.a(Cat(slice, i.U(4.W)))
  }
  when(captureSlice) {
    when(beat === 0.U && slice === 0.U) { bodies.write(context, io.input.bits.b(0)) }
    slice := slice + 1.U
    when(io.input.fire) { beat := beat + 1.U }
  }
  // A partial capture borrows the stable head of the accumulator drain queue.
  val held = RegNext(captureSlice && !io.input.ready, false.B)
  val heldPayload = RegEnable(io.input.bits.asUInt, captureSlice && !io.input.ready)
  when(held) {
    assert(io.input.valid && io.input.bits.asUInt === heldPayload,
      "accumulator drain changed a partially captured beat")
  }
  when(io.contextDone) {
    collecting := false.B
    when(context === (contexts - 1).U) { context := 0.U; state := header }
      .otherwise { context := context + 1.U }
  }

  val reserve = selected.io.count +& pending.asUInt - selected.io.deq.fire.asUInt
  val issue = state === emit && group < groups.U && reserve < 2.U
  val reverse = (groups - 1).U - group
  val readHeader = state === header
  val readAddress = context * (points / 16).U + Mux(readHeader, 0.U, reverse >> 2)
  maskBanks.foreach { bank =>
    bank.io.read.valid := readHeader || issue
    bank.io.read.bits := readAddress
  }
  val words = VecInit(maskBanks.map(_.io.data))
  val bodyRead = bodies.read(context, readHeader)
  pending := issue
  when(readHeader) { state := headerWait }
  when(state === headerWait) {
    firstMask := words(0)
    body := bodyRead
    state := first
    group := 0.U
  }
  when(issue) {
    pendingSelect := reverse(1, 0)
    pendingLast := group === (groups - 1).U
    group := group + 1.U
  }
  selected.io.enq.valid := state === first || pending
  for (i <- 0 until 4) {
    selected.io.enq.bits.data(i) := words(Cat(pendingSelect, (3 - i).U(2.W)))
  }
  when(pendingLast) { selected.io.enq.bits.data(3) := body }
  when(state === first) { selected.io.enq.bits.data := VecInit(Seq(firstMask, 0.U, 0.U, 0.U)) }
  selected.io.enq.bits.first := state === first
  selected.io.enq.bits.contextLast := pending && pendingLast
  selected.io.enq.bits.context := context
  val values = Wire(Vec(4, UInt(32.W)))
  for (i <- 0 until 4) {
    values(i) := Mux(selected.io.deq.bits.first || (i == 3).B && selected.io.deq.bits.contextLast,
      selected.io.deq.bits.data(i), 0.U(32.W) - selected.io.deq.bits.data(i))
  }
  results.io.enq.valid := selected.io.deq.valid
  selected.io.deq.ready := results.io.enq.ready
  results.io.enq.bits.data := values.asUInt
  results.io.enq.bits.count := Mux(selected.io.deq.bits.first, 1.U, 4.U)
  results.io.enq.bits.context := selected.io.deq.bits.context
  results.io.enq.bits.last := selected.io.deq.bits.contextLast && selected.io.deq.bits.context === (contexts - 1).U
  when(pending) { assert(selected.io.enq.ready, "snapshot read response overflow") }
  when(state === first && selected.io.enq.fire) { state := emit }
  when(pending && pendingLast) {
    when(context === (contexts - 1).U) { state := waitRetire }
      .otherwise { context := context + 1.U; state := header }
  }
  when(io.retire) {
    assert(state === waitRetire && !io.output.valid && !selected.io.deq.valid && !pending,
      "snapshot retired before its output drained")
    state := empty
  }
}

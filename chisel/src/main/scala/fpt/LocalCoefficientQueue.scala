package fpt

import chisel3._
import chisel3.util._

/** Same-cycle flow/pipe queue, with independently preserved storage controls. */
private[fpt] final class CoefficientQueueTile(width: Int, entries: Int)
    extends BlackBox with HasBlackBoxInline {
  require(width > 0 && entries >= 2 && isPow2(entries))
  private val p = log2Ceil(entries)
  override def desiredName = s"FptCoefficientQueueTile_${width}_${entries}"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val enqValid = Input(Bool())
    val enqReady = Output(Bool())
    val enqData = Input(UInt(width.W))
    val deqValid = Output(Bool())
    val deqReady = Input(Bool())
    val deqData = Output(UInt(width.W))
    val count = Output(UInt((p + 1).W))
  })
  // Preserve local state, not the payload boundary: unused result bits must
  // remain eligible for pruning and bypass logic for cross-boundary folding.
  setInline(s"$desiredName.sv", s"""module $desiredName (
    |input wire clock, reset, enqValid, deqReady,
    |input wire [${width - 1}:0] enqData,
    |output wire enqReady, deqValid,
    |output wire [${width - 1}:0] deqData,
    |output wire [$p:0] count);
    |(* DONT_TOUCH = "yes", KEEP = "yes", SHREG_EXTRACT = "no" *) reg [${p - 1}:0] enqPointer, deqPointer;
    |(* DONT_TOUCH = "yes", KEEP = "yes", SHREG_EXTRACT = "no" *) reg maybeFull;
    |reg [${width - 1}:0] storage [0:${entries - 1}];
    |wire matching = enqPointer == deqPointer;
    |(* DONT_TOUCH = "yes", KEEP = "yes" *) wire empty = matching && !maybeFull;
    |(* DONT_TOUCH = "yes", KEEP = "yes" *) wire full = matching && maybeFull;
    |assign enqReady = !full || deqReady;
    |assign deqValid = !empty || enqValid;
    |assign deqData = empty ? enqData : storage[deqPointer];
    |(* DONT_TOUCH = "yes", KEEP = "yes" *) wire writeEnable = enqValid && enqReady && !(empty && deqReady);
    |wire readEnable = deqReady && !empty;
    |wire [${p - 1}:0] distance = enqPointer - deqPointer;
    |assign count = matching ? (maybeFull ? ${p + 1}'d$entries : ${p + 1}'d0) : {1'b0, distance};
    |always @(posedge clock) begin
    |  if (reset) begin
    |    enqPointer <= 0; deqPointer <= 0; maybeFull <= 0;
    |  end else begin
    |    if (writeEnable) begin storage[enqPointer] <= enqData; enqPointer <= enqPointer + 1'b1; end
    |    if (readEnable) deqPointer <= deqPointer + 1'b1;
    |    if (writeEnable != readEnable) maybeFull <= writeEnable;
    |  end
    |end
    |endmodule
    |""".stripMargin)
}

final class LocalCoefficientQueue[T <: Data](gen: T, entries: Int, tileBits: Int = 512)
    extends Module {
  require(entries >= 2 && isPow2(entries) && tileBits > 0)
  val io = IO(new QueueIO(gen, entries))
  private val width = gen.getWidth
  private val input = io.enq.bits.asUInt
  private val tiles = (0 until width by tileBits).map { first =>
    val bits = math.min(tileBits, width - first)
    val tile = Module(new CoefficientQueueTile(bits, entries))
    tile.suggestName(s"storageTile_${first / tileBits}")
    tile.io.clock := clock
    tile.io.reset := reset.asBool
    tile.io.enqValid := io.enq.valid
    tile.io.deqReady := io.deq.ready
    tile.io.enqData := input(first + bits - 1, first)
    tile
  }
  io.enq.ready := tiles.head.io.enqReady
  io.deq.valid := tiles.head.io.deqValid
  io.count := tiles.head.io.count
  io.deq.bits := Cat(tiles.reverse.map(_.io.deqData)).asTypeOf(gen)
  tiles.tail.foreach { tile =>
    assert(tile.io.enqReady === tiles.head.io.enqReady, "queue tiles lost ready synchronization")
    assert(tile.io.deqValid === tiles.head.io.deqValid, "queue tiles lost valid synchronization")
    assert(tile.io.count === tiles.head.io.count, "queue tiles lost occupancy synchronization")
  }
}

private[fpt] object CoefficientQueue {
  def apply[T <: Data](gen: T, entries: Int, local: Boolean, name: String): QueueIO[T] = {
    if (local) {
      val queue = Module(new LocalCoefficientQueue(gen, entries))
      queue.suggestName(name)
      queue.io
    } else {
      val queue = Module(new Queue(gen, entries, pipe = true, flow = true))
      queue.suggestName(name)
      queue.io
    }
  }
}

package fpt

import chisel3._
import chisel3.util._

/** Exact constant-polynomial initialization, not a general coefficient stream. */
final class AccumulatorInitDescriptor(config: CmuxCoefficientConfig) extends Bundle {
  val positive = UInt(config.torusWidth.W)
  val negative = UInt(config.torusWidth.W)
  val exponent = UInt(config.exponentWidth.W)
}

/** Four lane-local writeback paths sharing a narrow initialization descriptor.
  * The writeData edge replaces (not follows) the ordinary update commit edge.
  * Only descriptor/counter/mode state is preserved; payload is free to optimize.
  */
private[fpt] final class BankLocalAccumulatorInitGroup(
    config: ReplicatedAccumulatorBanksConfig, firstLane: Int, lanes: Int
) extends BlackBox with HasBlackBoxInline {
  private val c = config.coefficient
  require(c.components == 2 && lanes > 0 && lanes <= 4)
  require(firstLane + lanes <= c.inverseLanes)
  override def desiredName = s"FptBankLocalAccumulatorInit_${c.polynomialSize}_${c.inverseLanes}_${c.torusWidth}_${firstLane}_${lanes}"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val loadStart = Input(Bool())
    val descriptor = Input(new AccumulatorInitDescriptor(c))
    val updateData = Input(UInt((lanes * config.wordWidth).W))
    val writeData = Output(UInt((lanes * config.wordWidth).W))
    val active = Output(Bool())
    val beat = Output(UInt(config.loadBeatWidth.W))
  })
  private val keep = "(* KEEP=\"yes\", DONT_TOUCH=\"yes\", SHREG_EXTRACT=\"no\" *)"
  setInline(s"$desiredName.sv", s"""(* KEEP_HIERARCHY="yes" *) module $desiredName(
    |input wire clock, reset, loadStart,
    |input wire [${c.torusWidth-1}:0] descriptor_positive, descriptor_negative,
    |input wire [${c.exponentWidth-1}:0] descriptor_exponent,
    |input wire [${lanes*config.wordWidth-1}:0] updateData,
    |(* EXTRACT_RESET="no", EXTRACT_ENABLE="no" *) output reg [${lanes*config.wordWidth-1}:0] writeData,
    |output wire active, output wire [${config.loadBeatWidth-1}:0] beat);
    |$keep reg [${c.torusWidth-1}:0] positive, negative;
    |$keep reg [${c.exponentWidth-1}:0] exponent;
    |$keep reg [${config.loadBeatWidth-1}:0] localBeat;
    |$keep reg [${lanes-1}:0] loadActive;
    |assign active = loadActive[0];
    |assign beat = localBeat;
    |always @(posedge clock) begin
    |  if (loadStart) begin
    |    positive <= descriptor_positive; negative <= descriptor_negative;
    |    exponent <= descriptor_exponent;
    |  end
    |  if (reset) begin loadActive <= 0; localBeat <= 0; end
    |  else if (loadStart) begin loadActive <= {${lanes}{1'b1}}; localBeat <= 0; end
    |  else if (active) begin
    |    localBeat <= localBeat + 1'b1;
    |    if (localBeat == ${config.loadBeats-1}) loadActive <= 0;
    |  end
    |end
    |genvar lane;
    |generate for (lane=0; lane<${lanes}; lane=lane+1) begin: laneWriteback
    |  wire [${c.exponentWidth-2}:0] index = localBeat * ${c.inverseLanes} + ${firstLane} + lane;
    |  wire negate = exponent[${c.exponentWidth-1}] ^ (index < exponent[${c.exponentWidth-2}:0]);
    |  wire [${c.torusWidth-1}:0] value = negate ? negative : positive;
    |  always @(posedge clock)
    |    writeData[lane*${config.wordWidth} +: ${config.wordWidth}] <= loadActive[lane]
    |      ? {value, value, ${2*c.torusWidth}'d0}
    |      : updateData[lane*${config.wordWidth} +: ${config.wordWidth}];
    |end endgenerate
    |endmodule
    |""".stripMargin)
}

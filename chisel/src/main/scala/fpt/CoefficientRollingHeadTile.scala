package fpt

import chisel3._
import chisel3.util._

/** Commands capture on the existing final scratch-read edge; snapshots
  * elsewhere still see the old heads on the subsequent update edge.
  */
private[fpt] final class CoefficientRollingHeadTile(
    lanes: Int, width: Int, components: Int
) extends BlackBox with HasBlackBoxInline {
  require(lanes > 0 && width > 0 && components > 0)
  override def desiredName = s"FptCoefficientRollingHeadTile_${lanes}_${width}_${components}"
  private val commandWidth = 2 + components
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    // bits 0/1: normal half writes; bit 2: save; bits 3+: repair components 1 and above (component 0 is never repaired).
    val commandInput = Input(UInt(commandWidth.W))
    val normalTail = Input(UInt((lanes * width * components).W))
    val deferredInput = Input(UInt((lanes * width).W))
    val heads = Output(UInt((2 * lanes * width * components).W))
  })
  private val updates = (0 until components).flatMap { component =>
    (0 until lanes).map { lane =>
      val data = (component * lanes + lane) * width
      val high = (components * lanes + component * lanes + lane) * width
      val saved = lane * width
      s"""if (command[0]) headsReg[$data +: $width] <= normalTail[$data +: $width];
         |if (command[1]) headsReg[$high +: $width] <= normalTail[$data +: $width];
         |${if (component > 0) s"if (command[${2 + component}]) headsReg[$data +: $width] <= deferredTail[$saved +: $width];" else ""}""".stripMargin
    }
  }.mkString("\n")
  setInline(s"$desiredName.sv", s"""(* KEEP_HIERARCHY = "yes" *) module $desiredName (
    |input wire clock, input wire reset,
    |input wire [${commandWidth - 1}:0] commandInput,
    |input wire [${lanes * width * components - 1}:0] normalTail,
    |input wire [${lanes * width - 1}:0] deferredInput,
    |output wire [${2 * lanes * width * components - 1}:0] heads);
    |(* DONT_TOUCH = "yes", KEEP = "yes", SHREG_EXTRACT = "no" *) reg [${commandWidth - 1}:0] command;
    |(* EXTRACT_RESET = "no" *) reg [${2 * lanes * width * components - 1}:0] headsReg;
    |(* EXTRACT_RESET = "no" *) reg [${lanes * width - 1}:0] deferredTail;
    |always @(posedge clock) begin
    |  if (reset) command <= 0;
    |  else command <= commandInput;
    |  if (command[2]) deferredTail <= deferredInput;
    |  $updates
    |end
    |assign heads = headsReg;
    |endmodule
    |""".stripMargin)
}

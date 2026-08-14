package fpt

import chisel3._
import chisel3.util.HasBlackBoxInline

/** U280 clock generator for the accelerator and pending-request SLL gearbox.
  *
  * An UltraScale+ MMCM generates sibling 250 MHz and 500 MHz clocks from the
  * accelerator's 250 MHz reference. Driving both domains from the compensated
  * MMCM avoids the large hold skew that results when only the fast clock is
  * generated. Each reset asserts asynchronously whenever the MMCM is unlocked
  * and deasserts synchronously in its destination domain. Consumers use the
  * synchronized outputs as synchronous resets, avoiding wide asynchronous
  * recovery/removal networks on the dense datapath register banks.
  */
private[fpt] final class U280PendingClockDoubler250To500
    extends BlackBox
    with HasBlackBoxInline {
  override def desiredName: String = "FptU280PendingClockDoubler250To500"

  val io = IO(new Bundle {
    val clockIn = Input(Clock())
    val resetIn = Input(Bool())
    val slowClock = Output(Clock())
    val slowReset = Output(Bool())
    val fastClock = Output(Clock())
    val fastReset = Output(Bool())
    val locked = Output(Bool())
  })

  setInline(
    "FptU280PendingClockDoubler250To500.sv",
    """module FptU280PendingClockDoubler250To500 (
      |  input  wire clockIn,
      |  input  wire resetIn,
      |  output wire slowClock,
      |  output wire slowReset,
      |  output wire fastClock,
      |  output wire fastReset,
      |  output wire locked
      |);
      |  wire feedback_unbuffered;
      |  wire feedback_buffered;
      |  wire fast_unbuffered;
      |  wire slow_unbuffered;
      |
      |  MMCME4_ADV #(
      |    .BANDWIDTH("OPTIMIZED"),
      |    .CLKFBOUT_MULT_F(4.000),
      |    .CLKFBOUT_PHASE(0.000),
      |    .CLKIN1_PERIOD(4.000),
      |    .CLKOUT0_DIVIDE_F(2.000),
      |    .CLKOUT0_DUTY_CYCLE(0.500),
      |    .CLKOUT0_PHASE(0.000),
      |    .CLKOUT1_DIVIDE(4),
      |    .CLKOUT1_DUTY_CYCLE(0.500),
      |    .CLKOUT1_PHASE(0.000),
      |    .COMPENSATION("AUTO"),
      |    .DIVCLK_DIVIDE(1),
      |    .REF_JITTER1(0.010),
      |    .STARTUP_WAIT("FALSE")
      |  ) mmcm (
      |    .CLKFBOUT(feedback_unbuffered),
      |    .CLKOUT0(fast_unbuffered),
      |    .CLKOUT1(slow_unbuffered),
      |    .LOCKED(locked),
      |    .CLKFBIN(feedback_buffered),
      |    .CLKIN1(clockIn),
      |    .CLKIN2(1'b0),
      |    .CLKINSEL(1'b1),
      |    .CDDCREQ(1'b0),
      |    .DADDR(7'b0),
      |    .DCLK(1'b0),
      |    .DEN(1'b0),
      |    .DI(16'b0),
      |    .DWE(1'b0),
      |    .PSCLK(1'b0),
      |    .PSEN(1'b0),
      |    .PSINCDEC(1'b0),
      |    .PWRDWN(1'b0),
      |    .RST(resetIn)
      |  );
      |
      |  BUFGCE #(
      |    .CE_TYPE("SYNC"),
      |    .SIM_DEVICE("ULTRASCALE_PLUS")
      |  ) slow_buffer (
      |    .I(slow_unbuffered),
      |    .CE(1'b1),
      |    .O(slowClock)
      |  );
      |
      |  BUFGCE #(
      |    .CE_TYPE("SYNC"),
      |    .SIM_DEVICE("ULTRASCALE_PLUS")
      |  ) feedback_buffer (
      |    .I(feedback_unbuffered),
      |    .CE(1'b1),
      |    .O(feedback_buffered)
      |  );
      |
      |  BUFGCE #(
      |    .CE_TYPE("SYNC"),
      |    .SIM_DEVICE("ULTRASCALE_PLUS")
      |  ) fast_buffer (
      |    .I(fast_unbuffered),
      |    .CE(1'b1),
      |    .O(fastClock)
      |  );
      |
      |  wire reset_or_unlocked = resetIn | ~locked;
      |  (* ASYNC_REG = "TRUE" *) reg [1:0] slow_reset_sync = 2'b11;
      |  (* ASYNC_REG = "TRUE" *) reg [1:0] fast_reset_sync = 2'b11;
      |  always @(posedge slowClock or posedge reset_or_unlocked) begin
      |    if (reset_or_unlocked)
      |      slow_reset_sync <= 2'b11;
      |    else
      |      slow_reset_sync <= {slow_reset_sync[0], 1'b0};
      |  end
      |  always @(posedge fastClock or posedge reset_or_unlocked) begin
      |    if (reset_or_unlocked)
      |      fast_reset_sync <= 2'b11;
      |    else
      |      fast_reset_sync <= {fast_reset_sync[0], 1'b0};
      |  end
      |  assign slowReset = slow_reset_sync[1];
      |  assign fastReset = fast_reset_sync[1];
      |endmodule
      |""".stripMargin
  )
}

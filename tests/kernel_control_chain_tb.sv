`timescale 1ns/1ps
module KernelControlCase #(parameter CHAIN=0)(output reg finished=0);
  reg ACLK=0; always #5 ACLK=~ACLK;
  reg ARESET=1;
  reg AWVALID=0, WVALID=0, ARVALID=0;
  reg [6:0] AWADDR=0, ARADDR=0;
  reg [31:0] WDATA=0;
  reg [3:0] WSTRB=0;
  wire AWREADY, WREADY, BVALID, ARREADY, RVALID;
  wire [31:0] RDATA;
  wire ap_start, ap_continue, interrupt;
  reg ap_done=0, ap_ready=0;
  integer continues=0;
  reg [31:0] value;
  FptBlindRotateKernel_control_s_axi #(.C_CHAIN(CHAIN)) dut (
    .ACLK(ACLK), .ARESET(ARESET), .ACLK_EN(1'b1),
    .AWVALID(AWVALID), .AWREADY(AWREADY), .AWADDR(AWADDR),
    .WVALID(WVALID), .WREADY(WREADY), .WDATA(WDATA), .WSTRB(WSTRB),
    .BVALID(BVALID), .BREADY(1'b1), .BRESP(),
    .ARVALID(ARVALID), .ARREADY(ARREADY), .ARADDR(ARADDR),
    .RVALID(RVALID), .RREADY(1'b1), .RDATA(RDATA), .RRESP(),
    .ap_start(ap_start), .ap_continue(ap_continue), .ap_done(ap_done),
    .ap_ready(ap_ready), .ap_idle(1'b0), .interrupt(interrupt),
    .input_ptr(), .key_low_ptr(), .key_high_ptr(), .key_low1_ptr(),
    .key_high1_ptr(), .output_ptr(), .kernel_status(32'h0),
    .debug_key_beats(32'h0), .debug_key_starved_cycles(32'h0),
    .debug_key_bank_blocked_cycles(32'h0), .debug_run_cycles(32'h0),
    .chain_accepted(32'd11), .chain_prefetched(32'd7), .chain_completed(32'd10));
  always @(posedge ACLK) begin
    if (!ARESET && ap_continue) begin
      continues <= continues+1;
      ap_done <= 0;
    end
  end
  task automatic write_reg(input [6:0] address, input [31:0] data,
                           input [3:0] strobe=4'hf, input accept=0);
    @(negedge ACLK); AWADDR=address; AWVALID=1;
    do @(posedge ACLK); while (!AWREADY);
    @(negedge ACLK); AWVALID=0; WDATA=data; WSTRB=strobe; WVALID=1; ap_ready=accept;
    do @(posedge ACLK); while (!WREADY);
    @(negedge ACLK); WVALID=0; ap_ready=0;
    @(posedge ACLK); #1;
  endtask
  task automatic read_reg(input [6:0] address, output [31:0] data);
    @(negedge ACLK); ARADDR=address; ARVALID=1;
    do @(posedge ACLK); while (!ARREADY);
    #1; data=RDATA;
    @(negedge ACLK); ARVALID=0;
    @(posedge ACLK); #1;
  endtask
  initial begin
    repeat(3) @(posedge ACLK);
    @(negedge ACLK); ARESET=0;
    write_reg(7'h10,32'hdeadbeef);
    write_reg(7'h10,32'h42,4'h1);
    read_reg(7'h10,value);
    if(value!==32'hdeadbe42) $fatal(1,"pointer byte strobes failed");
    write_reg(0,1);
    if(!ap_start) $fatal(1,"start not held");
    // A continue write must not suppress simultaneous start acknowledgement.
    write_reg(0,16,4'hf,1);
    if(ap_start) $fatal(1,"start accepted twice on concurrent control write");
    @(negedge ACLK); ap_done=1;
    @(posedge ACLK); #1;
    if(!CHAIN) begin @(negedge ACLK); ap_done=0; end
    read_reg(0,value);
    if(!value[1]) $fatal(1,"missing completion");
    read_reg(0,value);
    if(value[1]!==CHAIN[0]) $fatal(1,"wrong done clear-on-read semantics");
    write_reg(0,16,4'h2);
    if(CHAIN && !ap_done) $fatal(1,"masked continue acknowledged completion");
    write_reg(0,16);
    if(CHAIN && ap_done) $fatal(1,"continue did not acknowledge completion");
    if(continues !== (CHAIN ? 2 : 0)) $fatal(1,"wrong continue pulse count");
    read_reg(7'h58,value);
    if(value !== (CHAIN ? 32'd11 : 32'd0)) $fatal(1,"chain counter mapping failed");
    write_reg(0,128);
    read_reg(0,value);
    if(value[7] !== !CHAIN[0]) $fatal(1,"auto-restart mode guard failed");
    $display("KERNEL_CONTROL_PASS chain=%0d",CHAIN);
    finished=1;
  end
endmodule
module KernelControlChainTB;
  wire sequential_done, chained_done;
  KernelControlCase #(.CHAIN(0)) sequential_case(sequential_done);
  KernelControlCase #(.CHAIN(1)) chained_case(chained_done);
  initial begin wait(sequential_done && chained_done); $finish; end
  initial begin #100000; $fatal(1,"AXI control test timeout"); end
endmodule

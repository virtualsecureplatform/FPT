`timescale 1ns/1ps
`default_nettype none

`define FPT_AXI_MASTER_PORT(P) \
  output wire P``_awvalid, input wire P``_awready, \
  output wire [63:0] P``_awaddr, output wire [7:0] P``_awlen, \
  output wire [2:0] P``_awsize, output wire [1:0] P``_awburst, \
  output wire P``_wvalid, input wire P``_wready, \
  output wire [511:0] P``_wdata, output wire [63:0] P``_wstrb, \
  output wire P``_wlast, input wire P``_bvalid, output wire P``_bready, input wire [1:0] P``_bresp, \
  output wire P``_arvalid, input wire P``_arready, \
  output wire [63:0] P``_araddr, output wire [7:0] P``_arlen, \
  output wire [2:0] P``_arsize, output wire [1:0] P``_arburst, \
  input wire P``_rvalid, output wire P``_rready, \
  input wire [511:0] P``_rdata, input wire [1:0] P``_rresp, input wire P``_rlast,

module FptPaperStreamingKernel #(
  parameter integer C_S_AXI_CONTROL_ADDR_WIDTH = 7,
  parameter integer C_S_AXI_CONTROL_DATA_WIDTH = 32,
  parameter integer BRAM_STATE_LANES = 128
) (
  input wire ap_clk,
  input wire ap_rst_n,
  `FPT_AXI_MASTER_PORT(m_axi_input)
  `FPT_AXI_MASTER_PORT(m_axi_key_low)
  `FPT_AXI_MASTER_PORT(m_axi_key_high)
  `FPT_AXI_MASTER_PORT(m_axi_key_low1)
  `FPT_AXI_MASTER_PORT(m_axi_key_high1)
  `FPT_AXI_MASTER_PORT(m_axi_output)
  input  wire s_axi_control_awvalid,
  output wire s_axi_control_awready,
  input  wire [C_S_AXI_CONTROL_ADDR_WIDTH-1:0] s_axi_control_awaddr,
  input  wire s_axi_control_wvalid,
  output wire s_axi_control_wready,
  input  wire [C_S_AXI_CONTROL_DATA_WIDTH-1:0] s_axi_control_wdata,
  input  wire [C_S_AXI_CONTROL_DATA_WIDTH/8-1:0] s_axi_control_wstrb,
  input  wire s_axi_control_arvalid,
  output wire s_axi_control_arready,
  input  wire [C_S_AXI_CONTROL_ADDR_WIDTH-1:0] s_axi_control_araddr,
  output wire s_axi_control_rvalid,
  input  wire s_axi_control_rready,
  output wire [C_S_AXI_CONTROL_DATA_WIDTH-1:0] s_axi_control_rdata,
  output wire [1:0] s_axi_control_rresp,
  output wire s_axi_control_bvalid,
  input  wire s_axi_control_bready,
  output wire [1:0] s_axi_control_bresp,
  output wire interrupt
);

  wire reset=~ap_rst_n;
  wire ap_start,ap_idle,ap_ready,ap_done,unused_continue,busy;
  wire [63:0] input_ptr,output_ptr,key_ptr [0:3];
  wire [31:0] batch_count,status,cycles,first_cycle,last_cycle,input_frames,computes_started,computes_done,streams_done,key_beats,key_blocked;
  wire [5:0] command_valid,command_ready,status_valid,dma_error;
  wire [103:0] command_data [0:5];
  wire [7:0] status_data [0:5];
  wire [4:0] read_valid,read_ready,read_last;
  wire [511:0] read_data [0:4];
  wire [511:0] key_data [0:3];
  wire output_valid,output_ready,output_last;
  wire [511:0] output_data;
  wire [63:0] output_keep;
  genvar port;
  generate for(port=0;port<4;port=port+1)begin : key_wires
    assign key_data[port]=read_data[port+1];
  end endgenerate
  FptPaperStreamingKernel_control_s_axi #(.C_S_AXI_ADDR_WIDTH(C_S_AXI_CONTROL_ADDR_WIDTH),.C_S_AXI_DATA_WIDTH(C_S_AXI_CONTROL_DATA_WIDTH),.C_CHAIN(0)) control(
    .ACLK(ap_clk),.ARESET(reset),.ACLK_EN(1'b1),
    .AWVALID(s_axi_control_awvalid),.AWREADY(s_axi_control_awready),.AWADDR(s_axi_control_awaddr),
    .WVALID(s_axi_control_wvalid),.WREADY(s_axi_control_wready),.WDATA(s_axi_control_wdata),.WSTRB(s_axi_control_wstrb),
    .ARVALID(s_axi_control_arvalid),.ARREADY(s_axi_control_arready),.ARADDR(s_axi_control_araddr),
    .RVALID(s_axi_control_rvalid),.RREADY(s_axi_control_rready),.RDATA(s_axi_control_rdata),.RRESP(s_axi_control_rresp),
    .BVALID(s_axi_control_bvalid),.BREADY(s_axi_control_bready),.BRESP(s_axi_control_bresp),.interrupt(interrupt),
    .ap_start(ap_start),.ap_continue(unused_continue),.ap_done(ap_done),.ap_ready(ap_ready),.ap_idle(ap_idle),
    .input_ptr(input_ptr),.output_ptr(output_ptr),.key_low_ptr(key_ptr[0]),.key_high_ptr(key_ptr[1]),
    .key_low1_ptr(key_ptr[2]),.key_high1_ptr(key_ptr[3]),.batch_count(batch_count),
    .kernel_status(status),.debug_key_beats(key_beats),.debug_key_starved_cycles(32'd0),
    .debug_key_bank_blocked_cycles(key_blocked),.debug_run_cycles(cycles),
    .chain_accepted(computes_started),.chain_prefetched(input_frames),.chain_completed(streams_done),
    .preparation_cycles(first_cycle),.setup_cycles(last_cycle),.compute_cycles(last_cycle-first_cycle),
    .output_tail_cycles(cycles-last_cycle),.dma_wait_cycles(32'd0));
  PaperStreamingDmaEngine #(.BRAM_STATE_LANES(BRAM_STATE_LANES)) engine(
    .clk(ap_clk),.reset(reset),.start(ap_start),.idle(ap_idle),.ready(ap_ready),.busy(busy),.done(ap_done),
    .batch_count(batch_count),.input_pointer(input_ptr),.output_pointer(output_ptr),.key_pointer(key_ptr),
    .command_valid(command_valid),.command_ready(command_ready),.command_data(command_data),
    .status_valid(status_valid),.dma_error(dma_error),.status_data(status_data),
    .input_valid(read_valid[0]),.input_last(read_last[0]),.input_ready(read_ready[0]),.input_data(read_data[0]),
    .key_valid(read_valid[4:1]),.key_last(read_last[4:1]),.key_ready(read_ready[4:1]),.key_data(key_data),
    .output_valid(output_valid),.output_last(output_last),.output_ready(output_ready),.output_data(output_data),.output_keep(output_keep),
    .status(status),.cycles(cycles),.first_compute_cycle(first_cycle),.last_compute_cycle(last_cycle),
    .input_frames(input_frames),.computes_started(computes_started),.computes_done(computes_done),.streams_done(streams_done),
    .key_beats(key_beats),.key_blocked_cycles(key_blocked));
`define PAPER_MM2S(INST,P,I) \
  axi_datamover_mm2s INST( \
    .m_axi_mm2s_aclk(ap_clk),.m_axi_mm2s_aresetn(ap_rst_n),.mm2s_err(dma_error[I]), \
    .m_axis_mm2s_cmdsts_aclk(ap_clk),.m_axis_mm2s_cmdsts_aresetn(ap_rst_n), \
    .s_axis_mm2s_cmd_tvalid(command_valid[I]),.s_axis_mm2s_cmd_tready(command_ready[I]),.s_axis_mm2s_cmd_tdata(command_data[I]), \
    .m_axis_mm2s_sts_tvalid(status_valid[I]),.m_axis_mm2s_sts_tready(1'b1),.m_axis_mm2s_sts_tdata(status_data[I]), \
    .m_axis_mm2s_sts_tkeep(),.m_axis_mm2s_sts_tlast(),.m_axi_mm2s_arid(), \
    .m_axi_mm2s_araddr(P``_araddr),.m_axi_mm2s_arlen(P``_arlen),.m_axi_mm2s_arsize(P``_arsize),.m_axi_mm2s_arburst(P``_arburst), \
    .m_axi_mm2s_arprot(),.m_axi_mm2s_arcache(),.m_axi_mm2s_aruser(),.m_axi_mm2s_arvalid(P``_arvalid),.m_axi_mm2s_arready(P``_arready), \
    .m_axi_mm2s_rdata(P``_rdata),.m_axi_mm2s_rresp(P``_rresp),.m_axi_mm2s_rlast(P``_rlast),.m_axi_mm2s_rvalid(P``_rvalid),.m_axi_mm2s_rready(P``_rready), \
    .m_axis_mm2s_tdata(read_data[I]),.m_axis_mm2s_tkeep(),.m_axis_mm2s_tlast(read_last[I]),.m_axis_mm2s_tvalid(read_valid[I]),.m_axis_mm2s_tready(read_ready[I]));
  `PAPER_MM2S(input_datamover,m_axi_input,0)
  `PAPER_MM2S(key_low_datamover,m_axi_key_low,1)
  `PAPER_MM2S(key_high_datamover,m_axi_key_high,2)
  `PAPER_MM2S(key_low1_datamover,m_axi_key_low1,3)
  `PAPER_MM2S(key_high1_datamover,m_axi_key_high1,4)
  axi_datamover_s2mm output_datamover(
    .m_axi_s2mm_aclk(ap_clk),.m_axi_s2mm_aresetn(ap_rst_n),.s2mm_err(dma_error[5]),
    .m_axis_s2mm_cmdsts_awclk(ap_clk),.m_axis_s2mm_cmdsts_aresetn(ap_rst_n),
    .s_axis_s2mm_cmd_tvalid(command_valid[5]),.s_axis_s2mm_cmd_tready(command_ready[5]),.s_axis_s2mm_cmd_tdata(command_data[5]),
    .m_axis_s2mm_sts_tvalid(status_valid[5]),.m_axis_s2mm_sts_tready(1'b1),.m_axis_s2mm_sts_tdata(status_data[5]),
    .m_axis_s2mm_sts_tkeep(),.m_axis_s2mm_sts_tlast(),.m_axi_s2mm_awid(),
    .m_axi_s2mm_awaddr(m_axi_output_awaddr),.m_axi_s2mm_awlen(m_axi_output_awlen),.m_axi_s2mm_awsize(m_axi_output_awsize),.m_axi_s2mm_awburst(m_axi_output_awburst),
    .m_axi_s2mm_awprot(),.m_axi_s2mm_awcache(),.m_axi_s2mm_awuser(),.m_axi_s2mm_awvalid(m_axi_output_awvalid),.m_axi_s2mm_awready(m_axi_output_awready),
    .m_axi_s2mm_wdata(m_axi_output_wdata),.m_axi_s2mm_wstrb(m_axi_output_wstrb),.m_axi_s2mm_wlast(m_axi_output_wlast),.m_axi_s2mm_wvalid(m_axi_output_wvalid),
    .m_axi_s2mm_wready(m_axi_output_wready),.m_axi_s2mm_bresp(m_axi_output_bresp),.m_axi_s2mm_bvalid(m_axi_output_bvalid),.m_axi_s2mm_bready(m_axi_output_bready),
    .s_axis_s2mm_tdata(output_data),.s_axis_s2mm_tkeep(output_keep),.s_axis_s2mm_tlast(output_last),.s_axis_s2mm_tvalid(output_valid),.s_axis_s2mm_tready(output_ready));
`define PAPER_TIE_WRITE(P) \
  assign P``_awvalid=0;assign P``_awaddr=0;assign P``_awlen=0;assign P``_awsize=3'd6;assign P``_awburst=2'b01; \
  assign P``_wvalid=0;assign P``_wdata=0;assign P``_wstrb=0;assign P``_wlast=0;assign P``_bready=0;
  `PAPER_TIE_WRITE(m_axi_input)
  `PAPER_TIE_WRITE(m_axi_key_low)
  `PAPER_TIE_WRITE(m_axi_key_high)
  `PAPER_TIE_WRITE(m_axi_key_low1)
  `PAPER_TIE_WRITE(m_axi_key_high1)
  assign m_axi_output_arvalid=0;assign m_axi_output_araddr=0;assign m_axi_output_arlen=0;
  assign m_axi_output_arsize=3'd6;assign m_axi_output_arburst=2'b01;assign m_axi_output_rready=0;
endmodule
`undef FPT_AXI_MASTER_PORT
`undef PAPER_MM2S
`undef PAPER_TIE_WRITE
`default_nettype wire

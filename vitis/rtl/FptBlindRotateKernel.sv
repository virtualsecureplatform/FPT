`timescale 1ns/1ps
`default_nettype none

`define FPT_AXI_MASTER_PORT(P) \
  output wire P``_awvalid, input wire P``_awready, \
  output wire [63:0] P``_awaddr, output wire [7:0] P``_awlen, \
  output wire P``_wvalid, input wire P``_wready, \
  output wire [511:0] P``_wdata, output wire [63:0] P``_wstrb, \
  output wire P``_wlast, input wire P``_bvalid, output wire P``_bready, \
  output wire P``_arvalid, input wire P``_arready, \
  output wire [63:0] P``_araddr, output wire [7:0] P``_arlen, \
  input wire P``_rvalid, output wire P``_rready, \
  input wire [511:0] P``_rdata, input wire P``_rlast,

module FptBlindRotateKernel #(
  parameter integer C_S_AXI_CONTROL_ADDR_WIDTH = 7,
  parameter integer C_S_AXI_CONTROL_DATA_WIDTH = 32
) (
  input wire ap_clk,
  input wire ap_rst_n,
  `FPT_AXI_MASTER_PORT(m_axi_input)
  `FPT_AXI_MASTER_PORT(m_axi_key_low)
  `FPT_AXI_MASTER_PORT(m_axi_key_high)
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

  wire reset = ~ap_rst_n;
  wire ap_start;
  wire ap_idle;
  wire ap_done;
  wire ap_ready;
  wire [31:0] kernel_status;
  wire [63:0] input_ptr;
  wire [63:0] key_low_ptr;
  wire [63:0] key_high_ptr;
  wire [63:0] output_ptr;

  wire input_cmd_valid, input_cmd_ready;
  wire key_low_cmd_valid, key_low_cmd_ready;
  wire key_high_cmd_valid, key_high_cmd_ready;
  wire output_cmd_valid, output_cmd_ready;
  wire [103:0] input_cmd_data, key_low_cmd_data;
  wire [103:0] key_high_cmd_data, output_cmd_data;
  wire input_stream_valid, input_stream_ready;
  wire key_low_stream_valid, key_low_stream_ready;
  wire key_high_stream_valid, key_high_stream_ready;
  wire [511:0] input_stream_data, key_low_stream_data, key_high_stream_data;
  wire output_stream_valid, output_stream_ready, output_stream_last;
  wire [31:0] output_stream_data;
  wire input_status_valid, key_low_status_valid, key_high_status_valid;
  wire output_status_valid;
  wire [7:0] input_status_data, key_low_status_data, key_high_status_data;
  wire [7:0] output_status_data;
  wire input_error, key_low_error, key_high_error, output_error;

  FptBlindRotateKernel_control_s_axi #(
    .C_S_AXI_ADDR_WIDTH(C_S_AXI_CONTROL_ADDR_WIDTH),
    .C_S_AXI_DATA_WIDTH(C_S_AXI_CONTROL_DATA_WIDTH)
  ) control (
    .ACLK(ap_clk), .ARESET(reset), .ACLK_EN(1'b1),
    .AWVALID(s_axi_control_awvalid), .AWREADY(s_axi_control_awready),
    .AWADDR(s_axi_control_awaddr), .WVALID(s_axi_control_wvalid),
    .WREADY(s_axi_control_wready), .WDATA(s_axi_control_wdata),
    .WSTRB(s_axi_control_wstrb), .ARVALID(s_axi_control_arvalid),
    .ARREADY(s_axi_control_arready), .ARADDR(s_axi_control_araddr),
    .RVALID(s_axi_control_rvalid), .RREADY(s_axi_control_rready),
    .RDATA(s_axi_control_rdata), .RRESP(s_axi_control_rresp),
    .BVALID(s_axi_control_bvalid), .BREADY(s_axi_control_bready),
    .BRESP(s_axi_control_bresp), .interrupt(interrupt),
    .ap_start(ap_start), .ap_done(ap_done), .ap_ready(ap_ready),
    .ap_idle(ap_idle), .input_ptr(input_ptr), .key_low_ptr(key_low_ptr),
    .key_high_ptr(key_high_ptr), .output_ptr(output_ptr),
    .kernel_status(kernel_status)
  );

  FptBlindRotateKernelController controller (
    .clock(ap_clk), .reset(reset),
    .io_start(ap_start), .io_idle(ap_idle), .io_done(ap_done),
    .io_ready(ap_ready), .io_status(kernel_status),
    .io_inputPointer(input_ptr), .io_keyLowPointer(key_low_ptr),
    .io_keyHighPointer(key_high_ptr), .io_outputPointer(output_ptr),
    .io_inputCommand_ready(input_cmd_ready),
    .io_inputCommand_valid(input_cmd_valid), .io_inputCommand_bits(input_cmd_data),
    .io_keyLowCommand_ready(key_low_cmd_ready),
    .io_keyLowCommand_valid(key_low_cmd_valid), .io_keyLowCommand_bits(key_low_cmd_data),
    .io_keyHighCommand_ready(key_high_cmd_ready),
    .io_keyHighCommand_valid(key_high_cmd_valid), .io_keyHighCommand_bits(key_high_cmd_data),
    .io_outputCommand_ready(output_cmd_ready),
    .io_outputCommand_valid(output_cmd_valid), .io_outputCommand_bits(output_cmd_data),
    .io_inputData_ready(input_stream_ready),
    .io_inputData_valid(input_stream_valid), .io_inputData_bits(input_stream_data),
    .io_keyLowData_ready(key_low_stream_ready),
    .io_keyLowData_valid(key_low_stream_valid), .io_keyLowData_bits(key_low_stream_data),
    .io_keyHighData_ready(key_high_stream_ready),
    .io_keyHighData_valid(key_high_stream_valid), .io_keyHighData_bits(key_high_stream_data),
    .io_outputData_ready(output_stream_ready),
    .io_outputData_valid(output_stream_valid), .io_outputData_bits(output_stream_data),
    .io_outputLast(output_stream_last),
    .io_inputStatus_valid(input_status_valid), .io_inputStatus_bits(input_status_data),
    .io_keyLowStatus_valid(key_low_status_valid), .io_keyLowStatus_bits(key_low_status_data),
    .io_keyHighStatus_valid(key_high_status_valid), .io_keyHighStatus_bits(key_high_status_data),
    .io_outputStatus_valid(output_status_valid), .io_outputStatus_bits(output_status_data),
    .io_inputError(input_error), .io_keyLowError(key_low_error),
    .io_keyHighError(key_high_error), .io_outputError(output_error)
  );

  axi_datamover_mm2s input_datamover (
    .m_axi_mm2s_aclk(ap_clk), .m_axi_mm2s_aresetn(ap_rst_n),
    .mm2s_err(input_error),
    .m_axis_mm2s_cmdsts_aclk(ap_clk), .m_axis_mm2s_cmdsts_aresetn(ap_rst_n),
    .s_axis_mm2s_cmd_tvalid(input_cmd_valid), .s_axis_mm2s_cmd_tready(input_cmd_ready),
    .s_axis_mm2s_cmd_tdata(input_cmd_data),
    .m_axis_mm2s_sts_tvalid(input_status_valid), .m_axis_mm2s_sts_tready(1'b1),
    .m_axis_mm2s_sts_tdata(input_status_data), .m_axis_mm2s_sts_tkeep(),
    .m_axis_mm2s_sts_tlast(), .m_axi_mm2s_arid(),
    .m_axi_mm2s_araddr(m_axi_input_araddr), .m_axi_mm2s_arlen(m_axi_input_arlen),
    .m_axi_mm2s_arsize(), .m_axi_mm2s_arburst(), .m_axi_mm2s_arprot(),
    .m_axi_mm2s_arcache(), .m_axi_mm2s_aruser(),
    .m_axi_mm2s_arvalid(m_axi_input_arvalid), .m_axi_mm2s_arready(m_axi_input_arready),
    .m_axi_mm2s_rdata(m_axi_input_rdata), .m_axi_mm2s_rresp(2'b0),
    .m_axi_mm2s_rlast(m_axi_input_rlast), .m_axi_mm2s_rvalid(m_axi_input_rvalid),
    .m_axi_mm2s_rready(m_axi_input_rready),
    .m_axis_mm2s_tdata(input_stream_data), .m_axis_mm2s_tkeep(),
    .m_axis_mm2s_tlast(), .m_axis_mm2s_tvalid(input_stream_valid),
    .m_axis_mm2s_tready(input_stream_ready)
  );

`define FPT_KEY_DATAMOVER(INST, PREFIX, CMD, STREAM, STATUS, ERROR) \
  axi_datamover_mm2s INST ( \
    .m_axi_mm2s_aclk(ap_clk), .m_axi_mm2s_aresetn(ap_rst_n), .mm2s_err(ERROR), \
    .m_axis_mm2s_cmdsts_aclk(ap_clk), .m_axis_mm2s_cmdsts_aresetn(ap_rst_n), \
    .s_axis_mm2s_cmd_tvalid(CMD``_valid), .s_axis_mm2s_cmd_tready(CMD``_ready), \
    .s_axis_mm2s_cmd_tdata(CMD``_data), .m_axis_mm2s_sts_tvalid(STATUS``_valid), \
    .m_axis_mm2s_sts_tready(1'b1), .m_axis_mm2s_sts_tdata(STATUS``_data), \
    .m_axis_mm2s_sts_tkeep(), .m_axis_mm2s_sts_tlast(), .m_axi_mm2s_arid(), \
    .m_axi_mm2s_araddr(PREFIX``_araddr), .m_axi_mm2s_arlen(PREFIX``_arlen), \
    .m_axi_mm2s_arsize(), .m_axi_mm2s_arburst(), .m_axi_mm2s_arprot(), \
    .m_axi_mm2s_arcache(), .m_axi_mm2s_aruser(), .m_axi_mm2s_arvalid(PREFIX``_arvalid), \
    .m_axi_mm2s_arready(PREFIX``_arready), .m_axi_mm2s_rdata(PREFIX``_rdata), \
    .m_axi_mm2s_rresp(2'b0), .m_axi_mm2s_rlast(PREFIX``_rlast), \
    .m_axi_mm2s_rvalid(PREFIX``_rvalid), .m_axi_mm2s_rready(PREFIX``_rready), \
    .m_axis_mm2s_tdata(STREAM``_data), .m_axis_mm2s_tkeep(), .m_axis_mm2s_tlast(), \
    .m_axis_mm2s_tvalid(STREAM``_valid), .m_axis_mm2s_tready(STREAM``_ready) );

  `FPT_KEY_DATAMOVER(key_low_datamover, m_axi_key_low, key_low_cmd,
    key_low_stream, key_low_status, key_low_error)
  `FPT_KEY_DATAMOVER(key_high_datamover, m_axi_key_high, key_high_cmd,
    key_high_stream, key_high_status, key_high_error)

  axi_datamover_s2mm output_datamover (
    .m_axi_s2mm_aclk(ap_clk), .m_axi_s2mm_aresetn(ap_rst_n),
    .s2mm_err(output_error),
    .m_axis_s2mm_cmdsts_awclk(ap_clk), .m_axis_s2mm_cmdsts_aresetn(ap_rst_n),
    .s_axis_s2mm_cmd_tvalid(output_cmd_valid), .s_axis_s2mm_cmd_tready(output_cmd_ready),
    .s_axis_s2mm_cmd_tdata(output_cmd_data),
    .m_axis_s2mm_sts_tvalid(output_status_valid), .m_axis_s2mm_sts_tready(1'b1),
    .m_axis_s2mm_sts_tdata(output_status_data), .m_axis_s2mm_sts_tkeep(),
    .m_axis_s2mm_sts_tlast(), .m_axi_s2mm_awid(),
    .m_axi_s2mm_awaddr(m_axi_output_awaddr), .m_axi_s2mm_awlen(m_axi_output_awlen),
    .m_axi_s2mm_awsize(), .m_axi_s2mm_awburst(), .m_axi_s2mm_awprot(),
    .m_axi_s2mm_awcache(), .m_axi_s2mm_awuser(),
    .m_axi_s2mm_awvalid(m_axi_output_awvalid), .m_axi_s2mm_awready(m_axi_output_awready),
    .m_axi_s2mm_wdata(m_axi_output_wdata), .m_axi_s2mm_wstrb(m_axi_output_wstrb),
    .m_axi_s2mm_wlast(m_axi_output_wlast), .m_axi_s2mm_wvalid(m_axi_output_wvalid),
    .m_axi_s2mm_wready(m_axi_output_wready), .m_axi_s2mm_bresp(2'b0),
    .m_axi_s2mm_bvalid(m_axi_output_bvalid), .m_axi_s2mm_bready(m_axi_output_bready),
    .s_axis_s2mm_tdata(output_stream_data), .s_axis_s2mm_tkeep(4'hf),
    .s_axis_s2mm_tlast(output_stream_last), .s_axis_s2mm_tvalid(output_stream_valid),
    .s_axis_s2mm_tready(output_stream_ready)
  );

`define FPT_TIE_READ_MASTER_WRITE(P) \
  assign P``_awvalid = 1'b0; assign P``_awaddr = 64'b0; assign P``_awlen = 8'b0; \
  assign P``_wvalid = 1'b0; assign P``_wdata = 512'b0; assign P``_wstrb = 64'b0; \
  assign P``_wlast = 1'b0; assign P``_bready = 1'b0;
  `FPT_TIE_READ_MASTER_WRITE(m_axi_input)
  `FPT_TIE_READ_MASTER_WRITE(m_axi_key_low)
  `FPT_TIE_READ_MASTER_WRITE(m_axi_key_high)
  assign m_axi_output_arvalid = 1'b0;
  assign m_axi_output_araddr = 64'b0;
  assign m_axi_output_arlen = 8'b0;
  assign m_axi_output_rready = 1'b0;
endmodule

`undef FPT_AXI_MASTER_PORT
`undef FPT_KEY_DATAMOVER
`undef FPT_TIE_READ_MASTER_WRITE
`default_nettype wire

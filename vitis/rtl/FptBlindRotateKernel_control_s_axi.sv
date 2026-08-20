`timescale 1ns/1ps

module FptBlindRotateKernel_control_s_axi #(
  parameter integer C_S_AXI_ADDR_WIDTH = 7,
  parameter integer C_S_AXI_DATA_WIDTH = 32
) (
  input  wire                            ACLK,
  input  wire                            ARESET,
  input  wire                            ACLK_EN,
  input  wire                            AWVALID,
  output wire                            AWREADY,
  input  wire [C_S_AXI_ADDR_WIDTH-1:0]   AWADDR,
  input  wire                            WVALID,
  output wire                            WREADY,
  input  wire [C_S_AXI_DATA_WIDTH-1:0]   WDATA,
  input  wire [C_S_AXI_DATA_WIDTH/8-1:0] WSTRB,
  input  wire                            ARVALID,
  output wire                            ARREADY,
  input  wire [C_S_AXI_ADDR_WIDTH-1:0]   ARADDR,
  output wire                            RVALID,
  input  wire                            RREADY,
  output wire [C_S_AXI_DATA_WIDTH-1:0]   RDATA,
  output wire [1:0]                      RRESP,
  output wire                            BVALID,
  input  wire                            BREADY,
  output wire [1:0]                      BRESP,
  output wire                            interrupt,
  output wire                            ap_start,
  input  wire                            ap_done,
  input  wire                            ap_ready,
  input  wire                            ap_idle,
  output wire [63:0]                     input_ptr,
  output wire [63:0]                     key_low_ptr,
  output wire [63:0]                     key_high_ptr,
  output wire [63:0]                     output_ptr,
  input  wire [31:0]                     kernel_status
);

  localparam [6:0] ADDR_AP_CTRL       = 7'h00;
  localparam [6:0] ADDR_GIE           = 7'h04;
  localparam [6:0] ADDR_IER           = 7'h08;
  localparam [6:0] ADDR_ISR           = 7'h0c;
  localparam [6:0] ADDR_INPUT_LO      = 7'h10;
  localparam [6:0] ADDR_INPUT_HI      = 7'h14;
  localparam [6:0] ADDR_KEY_LOW_LO    = 7'h18;
  localparam [6:0] ADDR_KEY_LOW_HI    = 7'h1c;
  localparam [6:0] ADDR_KEY_HIGH_LO   = 7'h20;
  localparam [6:0] ADDR_KEY_HIGH_HI   = 7'h24;
  localparam [6:0] ADDR_OUTPUT_LO     = 7'h28;
  localparam [6:0] ADDR_OUTPUT_HI     = 7'h2c;
  localparam [6:0] ADDR_STATUS        = 7'h30;
  localparam [1:0] WRIDLE = 2'd0, WRDATA = 2'd1, WRRESP = 2'd2;
  localparam [1:0] RDIDLE = 2'd0, RDDATA = 2'd1;

  reg [1:0] wstate;
  reg [1:0] rstate;
  reg [C_S_AXI_ADDR_WIDTH-1:0] waddr;
  reg [31:0] rdata;
  reg int_ap_start;
  reg int_ap_done;
  reg int_ap_ready;
  reg int_auto_restart;
  reg int_gie;
  reg [1:0] int_ier;
  reg [1:0] int_isr;
  reg [63:0] int_input_ptr;
  reg [63:0] int_key_low_ptr;
  reg [63:0] int_key_high_ptr;
  reg [63:0] int_output_ptr;

  wire aw_hs = AWVALID && AWREADY;
  wire w_hs = WVALID && WREADY;
  wire ar_hs = ARVALID && ARREADY;
  wire [31:0] wmask = {
    {8{WSTRB[3]}}, {8{WSTRB[2]}}, {8{WSTRB[1]}}, {8{WSTRB[0]}}
  };

  assign AWREADY = wstate == WRIDLE;
  assign WREADY = wstate == WRDATA;
  assign BVALID = wstate == WRRESP;
  assign BRESP = 2'b00;
  assign ARREADY = rstate == RDIDLE;
  assign RVALID = rstate == RDDATA;
  assign RDATA = rdata;
  assign RRESP = 2'b00;
  assign interrupt = int_gie && (|int_isr);
  assign ap_start = int_ap_start;
  assign input_ptr = int_input_ptr;
  assign key_low_ptr = int_key_low_ptr;
  assign key_high_ptr = int_key_high_ptr;
  assign output_ptr = int_output_ptr;

  always @(posedge ACLK) begin
    if (ARESET) begin
      wstate <= WRIDLE;
      waddr <= 0;
    end else if (ACLK_EN) begin
      case (wstate)
        WRIDLE: if (AWVALID) begin wstate <= WRDATA; waddr <= AWADDR; end
        WRDATA: if (WVALID) wstate <= WRRESP;
        WRRESP: if (BREADY) wstate <= WRIDLE;
        default: wstate <= WRIDLE;
      endcase
    end
  end

  always @(posedge ACLK) begin
    if (ARESET) begin
      rstate <= RDIDLE;
      rdata <= 0;
    end else if (ACLK_EN) begin
      case (rstate)
        RDIDLE: if (ARVALID) begin
          rstate <= RDDATA;
          case (ARADDR)
            ADDR_AP_CTRL: begin
              rdata <= 0;
              rdata[0] <= int_ap_start;
              rdata[1] <= int_ap_done;
              rdata[2] <= ap_idle;
              rdata[3] <= int_ap_ready;
              rdata[7] <= int_auto_restart;
            end
            ADDR_GIE: rdata <= {31'b0, int_gie};
            ADDR_IER: rdata <= {30'b0, int_ier};
            ADDR_ISR: rdata <= {30'b0, int_isr};
            ADDR_INPUT_LO: rdata <= int_input_ptr[31:0];
            ADDR_INPUT_HI: rdata <= int_input_ptr[63:32];
            ADDR_KEY_LOW_LO: rdata <= int_key_low_ptr[31:0];
            ADDR_KEY_LOW_HI: rdata <= int_key_low_ptr[63:32];
            ADDR_KEY_HIGH_LO: rdata <= int_key_high_ptr[31:0];
            ADDR_KEY_HIGH_HI: rdata <= int_key_high_ptr[63:32];
            ADDR_OUTPUT_LO: rdata <= int_output_ptr[31:0];
            ADDR_OUTPUT_HI: rdata <= int_output_ptr[63:32];
            ADDR_STATUS: rdata <= kernel_status;
            default: rdata <= 0;
          endcase
        end
        RDDATA: if (RREADY) rstate <= RDIDLE;
        default: rstate <= RDIDLE;
      endcase
    end
  end

  always @(posedge ACLK) begin
    if (ARESET) begin
      int_ap_start <= 1'b0;
      int_ap_done <= 1'b0;
      int_ap_ready <= 1'b0;
      int_auto_restart <= 1'b0;
      int_gie <= 1'b0;
      int_ier <= 2'b0;
      int_isr <= 2'b0;
      int_input_ptr <= 64'b0;
      int_key_low_ptr <= 64'b0;
      int_key_high_ptr <= 64'b0;
      int_output_ptr <= 64'b0;
    end else if (ACLK_EN) begin
      if (w_hs && waddr == ADDR_AP_CTRL && WSTRB[0]) begin
        if (WDATA[0]) int_ap_start <= 1'b1;
        int_auto_restart <= WDATA[7];
      end else if (ap_ready) begin
        int_ap_start <= int_auto_restart;
      end

      if (ap_done) int_ap_done <= 1'b1;
      else if (ar_hs && ARADDR == ADDR_AP_CTRL) int_ap_done <= 1'b0;
      if (ap_ready) int_ap_ready <= 1'b1;
      else if (ar_hs && ARADDR == ADDR_AP_CTRL) int_ap_ready <= 1'b0;

      if (w_hs && waddr == ADDR_GIE && WSTRB[0]) int_gie <= WDATA[0];
      if (w_hs && waddr == ADDR_IER && WSTRB[0]) int_ier <= WDATA[1:0];
      if (int_ier[0] && ap_done) int_isr[0] <= 1'b1;
      if (int_ier[1] && ap_ready) int_isr[1] <= 1'b1;
      if (w_hs && waddr == ADDR_ISR && WSTRB[0])
        int_isr <= int_isr & ~WDATA[1:0];

      if (w_hs && waddr == ADDR_INPUT_LO)
        int_input_ptr[31:0] <= (WDATA & wmask) | (int_input_ptr[31:0] & ~wmask);
      if (w_hs && waddr == ADDR_INPUT_HI)
        int_input_ptr[63:32] <= (WDATA & wmask) | (int_input_ptr[63:32] & ~wmask);
      if (w_hs && waddr == ADDR_KEY_LOW_LO)
        int_key_low_ptr[31:0] <= (WDATA & wmask) | (int_key_low_ptr[31:0] & ~wmask);
      if (w_hs && waddr == ADDR_KEY_LOW_HI)
        int_key_low_ptr[63:32] <= (WDATA & wmask) | (int_key_low_ptr[63:32] & ~wmask);
      if (w_hs && waddr == ADDR_KEY_HIGH_LO)
        int_key_high_ptr[31:0] <= (WDATA & wmask) | (int_key_high_ptr[31:0] & ~wmask);
      if (w_hs && waddr == ADDR_KEY_HIGH_HI)
        int_key_high_ptr[63:32] <= (WDATA & wmask) | (int_key_high_ptr[63:32] & ~wmask);
      if (w_hs && waddr == ADDR_OUTPUT_LO)
        int_output_ptr[31:0] <= (WDATA & wmask) | (int_output_ptr[31:0] & ~wmask);
      if (w_hs && waddr == ADDR_OUTPUT_HI)
        int_output_ptr[63:32] <= (WDATA & wmask) | (int_output_ptr[63:32] & ~wmask);
    end
  end
endmodule

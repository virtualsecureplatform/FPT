// One spectral point/component lane for the guarded32 schedule.
// Four shallow accumulator slots = two transaction banks x two frame halves.
// The same distributed RAM slots are read by accumulation and drain paths;
// first-row writes initialize each slot, so the memory needs no reset or copy.
module PaperStreamingMacLane #(
  parameter integer USE_SPLIT=0, ACC_WIDTH=48, FUSED_ACCUM_ROUND=0, PRODUCT_PIPELINE=0
)(
  input wire clk, reset, input_valid,
  input wire input_bank, input_half,
  input wire [2:0] input_row,
  input wire signed [31:0] ar, ai,
  input wire signed [27:0] br, bi,
  output reg result_valid, result_bank, result_half, overflow,
  input wire read_bank, read_half,
  output wire signed [ACC_WIDTH-1:0] read_real, read_imag
);
  wire product_valid, real_valid, imag_valid, real_overflow, imag_overflow;
  wire signed [61:0] product_real, product_imag;
  wire signed [ACC_WIDTH-1:0] rounded_real, rounded_imag;
  wire real_increment,imag_increment;
  localparam integer LAST=3+PRODUCT_PIPELINE;
  reg [4:0] metadata [0:LAST];
  (* ram_style="distributed" *) reg signed [ACC_WIDTH-1:0] real_sums [0:3], imag_sums [0:3];
  wire [1:0] address=metadata[LAST][1:0];
  wire first_row=metadata[LAST][4:2]==0;
  wire signed [ACC_WIDTH:0] old_real=first_row?'0:$signed({real_sums[address][ACC_WIDTH-1],real_sums[address]});
  wire signed [ACC_WIDTH:0] old_imag=first_row?'0:$signed({imag_sums[address][ACC_WIDTH-1],imag_sums[address]});
  (* use_dsp = "no" *) wire signed [ACC_WIDTH:0] next_real=old_real+$signed({rounded_real[ACC_WIDTH-1],rounded_real})+$signed({{ACC_WIDTH{1'b0}},real_increment});
  (* use_dsp = "no" *) wire signed [ACC_WIDTH:0] next_imag=old_imag+$signed({rounded_imag[ACC_WIDTH-1],rounded_imag})+$signed({{ACC_WIDTH{1'b0}},imag_increment});
  assign read_real=real_sums[{read_bank,read_half}];
  assign read_imag=imag_sums[{read_bank,read_half}];
  generate if(USE_SPLIT!=0) begin : split_product
    PaperStreamingComplexProductSplit #(.A_WIDTH(32),.B_WIDTH(28),.PIPELINE(PRODUCT_PIPELINE)) product(
      .clk(clk),.reset(reset),.input_valid(input_valid),.ar(ar),.ai(ai),.br(br),.bi(bi),
      .output_valid(product_valid),.pr(product_real),.pi(product_imag));
  end else begin : direct_product
    PaperStreamingComplexProduct #(.A_WIDTH(32),.B_WIDTH(28)) product(
    .clk(clk),.reset(reset),.input_valid(input_valid),.ar(ar),.ai(ai),.br(br),.bi(bi),
    .output_valid(product_valid),.pr(product_real),.pi(product_imag));
  end endgenerate
  generate if(FUSED_ACCUM_ROUND!=0)begin : fused_round_input
    localparam integer SHIFT=62-ACC_WIDTH;
    reg valid;
    reg signed [ACC_WIDTH-1:0] high_real,high_imag;
    reg increment_real,increment_imag,overflow_real,overflow_imag;
    wire real_up=product_real[SHIFT-1] && ((|product_real[SHIFT-2:0]) || product_real[SHIFT]);
    wire imag_up=product_imag[SHIFT-1] && ((|product_imag[SHIFT-2:0]) || product_imag[SHIFT]);
    always @(posedge clk)begin
      if(reset)valid<=0;else valid<=product_valid;
      high_real<=product_real[61:SHIFT];high_imag<=product_imag[61:SHIFT];
      increment_real<=real_up;increment_imag<=imag_up;
      overflow_real<=!product_real[61] && (&product_real[60:SHIFT]) && real_up;
      overflow_imag<=!product_imag[61] && (&product_imag[60:SHIFT]) && imag_up;
    end
    assign real_valid=valid;assign imag_valid=valid;
    assign rounded_real=high_real;assign rounded_imag=high_imag;
    assign real_increment=increment_real;assign imag_increment=increment_imag;
    assign real_overflow=overflow_real;assign imag_overflow=overflow_imag;
  end else begin : separate_round_input
    assign real_increment=0;assign imag_increment=0;
  PaperStreamingRequantize #(.INPUT_WIDTH(62),.OUTPUT_WIDTH(ACC_WIDTH),.SHIFT(62-ACC_WIDTH)) real_round(
    .clk(clk),.reset(reset),.input_valid(product_valid),.value(product_real),
    .output_valid(real_valid),.overflow(real_overflow),.result(rounded_real));
  PaperStreamingRequantize #(.INPUT_WIDTH(62),.OUTPUT_WIDTH(ACC_WIDTH),.SHIFT(62-ACC_WIDTH)) imag_round(
    .clk(clk),.reset(reset),.input_valid(product_valid),.value(product_imag),
    .output_valid(imag_valid),.overflow(imag_overflow),.result(rounded_imag));
  end endgenerate
  integer stage;
  always @(posedge clk) begin
    metadata[0]<={input_row,input_bank,input_half};
    for(stage=1;stage<=LAST;stage=stage+1)metadata[stage]<=metadata[stage-1];
    if(reset) begin
      result_valid<=0;
      overflow<=0;
    end else begin
      result_valid<=real_valid && imag_valid && metadata[LAST][4:2]==5;
      result_bank<=metadata[LAST][1];result_half<=metadata[LAST][0];
      overflow<=real_valid && (real_overflow || imag_overflow ||
                               next_real[ACC_WIDTH]!=next_real[ACC_WIDTH-1] || next_imag[ACC_WIDTH]!=next_imag[ACC_WIDTH-1]);
    end
    // Not gated by reset: the valid chain is reset, and every slot is
    // initialised by its first row, so a write in the reset cycle is harmless.
    if(real_valid && imag_valid) begin
      real_sums[address]<=next_real[ACC_WIDTH-1:0];
      imag_sums[address]<=next_imag[ACC_WIDTH-1:0];
    end
  end
  initial if(PRODUCT_PIPELINE!=0 && USE_SPLIT==0)$fatal(1,"product pipeline needs the split product");
  initial if(ACC_WIDTH!=40 && ACC_WIDTH!=48)$fatal(1,"unsupported streaming accumulator width");
endmodule

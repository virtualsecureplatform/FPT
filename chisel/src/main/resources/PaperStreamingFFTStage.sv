// One five-register-stage radix-4 FFT stage of the guarded32 profile.
// Stages 0..2 use natural-order beats; stage 3 uses groups of four butterfly
// legs and leaves the final permutation to the stream commutator.
module PaperStreamingFFTStage #(
  parameter integer LANES=128, STAGE=0, INVERSE=0, FUSED_ROUND=0, LEG_GUARD=-1, DSP_PAIRS=0, PRODUCT_PIPELINE=0
)(
  input wire clk, reset, input_valid,
  input wire signed [31:0] input_real [0:LANES-1],input_imag [0:LANES-1],
  output wire output_valid,
  output wire signed [31:0] output_real [0:LANES-1],output_imag [0:LANES-1],
  output reg fault
);
  localparam integer PHASE_BITS=$clog2(256/LANES),BUTTERFLIES=LANES/4;
  localparam integer QUARTER=1<<(2*STAGE);
  localparam integer FRACTION_DROP=INVERSE==0?2:(STAGE==0?0:1);
  localparam integer PRODUCT_WIDTH=LEG_GUARD<0?62:33+LEG_GUARD;
  localparam integer ROUND_SHIFT=(LEG_GUARD<0?26:LEG_GUARD)+FRACTION_DROP;
  reg [PHASE_BITS-1:0] phase;
  wire [LANES-1:0] round_valid_real,round_valid_imag,round_overflow_real,round_overflow_imag;
  wire [BUTTERFLIES-1:0] guard_overflow;
  assign output_valid=round_valid_real[0];
  genvar butterfly,leg;
  generate for(butterfly=0;butterfly<BUTTERFLIES;butterfly=butterfly+1) begin : butterflies
    wire signed [PRODUCT_WIDTH-1:0] product_real [0:3],product_imag [0:3];
    wire signed [PRODUCT_WIDTH+1:0] sum_real [0:3],sum_imag [0:3];
    wire signed [31:0] fused_real [0:3],fused_imag [0:3];
    wire [3:0] fused_overflow_real,fused_overflow_imag;
    wire [3:0] product_valid;
    wire [3:0] product_overflow;
    wire sum_valid,fused_valid;
    for(leg=0;leg<4;leg=leg+1) begin : legs
      localparam integer POSITION=STAGE==0 || STAGE==3?butterfly*4+leg:
          (butterfly/QUARTER)*(4*QUARTER)+butterfly%QUARTER+leg*QUARTER;
      PaperStreamingTwiddleProduct #(.LANES(LANES),.STAGE(STAGE),.INDEX(butterfly),.LEG(leg),.INVERSE(INVERSE),.LEG_GUARD(LEG_GUARD),.PRODUCT_PIPELINE(PRODUCT_PIPELINE)) twiddle(
        .clk(clk),.reset(reset),.input_valid(input_valid),.phase(phase),
        .ar(input_real[POSITION]),.ai(input_imag[POSITION]),
        .output_valid(product_valid[leg]),.pr(product_real[leg]),.pi(product_imag[leg]),.overflow(product_overflow[leg]));
      if(FUSED_ROUND==0)begin : separate_round
        PaperStreamingRequantize #(.INPUT_WIDTH(PRODUCT_WIDTH+2),.OUTPUT_WIDTH(32),.SHIFT(ROUND_SHIFT)) real_round(
        .clk(clk),.reset(reset),.input_valid(sum_valid),.value(sum_real[leg]),
        .output_valid(round_valid_real[POSITION]),.overflow(round_overflow_real[POSITION]),.result(output_real[POSITION]));
      PaperStreamingRequantize #(.INPUT_WIDTH(PRODUCT_WIDTH+2),.OUTPUT_WIDTH(32),.SHIFT(ROUND_SHIFT)) imag_round(
        .clk(clk),.reset(reset),.input_valid(sum_valid),.value(sum_imag[leg]),
        .output_valid(round_valid_imag[POSITION]),.overflow(round_overflow_imag[POSITION]),.result(output_imag[POSITION]));
      end else begin : fused_round
        assign output_real[POSITION]=fused_real[leg];assign output_imag[POSITION]=fused_imag[leg];
        assign round_valid_real[POSITION]=fused_valid;assign round_valid_imag[POSITION]=fused_valid;
        assign round_overflow_real[POSITION]=fused_overflow_real[leg];
        assign round_overflow_imag[POSITION]=fused_overflow_imag[leg];
      end
    end
    if(FUSED_ROUND==0)begin : full_butterfly
      PaperStreamingButterfly4 #(.WIDTH(PRODUCT_WIDTH),.INVERSE(INVERSE)) butterfly_add(
      .clk(clk),.reset(reset),.input_valid(product_valid[0]),.xr(product_real),.xi(product_imag),
      .output_valid(sum_valid),.yr(sum_real),.yi(sum_imag));
    end else begin : rounded_butterfly
      PaperStreamingRoundedButterfly4 #(.WIDTH(PRODUCT_WIDTH),.OUTPUT_WIDTH(32),.SHIFT(ROUND_SHIFT),.INVERSE(INVERSE),.DSP_PAIRS(DSP_PAIRS)) butterfly_add(
        .clk(clk),.reset(reset),.input_valid(product_valid[0]),.xr(product_real),.xi(product_imag),
        .output_valid(fused_valid),.yr(fused_real),.yi(fused_imag),
        .overflow_real(fused_overflow_real),.overflow_imag(fused_overflow_imag));
    end
    // Guard rounding can overflow before the final stage boundary; retain it
    // in the shared sticky fault even if the later narrowing would hide it.
    assign guard_overflow[butterfly]=product_valid[0] && (|product_overflow);
  end endgenerate
  always @(posedge clk) begin
    if(reset)begin phase<=0;fault<=0;end
    else begin
      if(input_valid)phase<=phase+1'b1;
      else if(phase!=0)fault<=1;
      if(output_valid && ((|round_overflow_real) || (|round_overflow_imag)))fault<=1;
      if(|guard_overflow)fault<=1;
    end
  end
  initial if((LANES!=64 && LANES!=128) || STAGE<0 || STAGE>3 || (INVERSE!=0 && INVERSE!=1))
    $fatal(1,"invalid streaming FFT stage geometry");
endmodule

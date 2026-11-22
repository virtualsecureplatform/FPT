// PIPELINE=1 registers the partial products; PIPELINE=2 also splits the final
// rounded sums. Latency is 3+PIPELINE.
// Exact three-real-product multiplication with the declared RNE boundary
// fused into the final real/imaginary sums. Three registered stages.
module PaperStreamingComplexProductRounded #(
  parameter integer A_WIDTH=32, B_WIDTH=28, OUTPUT_WIDTH=48, SHIFT=14, PIPELINE=0
)(
  input wire clk,reset,input_valid,
  input wire signed [A_WIDTH-1:0] ar,ai,
  input wire signed [B_WIDTH-1:0] br,bi,
  output wire output_valid,overflow_real,overflow_imag,
  output wire signed [OUTPUT_WIDTH-1:0] pr,pi
);
  localparam integer M_WIDTH=A_WIDTH+B_WIDTH+1;
  reg valid1,valid2,valid3;
  wire term_valid=PIPELINE!=0?valid3:valid2;
  reg signed [A_WIDTH:0] a_difference;
  reg signed [A_WIDTH-1:0] a_real,a_imag;
  reg signed [B_WIDTH:0] b_difference,b_sum;
  reg signed [B_WIDTH-1:0] b_real;
  wire signed [M_WIDTH-1:0] next_z,next_real,next_imag;
  reg signed [M_WIDTH-1:0] z,real_term,imag_term;
  wire imag_valid;
  PaperStreamingSplitMultiply #(.A_WIDTH(A_WIDTH+1),.B_WIDTH(B_WIDTH),.PIPELINE(PIPELINE)) mul_z(
    .clk(clk),.a(a_difference),.b(b_real),.product(next_z));
  PaperStreamingSplitMultiply #(.A_WIDTH(A_WIDTH),.B_WIDTH(B_WIDTH+1),.PIPELINE(PIPELINE)) mul_real(
    .clk(clk),.a(a_imag),.b(b_difference),.product(next_real));
  PaperStreamingSplitMultiply #(.A_WIDTH(A_WIDTH),.B_WIDTH(B_WIDTH+1),.PIPELINE(PIPELINE)) mul_imag(
    .clk(clk),.a(a_real),.b(b_sum),.product(next_imag));
  always @(posedge clk)begin
    if(reset)begin valid1<=0;valid2<=0;valid3<=0;end
    else begin valid1<=input_valid;valid2<=valid1;valid3<=valid2;end
    a_difference<=$signed({ar[A_WIDTH-1],ar})-$signed({ai[A_WIDTH-1],ai});
    a_real<=ar;a_imag<=ai;
    b_difference<=$signed({br[B_WIDTH-1],br})-$signed({bi[B_WIDTH-1],bi});
    b_sum<=$signed({br[B_WIDTH-1],br})+$signed({bi[B_WIDTH-1],bi});b_real<=br;
    z<=next_z;real_term<=next_real;imag_term<=next_imag;
  end
  PaperStreamingRoundedAdd #(.WIDTH(M_WIDTH),.OUTPUT_WIDTH(OUTPUT_WIDTH),.SHIFT(SHIFT),.SUBTRACT(0),.PIPELINE(int'(PIPELINE>=2))) real_round(
    .clk(clk),.reset(reset),.input_valid(term_valid),.a(z),.b(real_term),
    .output_valid(output_valid),.overflow(overflow_real),.result(pr));
  PaperStreamingRoundedAdd #(.WIDTH(M_WIDTH),.OUTPUT_WIDTH(OUTPUT_WIDTH),.SHIFT(SHIFT),.SUBTRACT(1),.PIPELINE(int'(PIPELINE>=2))) imag_round(
    .clk(clk),.reset(reset),.input_valid(term_valid),.a(imag_term),.b(z),
    .output_valid(imag_valid),.overflow(overflow_imag),.result(pi));
endmodule

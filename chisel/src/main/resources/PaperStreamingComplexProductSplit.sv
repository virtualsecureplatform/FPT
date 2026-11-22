// Exact signed multiplication with at most 27 bits on the DSP coefficient
// operand. Low coefficient bits use LUT arithmetic rather than another DSP tile.
// PIPELINE=1 registers the DSP and LUT partial products (one more cycle).
module PaperStreamingSplitMultiply #(
  parameter integer A_WIDTH=32, B_WIDTH=28, PIPELINE=0
)(
  input wire clk,
  input wire signed [A_WIDTH-1:0] a,
  input wire signed [B_WIDTH-1:0] b,
  output wire signed [A_WIDTH+B_WIDTH-1:0] product
);
  generate
    if(B_WIDTH<=27) begin : direct
      (* use_dsp="yes" *) wire signed [A_WIDTH+B_WIDTH-1:0] p=a*b;
      if(PIPELINE!=0)begin : staged
        reg signed [A_WIDTH+B_WIDTH-1:0] p_q;
        always @(posedge clk)p_q<=p;
        assign product=p_q;
      end else begin : immediate
        assign product=p;
      end
    end else begin : split
      localparam integer LOW_BITS=B_WIDTH-27;
      localparam integer HIGH_WIDTH=A_WIDTH+27;
      localparam integer LOW_WIDTH=A_WIDTH+LOW_BITS+1;
      wire signed [26:0] high_b=b[B_WIDTH-1:LOW_BITS];
      wire signed [LOW_BITS:0] low_b=$signed({1'b0,b[LOW_BITS-1:0]});
      (* use_dsp="yes" *) wire signed [HIGH_WIDTH-1:0] high_next=a*high_b;
      (* use_dsp="no" *) wire signed [LOW_WIDTH-1:0] low_next=a*low_b;
      wire signed [HIGH_WIDTH-1:0] high_product;
      wire signed [LOW_WIDTH-1:0] low_product;
      if(PIPELINE!=0)begin : staged
        reg signed [HIGH_WIDTH-1:0] high_q;
        reg signed [LOW_WIDTH-1:0] low_q;
        always @(posedge clk)begin high_q<=high_next;low_q<=low_next;end
        assign high_product=high_q;assign low_product=low_q;
      end else begin : immediate
        assign high_product=high_next;assign low_product=low_next;
      end
      wire signed [A_WIDTH+B_WIDTH-1:0] high_extended=
          {{LOW_BITS{high_product[HIGH_WIDTH-1]}},high_product};
      wire signed [A_WIDTH+B_WIDTH-1:0] low_extended=
          {{(B_WIDTH-LOW_BITS-1){low_product[LOW_WIDTH-1]}},low_product};
      assign product=(high_extended <<< LOW_BITS)+low_extended;
    end
  endgenerate
endmodule

// Port and three-cycle valid timing match PaperStreamingComplexProduct.
module PaperStreamingComplexProductSplit #(
  parameter integer A_WIDTH=32, B_WIDTH=28,
  parameter integer P_WIDTH=A_WIDTH+B_WIDTH+2, PIPELINE=0
)(
  input wire clk, reset, input_valid,
  input wire signed [A_WIDTH-1:0] ar, ai,
  input wire signed [B_WIDTH-1:0] br, bi,
  output reg output_valid,
  output reg signed [P_WIDTH-1:0] pr, pi
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
  PaperStreamingSplitMultiply #(.A_WIDTH(A_WIDTH+1),.B_WIDTH(B_WIDTH),.PIPELINE(PIPELINE)) mul_z(
    .clk(clk),.a(a_difference),.b(b_real),.product(next_z));
  PaperStreamingSplitMultiply #(.A_WIDTH(A_WIDTH),.B_WIDTH(B_WIDTH+1),.PIPELINE(PIPELINE)) mul_real(
    .clk(clk),.a(a_imag),.b(b_difference),.product(next_real));
  PaperStreamingSplitMultiply #(.A_WIDTH(A_WIDTH),.B_WIDTH(B_WIDTH+1),.PIPELINE(PIPELINE)) mul_imag(
    .clk(clk),.a(a_real),.b(b_sum),.product(next_imag));
  always @(posedge clk) begin
    if(reset)begin valid1<=0;valid2<=0;valid3<=0;output_valid<=0;end
    else begin valid1<=input_valid;valid2<=valid1;valid3<=valid2;output_valid<=term_valid;end
    a_difference<=$signed({ar[A_WIDTH-1],ar})-$signed({ai[A_WIDTH-1],ai});
    a_real<=ar;a_imag<=ai;
    b_difference<=$signed({br[B_WIDTH-1],br})-$signed({bi[B_WIDTH-1],bi});
    b_sum<=$signed({br[B_WIDTH-1],br})+$signed({bi[B_WIDTH-1],bi});
    b_real<=br;
    z<=next_z;real_term<=next_real;imag_term<=next_imag;
    pr<={{(P_WIDTH-M_WIDTH){z[M_WIDTH-1]}},z}+{{(P_WIDTH-M_WIDTH){real_term[M_WIDTH-1]}},real_term};
    pi<={{(P_WIDTH-M_WIDTH){imag_term[M_WIDTH-1]}},imag_term}-{{(P_WIDTH-M_WIDTH){z[M_WIDTH-1]}},z};
  end
  initial if(A_WIDTH<2 || B_WIDTH<2 || P_WIDTH<M_WIDTH+1)
    $fatal(1,"invalid split product widths");
endmodule

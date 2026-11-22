// Exact radix-4 butterfly plus RNE, retaining the two-cycle latency of the
// original butterfly/RNE pair. Register pair sums, then round final sums.
module PaperStreamingRoundedButterfly4 #(
  parameter integer WIDTH=62, OUTPUT_WIDTH=32, SHIFT=28, INVERSE=0, DSP_PAIRS=0
)(
  input wire clk,reset,input_valid,
  input wire signed [WIDTH-1:0] xr [0:3],xi [0:3],
  output wire output_valid,
  output wire signed [OUTPUT_WIDTH-1:0] yr [0:3],yi [0:3],
  output wire [3:0] overflow_real,overflow_imag
);
  initial if(DSP_PAIRS!=0 && WIDTH+1>48)$fatal(1,"DSP pair sums exceed 48 bits");
  reg pair_valid;
  (* use_dsp=DSP_PAIRS!=0?"yes":"no" *) reg signed [WIDTH:0] er,ei,orr,oi,dr,di,cr,ci;
  always @(posedge clk)begin
    if(reset)pair_valid<=0;else pair_valid<=input_valid;
    er<=$signed({xr[0][WIDTH-1],xr[0]})+$signed({xr[2][WIDTH-1],xr[2]});
    ei<=$signed({xi[0][WIDTH-1],xi[0]})+$signed({xi[2][WIDTH-1],xi[2]});
    orr<=$signed({xr[1][WIDTH-1],xr[1]})+$signed({xr[3][WIDTH-1],xr[3]});
    oi<=$signed({xi[1][WIDTH-1],xi[1]})+$signed({xi[3][WIDTH-1],xi[3]});
    dr<=$signed({xr[0][WIDTH-1],xr[0]})-$signed({xr[2][WIDTH-1],xr[2]});
    di<=$signed({xi[0][WIDTH-1],xi[0]})-$signed({xi[2][WIDTH-1],xi[2]});
    cr<=$signed({xr[1][WIDTH-1],xr[1]})-$signed({xr[3][WIDTH-1],xr[3]});
    ci<=$signed({xi[1][WIDTH-1],xi[1]})-$signed({xi[3][WIDTH-1],xi[3]});
  end
  wire signed [WIDTH:0] real_a [0:3],real_b [0:3],imag_a [0:3],imag_b [0:3];
  assign real_a[0]=er;assign real_b[0]=orr;assign imag_a[0]=ei;assign imag_b[0]=oi;
  assign real_a[2]=er;assign real_b[2]=orr;assign imag_a[2]=ei;assign imag_b[2]=oi;
  assign real_a[1]=dr;assign real_b[1]=ci;assign imag_a[1]=di;assign imag_b[1]=cr;
  assign real_a[3]=dr;assign real_b[3]=ci;assign imag_a[3]=di;assign imag_b[3]=cr;
  wire [3:0] real_valid,imag_valid;
  assign output_valid=real_valid[0];
  genvar leg;
  generate for(leg=0;leg<4;leg=leg+1)begin : outputs
    localparam integer SUB_REAL=int'(leg==2 || (leg==1 && INVERSE!=0) || (leg==3 && INVERSE==0));
    localparam integer SUB_IMAG=int'(leg==2 || (leg==1 && INVERSE==0) || (leg==3 && INVERSE!=0));
    PaperStreamingRoundedAdd #(.WIDTH(WIDTH+1),.OUTPUT_WIDTH(OUTPUT_WIDTH),.SHIFT(SHIFT),.SUBTRACT(SUB_REAL)) real_sum(
      .clk(clk),.reset(reset),.input_valid(pair_valid),.a(real_a[leg]),.b(real_b[leg]),
      .output_valid(real_valid[leg]),.overflow(overflow_real[leg]),.result(yr[leg]));
    PaperStreamingRoundedAdd #(.WIDTH(WIDTH+1),.OUTPUT_WIDTH(OUTPUT_WIDTH),.SHIFT(SHIFT),.SUBTRACT(SUB_IMAG)) imag_sum(
      .clk(clk),.reset(reset),.input_valid(pair_valid),.a(imag_a[leg]),.b(imag_b[leg]),
      .output_valid(imag_valid[leg]),.overflow(overflow_imag[leg]),.result(yi[leg]));
  end endgenerate
endmodule

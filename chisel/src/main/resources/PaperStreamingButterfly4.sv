// Exact radix-4 add/subtract stage. Two growth bits; rounding is a separate
// declared stage boundary so common subexpressions never narrow prematurely.
module PaperStreamingButterfly4 #(
  parameter integer WIDTH = 32,
  parameter integer INVERSE = 0
)(
  input wire clk, reset, input_valid,
  input wire signed [WIDTH-1:0] xr [0:3], xi [0:3],
  output reg output_valid,
  output reg signed [WIDTH+1:0] yr [0:3], yi [0:3]
);
  wire signed [WIDTH+1:0] r [0:3], i [0:3];
  genvar leg;
  generate for(leg=0;leg<4;leg=leg+1) begin : extend_inputs
    assign r[leg]={{2{xr[leg][WIDTH-1]}},xr[leg]};
    assign i[leg]={{2{xi[leg][WIDTH-1]}},xi[leg]};
  end endgenerate
  wire signed [WIDTH+1:0] er=r[0]+r[2], ei=i[0]+i[2];
  wire signed [WIDTH+1:0] orr=r[1]+r[3], oi=i[1]+i[3];
  wire signed [WIDTH+1:0] dr=r[0]-r[2], di=i[0]-i[2];
  wire signed [WIDTH+1:0] cr=r[1]-r[3], ci=i[1]-i[3];
  always @(posedge clk) begin
    if(reset)output_valid<=1'b0;
    else output_valid<=input_valid;
    yr[0]<=er+orr;yi[0]<=ei+oi;
    yr[2]<=er-orr;yi[2]<=ei-oi;
    if(INVERSE!=0) begin
      yr[1]<=dr-ci;yi[1]<=di+cr;
      yr[3]<=dr+ci;yi[3]<=di-cr;
    end else begin
      yr[1]<=dr+ci;yi[1]<=di-cr;
      yr[3]<=dr-ci;yi[3]<=di+cr;
    end
  end
  initial if(WIDTH<2 || (INVERSE!=0 && INVERSE!=1))
    $fatal(1,"invalid streaming butterfly parameters");
endmodule

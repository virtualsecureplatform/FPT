// Area/timing reference for the existing four-cycle product/RNE sequence.
module PaperStreamingProductRequantize(
  input wire clk,reset,input_valid,
  input wire signed [31:0] ar,ai,
  input wire signed [27:0] br,bi,
  output wire output_valid,overflow_real,overflow_imag,
  output wire signed [47:0] pr,pi
);
  wire product_valid,imag_valid;
  wire signed [61:0] real_product,imag_product;
  PaperStreamingComplexProductSplit #(.A_WIDTH(32),.B_WIDTH(28)) product(
    .clk(clk),.reset(reset),.input_valid(input_valid),.ar(ar),.ai(ai),.br(br),.bi(bi),
    .output_valid(product_valid),.pr(real_product),.pi(imag_product));
  PaperStreamingRequantize #(.INPUT_WIDTH(62),.OUTPUT_WIDTH(48),.SHIFT(14)) real_round(
    .clk(clk),.reset(reset),.input_valid(product_valid),.value(real_product),
    .output_valid(output_valid),.overflow(overflow_real),.result(pr));
  PaperStreamingRequantize #(.INPUT_WIDTH(62),.OUTPUT_WIDTH(48),.SHIFT(14)) imag_round(
    .clk(clk),.reset(reset),.input_valid(product_valid),.value(imag_product),
    .output_valid(imag_valid),.overflow(overflow_imag),.result(pi));
endmodule

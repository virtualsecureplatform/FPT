// One-cycle signed round-to-nearest-even, overflow-before-wrap reporting.
// Payload registers deliberately have no reset; valid alone flushes the pipe.
module PaperStreamingRequantize #(
  parameter integer INPUT_WIDTH = 57,
  parameter integer OUTPUT_WIDTH = 32,
  parameter integer SHIFT = 20
)(
  input wire clk, reset, input_valid,
  input wire signed [INPUT_WIDTH-1:0] value,
  output reg output_valid, overflow,
  output reg signed [OUTPUT_WIDTH-1:0] result
);
  wire signed [INPUT_WIDTH:0] extended = {value[INPUT_WIDTH-1],value};
  wire signed [INPUT_WIDTH:0] rounded;
  generate
    if (SHIFT == 0) begin : no_shift
      assign rounded = extended;
    end else if (SHIFT == 1) begin : one_shift
      assign rounded = (extended >>> SHIFT) +
                       $signed({{INPUT_WIDTH{1'b0}},(value[0] & value[1])});
    end else begin : many_shift
      wire increment = value[SHIFT-1] & ((|value[SHIFT-2:0]) | value[SHIFT]);
      assign rounded = (extended >>> SHIFT) + $signed({{INPUT_WIDTH{1'b0}},increment});
    end
  endgenerate
  always @(posedge clk) begin
    if (reset) output_valid <= 1'b0;
    else output_valid <= input_valid;
    result <= rounded[OUTPUT_WIDTH-1:0];
    overflow <= rounded != {{(INPUT_WIDTH+1-OUTPUT_WIDTH){rounded[OUTPUT_WIDTH-1]}},
                            rounded[OUTPUT_WIDTH-1:0]};
  end
  initial begin
    if (INPUT_WIDTH < 2 || OUTPUT_WIDTH < 2 || OUTPUT_WIDTH > INPUT_WIDTH ||
        SHIFT < 0 || SHIFT >= INPUT_WIDTH)
      $fatal(1,"invalid streaming requantization widths");
  end
endmodule

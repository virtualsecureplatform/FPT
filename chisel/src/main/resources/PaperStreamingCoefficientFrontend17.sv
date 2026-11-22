// Exact coefficient rotation and two-level gadget decomposition on the
// profile's 17-bit state. DIGIT_REVERSED permutes output points in wiring. Ten cycles; 128 folded 8-bit digits each cycle.
module PaperStreamingCoefficientFrontend17 #(parameter integer DIGIT_REVERSED=0)(
  input wire clk,reset,input_valid,
  input wire [9:0] input_exponent,
  input wire [3:0] input_context,
  input wire [2:0] input_row,
  input wire input_half,
  input wire [16:0] input_code [0:511],
  output wire output_valid,
  output wire [3:0] output_context,
  output wire [2:0] output_row,
  output wire output_half,
  output wire signed [7:0] digit_low [0:127],digit_high [0:127],
  output reg fault
);
  wire rotated_valid;
  wire [16:0] rotated [0:511];
  (* shreg_extract="no" *) reg [16:0] original_low [0:4][0:127],original_high [0:4][0:127];
  reg [17:0] metadata [0:9];
  wire [127:0] low_valid,high_valid;
  // Digit-reversed order: the second half's points are the first half's
  // plus two, so rotating by exponent-2 brings them to the first half's
  // positions and only those outputs of the rotation network are built.
  // Signs below still use the true point and exponent.
  wire [8:0] rotation_exponent=DIGIT_REVERSED!=0 && input_half?input_exponent[8:0]-9'd2:input_exponent[8:0];
  PaperStreamingRotate17 #(.LOW_PAIRS(DIGIT_REVERSED!=0?1:0)) rotation(
    .clk(clk),.reset(reset),.input_valid(input_valid),.exponent(rotation_exponent),
    .input_code(input_code),.output_valid(rotated_valid),.output_code(rotated));
  genvar lane,stage;
  generate for(lane=0;lane<128;lane=lane+1)begin : lanes
    localparam integer LOW=DIGIT_REVERSED!=0?((lane%4)*64+((lane/4)%4)*16+((lane/16)%4)*4+lane/64):lane;
    localparam integer HIGH=DIGIT_REVERSED!=0?LOW+2:lane+128;
    always @(posedge clk)begin
      original_low[0][lane]<=input_half?input_code[HIGH]:input_code[LOW];
      original_high[0][lane]<=input_half?input_code[HIGH+256]:input_code[LOW+256];
    end
    for(stage=1;stage<5;stage=stage+1)begin : original_delays
      always @(posedge clk)begin
        original_low[stage][lane]<=original_low[stage-1][lane];
        original_high[stage][lane]<=original_high[stage-1][lane];
      end
    end
    wire [8:0] low_point=metadata[4][10]?9'(HIGH):9'(LOW);
    wire [8:0] high_point=low_point+9'd256;
    wire low_negative=(low_point<metadata[4][8:0])^metadata[4][9];
    wire high_negative=(high_point<metadata[4][8:0])^metadata[4][9];
    wire [16:0] selected_low,selected_high;
    if(DIGIT_REVERSED!=0)begin : shifted_half
      assign selected_low=rotated[LOW];assign selected_high=rotated[LOW+256];
    end else begin : muxed_half
      assign selected_low=metadata[4][10]?rotated[HIGH]:rotated[LOW];
      assign selected_high=metadata[4][10]?rotated[HIGH+256]:rotated[LOW+256];
    end
    PaperStreamingDecompose17 low_decompose(
      .clk(clk),.reset(reset),.input_valid(rotated_valid),.negate(low_negative),.level(metadata[4][11]),
      .rotated(selected_low),.current(original_low[4][lane]),.output_valid(low_valid[lane]),.digit(digit_low[lane]));
    PaperStreamingDecompose17 high_decompose(
      .clk(clk),.reset(reset),.input_valid(rotated_valid),.negate(high_negative),.level(metadata[4][11]),
      .rotated(selected_high),.current(original_high[4][lane]),.output_valid(high_valid[lane]),.digit(digit_high[lane]));
  end endgenerate
  assign output_valid=low_valid[0];
  assign output_context=metadata[9][17:14];assign output_row=metadata[9][13:11];assign output_half=metadata[9][10];
  integer delay;
  always @(posedge clk)begin
    metadata[0]<={input_context,input_row,input_half,input_exponent};
    for(delay=1;delay<10;delay=delay+1)metadata[delay]<=metadata[delay-1];
    if(reset)fault<=0;
    else if(input_valid && (input_context>=12 || input_row>=6))fault<=1;
  end
endmodule

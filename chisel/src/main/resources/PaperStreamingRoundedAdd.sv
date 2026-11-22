// Exact signed RNE(a +/- b). Fold the low-part carry and rounding increment
// into the high-part sum instead of constructing and then incrementing a
// separate full-width result. SUBTRACT implements a-b without narrowing -b.
(* use_dsp="no" *) module PaperStreamingRoundedAdd #(
  // PIPELINE=1 adds a kept fabric register on the result (one more cycle).
  parameter integer WIDTH=63, OUTPUT_WIDTH=32, SHIFT=28, SUBTRACT=0, PIPELINE=0
)(
  input wire clk,reset,input_valid,
  input wire signed [WIDTH-1:0] a,b,
  output reg output_valid,overflow,
  output reg signed [OUTPUT_WIDTH-1:0] result
);
  localparam integer HIGH_WIDTH=WIDTH+2-SHIFT;
  wire signed [WIDTH-1:0] effective_b=SUBTRACT!=0?~b:b;
  wire [SHIFT:0] low_sum={1'b0,a[SHIFT-1:0]}+{1'b0,effective_b[SHIFT-1:0]}+(SHIFT+1)'(SUBTRACT);
  wire high_lsb=a[SHIFT]^effective_b[SHIFT]^low_sum[SHIFT];
  wire sticky;
  generate if(SHIFT==1)begin : no_sticky
    assign sticky=0;
  end else begin : with_sticky
    assign sticky=|low_sum[SHIFT-2:0];
  end endgenerate
  wire increment=low_sum[SHIFT-1] && (sticky || high_lsb);
  wire [1:0] adjustment={1'b0,low_sum[SHIFT]}+{1'b0,increment};
  wire signed [HIGH_WIDTH-1:0] high_a={{2{a[WIDTH-1]}},a[WIDTH-1:SHIFT]};
  wire signed [HIGH_WIDTH-1:0] high_b={{2{effective_b[WIDTH-1]}},effective_b[WIDTH-1:SHIFT]};
  (* use_dsp="no" *) wire signed [HIGH_WIDTH-1:0] rounded=
    high_a+high_b+$signed({{(HIGH_WIDTH-2){1'b0}},adjustment});
  wire rounded_overflow=rounded!={{(HIGH_WIDTH-OUTPUT_WIDTH){rounded[OUTPUT_WIDTH-1]}},rounded[OUTPUT_WIDTH-1:0]};
  generate if(PIPELINE!=0)begin : staged
    // The sum is captured next to the adder; the second register is free to
    // be absorbed into, or placed beside, the consumer.
    (* dont_touch="true" *) reg signed [OUTPUT_WIDTH-1:0] result_q;
    (* dont_touch="true" *) reg overflow_q;
    reg valid_q;
    always @(posedge clk)begin
      if(reset)begin valid_q<=0;output_valid<=0;end
      else begin valid_q<=input_valid;output_valid<=valid_q;end
      result_q<=rounded[OUTPUT_WIDTH-1:0];overflow_q<=rounded_overflow;
      result<=result_q;overflow<=overflow_q;
    end
  end else begin : direct
    always @(posedge clk)begin
      if(reset)output_valid<=0;else output_valid<=input_valid;
      result<=rounded[OUTPUT_WIDTH-1:0];overflow<=rounded_overflow;
    end
  end endgenerate
  initial if(WIDTH<3 || SHIFT<1 || SHIFT>=WIDTH || OUTPUT_WIDTH<2 || OUTPUT_WIDTH>HIGH_WIDTH ||
             (SUBTRACT!=0 && SUBTRACT!=1))$fatal(1,"invalid rounded-add geometry");
endmodule

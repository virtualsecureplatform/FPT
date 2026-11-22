// One forward-twist lane without multipliers. The operand is an 8-bit digit,
// so each digit selects its two exact products with the lane's twiddle from a
// block ROM addressed by {phase,digit}. Results and latency (4+PAD) are those
// of the multiplier lane for inputs of the form digit<<23.
module PaperStreamingTwistRomLane #(
  parameter integer LANES=128, INDEX=0, DIGIT_REVERSED=0, PAD=0
)(
  input wire clk, reset, input_valid,
  input wire [$clog2(256/LANES)-1:0] phase,
  input wire [7:0] digit_real,digit_imag,
  output wire output_valid,overflow,
  output wire signed [31:0] result_real,result_imag
);
  `include "PaperStreamingTwiddles.svh"
  localparam integer PHASES=256/LANES,DEPTH=3+PAD;
  function automatic integer table_index(input integer p);
    if(DIGIT_REVERSED!=0)
      table_index=((p*LANES+INDEX)%4)*64+(((p*LANES+INDEX)/4)%4)*16+(((p*LANES+INDEX)/16)%4)*4+(p*LANES+INDEX)/64;
    else table_index=p*LANES+INDEX;
  endfunction
  // {digit*sin, digit*cos}, each exact in 36 signed bits (|digit|<=128, |twiddle|<=2^26).
  (* rom_style="block" *) reg [71:0] products [0:PHASES*256-1];
  integer init_phase,init_digit;
  reg signed [63:0] init_value,init_cos,init_sin,init_real,init_imag;
  initial for(init_phase=0;init_phase<PHASES;init_phase=init_phase+1)begin
    init_cos=64'(PAPER_TWIST_COS[table_index(init_phase)]);
    init_sin=64'(PAPER_TWIST_SIN[table_index(init_phase)]);
    for(init_digit=0;init_digit<256;init_digit=init_digit+1)begin
      init_value=init_digit<128?64'(init_digit):64'(init_digit)-64'sd256;
      init_real=init_value*init_cos;init_imag=init_value*init_sin;
      products[init_phase*256+init_digit]={init_imag[35:0],init_real[35:0]};
    end
  end
  reg [71:0] read_real,read_imag,held_real,held_imag;
  (* shreg_extract="no" *) reg signed [36:0] sum_real [0:PAD],sum_imag [0:PAD];
  reg [DEPTH-1:0] valid;
  integer delay;
  always @(posedge clk)begin
    if(reset)valid<=0;else valid<=DEPTH'({valid,input_valid});
    read_real<=products[{phase,digit_real}];read_imag<=products[{phase,digit_imag}];
    held_real<=read_real;held_imag<=read_imag;
    // (a+ib)(c+is): real=ac-bs, imag=as+bc.
    sum_real[0]<=$signed({held_real[35],held_real[35:0]})-$signed({held_imag[71],held_imag[71:36]});
    sum_imag[0]<=$signed({held_real[71],held_real[71:36]})+$signed({held_imag[35],held_imag[35:0]});
    for(delay=1;delay<=PAD;delay=delay+1)begin sum_real[delay]<=sum_real[delay-1];sum_imag[delay]<=sum_imag[delay-1];end
  end
  wire real_valid,imag_valid,real_overflow,imag_overflow;
  // digit<<23 times an F=26 twiddle, requantized by 26: round the product by 3.
  PaperStreamingRequantize #(.INPUT_WIDTH(37),.OUTPUT_WIDTH(32),.SHIFT(3)) real_round(
    .clk(clk),.reset(reset),.input_valid(valid[DEPTH-1]),.value(sum_real[PAD]),
    .output_valid(real_valid),.overflow(real_overflow),.result(result_real));
  PaperStreamingRequantize #(.INPUT_WIDTH(37),.OUTPUT_WIDTH(32),.SHIFT(3)) imag_round(
    .clk(clk),.reset(reset),.input_valid(valid[DEPTH-1]),.value(sum_imag[PAD]),
    .output_valid(imag_valid),.overflow(imag_overflow),.result(result_imag));
  assign output_valid=real_valid;assign overflow=real_overflow || imag_overflow;
  wire unused_valid=imag_valid;
endmodule

// Forward fold twist or inverse untwist; four registered stages, F=26
// constants, with rounding at the declared 32-bit boundary.
// ROM=1 (forward twist only) uses PaperStreamingTwistRomLane: no DSPs, and
// any operand that is not a sign-extended digit<<23 raises fault.
module PaperStreamingTwist #(
  parameter integer LANES=128, MODE=1, NATIVE=0, DIGIT_REVERSED=0, PRODUCT_PIPELINE=0, ROM=0
)(
  input wire clk, reset, input_valid,
  input wire signed [31:0] input_real [0:LANES-1],input_imag [0:LANES-1],
  output wire output_valid,
  output wire signed [31:0] output_real [0:LANES-1],output_imag [0:LANES-1],
  output reg fault
);
  reg [$clog2(256/LANES)-1:0] phase;
  wire [LANES-1:0] real_valid,imag_valid,real_overflow,imag_overflow;
  assign output_valid=real_valid[0];
  wire [LANES-1:0] format_fault;
  genvar lane;
  generate for(lane=0;lane<LANES;lane=lane+1)begin : lanes
   if(ROM!=0)begin : rom
    wire lane_overflow;
    PaperStreamingTwistRomLane #(.LANES(LANES),.INDEX(lane),.DIGIT_REVERSED(DIGIT_REVERSED),.PAD(PRODUCT_PIPELINE)) twiddle(
      .clk(clk),.reset(reset),.input_valid(input_valid),.phase(phase),
      .digit_real(input_real[lane][30:23]),.digit_imag(input_imag[lane][30:23]),
      .output_valid(real_valid[lane]),.overflow(lane_overflow),
      .result_real(output_real[lane]),.result_imag(output_imag[lane]));
    assign imag_valid[lane]=real_valid[lane];
    assign real_overflow[lane]=lane_overflow;assign imag_overflow[lane]=0;
    assign format_fault[lane]=input_real[lane][31]!=input_real[lane][30] || (|input_real[lane][22:0]) ||
                              input_imag[lane][31]!=input_imag[lane][30] || (|input_imag[lane][22:0]);
   end else begin : multiply
    assign format_fault[lane]=0;
    wire product_valid;
    wire signed [61:0] product_real,product_imag;
    PaperStreamingTwiddleProduct #(.LANES(LANES),.INDEX(lane),.MODE(MODE),.NATIVE(NATIVE),.DIGIT_REVERSED(DIGIT_REVERSED),.PRODUCT_PIPELINE(PRODUCT_PIPELINE)) twiddle(
      .clk(clk),.reset(reset),.input_valid(input_valid),.phase(phase),
      .ar(input_real[lane]),.ai(input_imag[lane]),.output_valid(product_valid),.pr(product_real),.pi(product_imag),.overflow());
    PaperStreamingRequantize #(.INPUT_WIDTH(62),.OUTPUT_WIDTH(32),.SHIFT(26)) real_round(
      .clk(clk),.reset(reset),.input_valid(product_valid),.value(product_real),
      .output_valid(real_valid[lane]),.overflow(real_overflow[lane]),.result(output_real[lane]));
    PaperStreamingRequantize #(.INPUT_WIDTH(62),.OUTPUT_WIDTH(32),.SHIFT(26)) imag_round(
      .clk(clk),.reset(reset),.input_valid(product_valid),.value(product_imag),
      .output_valid(imag_valid[lane]),.overflow(imag_overflow[lane]),.result(output_imag[lane]));
   end
  end endgenerate
  always @(posedge clk) begin
    if(reset)begin phase<=0;fault<=0;end
    else begin
      if(input_valid)phase<=phase+1'b1;
      else if(phase!=0)fault<=1;
      if(output_valid && ((|real_overflow) || (|imag_overflow)))fault<=1;
      if(input_valid && (|format_fault))fault<=1;
    end
  end
  initial if((LANES!=64 && LANES!=128) || (MODE!=1 && MODE!=2) || (ROM!=0 && MODE!=1))
    $fatal(1,"invalid streaming twist geometry");
endmodule

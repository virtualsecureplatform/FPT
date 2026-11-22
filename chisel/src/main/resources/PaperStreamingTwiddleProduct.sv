// Compile-time twiddle selection for a time-folded FFT lane.
// MODE: 0 radix-4 leg, 1 forward twist, 2 inverse untwist.
module PaperStreamingTwiddleProduct #(
  parameter integer LANES=128, STAGE=0, INDEX=0, LEG=0, INVERSE=0, MODE=0, NATIVE=0, DIGIT_REVERSED=0,
  parameter integer LEG_GUARD=-1, P_WIDTH=LEG_GUARD<0?62:33+LEG_GUARD, PRODUCT_PIPELINE=0
)(
  input wire clk, reset, input_valid,
  input wire [$clog2(256/LANES)-1:0] phase,
  input wire signed [31:0] ar,ai,
  output wire output_valid,
  output wire signed [P_WIDTH-1:0] pr,pi,
  output wire overflow
);
  `include "PaperStreamingTwiddles.svh"
  localparam integer PHASES=256/LANES;
  localparam integer DEPTH=3+PRODUCT_PIPELINE;
  initial if(PRODUCT_PIPELINE<0 || PRODUCT_PIPELINE>2 || (LEG_GUARD<0 && PRODUCT_PIPELINE>1))$fatal(1,"unsupported product pipeline");
  localparam integer QUARTER=1<<(2*STAGE);
  localparam integer OFFSET=4*(QUARTER-1)/3;
  localparam integer SCALE_SHIFT=LEG_GUARD<0?26:LEG_GUARD;
  function automatic integer table_index(input integer p);
    if(MODE==2 && NATIVE!=0)table_index=(p*LANES+INDEX)/4+((p*LANES+INDEX)%4)*64;
    else if(MODE==1 && DIGIT_REVERSED!=0)
      table_index=((p*LANES+INDEX)%4)*64+(((p*LANES+INDEX)/4)%4)*16+(((p*LANES+INDEX)/16)%4)*4+(p*LANES+INDEX)/64;
    else if(MODE!=0)table_index=p*LANES+INDEX;
    else table_index=OFFSET+LEG*QUARTER+(STAGE==3?p*(LANES/4)+INDEX:INDEX%QUARTER);
  endfunction
  function automatic signed [27:0] coefficient_real(input integer p);
    if(MODE!=0)coefficient_real=PAPER_TWIST_COS[table_index(p)];
    else coefficient_real=PAPER_FFT_COS[table_index(p)];
  endfunction
  function automatic signed [27:0] coefficient_imag(input integer p);
    if(MODE==1)coefficient_imag=PAPER_TWIST_SIN[table_index(p)];
    else if(MODE==2)coefficient_imag=-PAPER_TWIST_SIN[table_index(p)];
    else if(INVERSE!=0)coefficient_imag=-PAPER_FFT_SIN[table_index(p)];
    else coefficient_imag=PAPER_FFT_SIN[table_index(p)];
  endfunction
  function automatic integer all_trivial();
    integer p,c,s;
    begin
      all_trivial=1;
      for(p=0;p<PHASES;p=p+1) begin
        c=int'(coefficient_real(p));s=int'(coefficient_imag(p));
        if(!((s==0 && (c==67108864 || c==-67108864)) ||
             (c==0 && (s==67108864 || s==-67108864))))all_trivial=0;
      end
    end
  endfunction
  function automatic integer any_trivial();
    integer p,c,s;
    begin
      any_trivial=0;
      for(p=0;p<PHASES;p=p+1)begin
        c=int'(coefficient_real(p));s=int'(coefficient_imag(p));
        if((s==0 && (c==67108864 || c==-67108864)) ||
           (c==0 && (s==67108864 || s==-67108864)))any_trivial=1;
      end
    end
  endfunction
  wire signed [27:0] cosine [0:PHASES-1],sine [0:PHASES-1];
  wire [PHASES-1:0] trivial_phase;
  genvar p;
  generate for(p=0;p<PHASES;p=p+1) begin : constants
    assign cosine[p]=coefficient_real(p);
    assign sine[p]=coefficient_imag(p);
    assign trivial_phase[p]=(coefficient_imag(p)==0 &&
        (coefficient_real(p)==67108864 || coefficient_real(p)==-67108864)) ||
        (coefficient_real(p)==0 &&
        (coefficient_imag(p)==67108864 || coefficient_imag(p)==-67108864));
    initial if(!trivial_phase[p] &&
        ((coefficient_real(p)>67108863 || coefficient_real(p)<-67108864) ||
         (coefficient_imag(p)>67108863 || coefficient_imag(p)<-67108864)))
      $fatal(1,"nontrivial twiddle does not fit signed 27 bits");
  end
  wire trivial_valid;
  wire signed [P_WIDTH-1:0] trivial_real,trivial_imag;
  if(any_trivial()!=0) begin : trivial
    reg signed [32:0] chosen_real,chosen_imag;
    (* shreg_extract="no" *) reg signed [P_WIDTH-1:0] real_pipe [0:DEPTH-1],imag_pipe [0:DEPTH-1];
    reg [DEPTH-1:0] valid;
    always @* begin
      if(cosine[phase]==67108864) begin
        chosen_real={ar[31],ar};chosen_imag={ai[31],ai};
      end else if(cosine[phase]==-67108864) begin
        chosen_real=-$signed({ar[31],ar});chosen_imag=-$signed({ai[31],ai});
      end else if(sine[phase]==67108864) begin
        chosen_real=-$signed({ai[31],ai});chosen_imag={ar[31],ar};
      end else begin
        chosen_real={ai[31],ai};chosen_imag=-$signed({ar[31],ar});
      end
    end
    integer delay;
    always @(posedge clk) begin
      if(reset)valid<=0;
      else valid<=DEPTH'({valid,input_valid});
      real_pipe[0]<=$signed({{(P_WIDTH-33){chosen_real[32]}},chosen_real})<<<SCALE_SHIFT;
      imag_pipe[0]<=$signed({{(P_WIDTH-33){chosen_imag[32]}},chosen_imag})<<<SCALE_SHIFT;
      for(delay=1;delay<DEPTH;delay=delay+1) begin
        real_pipe[delay]<=real_pipe[delay-1];imag_pipe[delay]<=imag_pipe[delay-1];
      end
    end
    assign trivial_valid=valid[DEPTH-1];assign trivial_real=real_pipe[DEPTH-1];assign trivial_imag=imag_pipe[DEPTH-1];
  end else begin : no_trivial
    assign trivial_valid=0;assign trivial_real=0;assign trivial_imag=0;
  end
  if(all_trivial()!=0)begin : trivial_output
    assign output_valid=trivial_valid;assign pr=trivial_real;assign pi=trivial_imag;
    assign overflow=0;
  end else begin : general_rotation
    wire general_valid;
    wire signed [P_WIDTH-1:0] general_real,general_imag;
    wire [1:0] general_overflow;
    wire signed [26:0] general_cosine=trivial_phase[phase]?27'sd0:cosine[phase][26:0];
    wire signed [26:0] general_sine=trivial_phase[phase]?27'sd0:sine[phase][26:0];
    if(LEG_GUARD<0)begin : full_product
      PaperStreamingComplexProductSplit #(.A_WIDTH(32),.B_WIDTH(27),.P_WIDTH(P_WIDTH),.PIPELINE(PRODUCT_PIPELINE)) product(
      .clk(clk),.reset(reset),.input_valid(input_valid),.ar(ar),.ai(ai),
      .br(general_cosine),.bi(general_sine),.output_valid(general_valid),.pr(general_real),.pi(general_imag));
      assign general_overflow=0;
    end else begin : rounded_product
      PaperStreamingComplexProductRounded #(.A_WIDTH(32),.B_WIDTH(27),.OUTPUT_WIDTH(P_WIDTH),.SHIFT(26-LEG_GUARD),.PIPELINE(PRODUCT_PIPELINE)) product(
        .clk(clk),.reset(reset),.input_valid(input_valid),.ar(ar),.ai(ai),.br(general_cosine),.bi(general_sine),
        .output_valid(general_valid),.pr(general_real),.pi(general_imag),
        .overflow_real(general_overflow[0]),.overflow_imag(general_overflow[1]));
    end
    assign output_valid=general_valid;
    if(any_trivial()!=0)begin : select_trivial
      reg [DEPTH-1:0] select_pipe;
      always @(posedge clk)begin
        if(reset)select_pipe<=0;else select_pipe<=DEPTH'({select_pipe,trivial_phase[phase]});
      end
      assign pr=select_pipe[DEPTH-1]?trivial_real:general_real;
      assign pi=select_pipe[DEPTH-1]?trivial_imag:general_imag;
      assign overflow=!select_pipe[DEPTH-1] && (|general_overflow);
    end else begin : general_output
      assign pr=general_real;assign pi=general_imag;
      assign overflow=|general_overflow;
    end
  end endgenerate
  initial if((LANES!=64 && LANES!=128) || STAGE<0 || STAGE>3 || MODE<0 || MODE>2)
    $fatal(1,"invalid streaming twiddle geometry");
  initial if((LEG_GUARD!=-1 && LEG_GUARD!=4) || P_WIDTH!=(LEG_GUARD<0?62:33+LEG_GUARD))
    $fatal(1,"unsupported streaming rotation guard");
endmodule

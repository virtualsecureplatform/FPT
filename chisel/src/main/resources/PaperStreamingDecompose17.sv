// Five four-bit slices (last slice one bit) compute the exact Set-I gadget
// digits on the 17-bit Torus grid. No 32-bit rotation/subtraction is needed.
module PaperStreamingDecompose17(
  input wire clk,reset,input_valid,negate,level,
  input wire [16:0] rotated,current,
  output wire output_valid,
  output wire signed [7:0] digit
);
  (* shreg_extract="no" *) reg [16:0] a_pipe [0:4],b_pipe [0:4],result_pipe [0:4];
  reg [1:0] carry_pipe [0:4];
  reg [4:0] valid_pipe,level_pipe;
  genvar stage;
  generate for(stage=0;stage<5;stage=stage+1)begin : slices
    localparam integer WIDTH=stage==4?1:4;
    localparam integer OFFSET=(32'h00010101>>(stage*4))&((1<<WIDTH)-1);
    wire [16:0] a,b,previous;
    wire [1:0] carry_in;
    if(stage==0)begin : first
      assign a=rotated^{17{negate}};assign b=~current;assign previous=0;
      assign carry_in=2'd1+{1'b0,negate};
    end else begin : following
      assign a=a_pipe[stage-1];assign b=b_pipe[stage-1];assign previous=result_pipe[stage-1];
      assign carry_in=carry_pipe[stage-1];
    end
    wire [5:0] sum={{(6-WIDTH){1'b0}},a[stage*4+:WIDTH]}+
      {{(6-WIDTH){1'b0}},b[stage*4+:WIDTH]}+6'(OFFSET)+{4'b0,carry_in};
    always @(posedge clk)begin
      a_pipe[stage]<=a;b_pipe[stage]<=b;
      carry_pipe[stage]<=sum[WIDTH+:2];
      result_pipe[stage]<=previous|(17'(sum[WIDTH-1:0])<<(stage*4));
    end
  end endgenerate
  always @(posedge clk)begin
    if(reset)valid_pipe<=0;else valid_pipe<={valid_pipe[3:0],input_valid};
    level_pipe<={level_pipe[3:0],level};
  end
  assign output_valid=valid_pipe[4];
  assign digit=(level_pipe[4]?result_pipe[4][8:1]:result_pipe[4][16:9])^8'h80;
endmodule

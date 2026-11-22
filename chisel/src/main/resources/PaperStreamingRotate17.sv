// Cyclic coefficient rotation, five registered stages of 2,2,2,2,1 bits.
// Negacyclic signs are applied by the downstream modular decomposer.
// LOW_PAIRS=1 builds only output points with point%4<2. Every stage after
// the first shifts by a multiple of four, so those outputs depend only on
// the same residues of earlier stages and half of the network is omitted;
// the other output points are then undriven and must not be used.
module PaperStreamingRotate17 #(parameter integer LOW_PAIRS=0)(
  input wire clk,reset,input_valid,
  input wire [8:0] exponent,
  input wire [16:0] input_code [0:511],
  output wire output_valid,
  output wire [16:0] output_code [0:511]
);
  (* shreg_extract="no" *) reg [16:0] rotated [0:4][0:511];
  reg [8:0] exponent_pipe [0:4];
  reg [4:0] valid_pipe;
  genvar stage,point,choice;
  generate for(stage=0;stage<5;stage=stage+1)begin : stages
    localparam integer BITS=stage==4?1:2;
    wire [BITS-1:0] select_bits;
    if(stage==0)begin : first_control
      assign select_bits=exponent[0+:BITS];
    end else begin : following_control
      assign select_bits=exponent_pipe[stage-1][stage*2+:BITS];
    end
    for(point=0;point<512;point=point+1)if(LOW_PAIRS==0 || point%4<2)begin : points
      wire [16:0] choices [0:(1<<BITS)-1];
      for(choice=0;choice<(1<<BITS);choice=choice+1)begin : sources
        localparam integer SOURCE=(point+512-(choice<<(stage*2)))%512;
        if(stage==0)begin : first_data
          assign choices[choice]=input_code[SOURCE];
        end else begin : following_data
          assign choices[choice]=rotated[stage-1][SOURCE];
        end
      end
      always @(posedge clk)rotated[stage][point]<=choices[select_bits];
    end
  end
  for(point=0;point<512;point=point+1)begin : outputs
    assign output_code[point]=rotated[4][point];
  end endgenerate
  integer delay;
  always @(posedge clk)begin
    if(reset)valid_pipe<=0;else valid_pipe<={valid_pipe[3:0],input_valid};
    exponent_pipe[0]<=exponent;
    for(delay=1;delay<5;delay=delay+1)exponent_pipe[delay]<=exponent_pipe[delay-1];
  end
  assign output_valid=valid_pipe[4];
endmodule

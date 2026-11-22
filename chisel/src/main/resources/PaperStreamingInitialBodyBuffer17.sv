// One prefetched initial-body batch. Serial natural-point writes are transposed
// into 128 banks; a parallel inverse-native chunk can then load the state store
// or replace a final-step body after sample capture. Only validity resets.
module PaperStreamingInitialBodyBuffer17(
  input wire clk,reset,
  input wire input_valid,
  output wire input_ready,
  input wire [3:0] input_context,
  input wire [8:0] input_point,
  input wire [16:0] input_code,
  output reg full,drained,
  input wire read_valid,
  output wire read_ready,
  input wire [3:0] read_context,
  input wire [1:0] read_chunk,
  output wire [16:0] read_low [0:63],read_high [0:63],
  output reg fault
);
  reg [3:0] expected_input_context,expected_read_context;
  reg [8:0] expected_point;
  reg [1:0] expected_chunk;
  wire input_legal=input_context==expected_input_context && input_point==expected_point;
  wire read_legal=read_context==expected_read_context && read_chunk==expected_chunk;
  wire [6:0] write_bank={input_point[3:0],input_point[7:6],input_point[8]};
  wire [5:0] write_address={input_context,input_point[5:4]};
  wire [5:0] read_address=read_context<12?{read_context,read_chunk}:6'd0;
  assign input_ready=!full && !fault && !reset;
  assign read_ready=full && read_legal && !fault && !reset;
  genvar bank;
  generate for(bank=0;bank<128;bank=bank+1)begin : banks
    (* ram_style="distributed" *) reg [16:0] words [0:47];
    always @(posedge clk)if(input_valid && input_ready && input_legal && write_bank==7'(bank))
      words[write_address]<=input_code;
    if(bank%2==0)assign read_low[bank/2]=words[read_address];
    else assign read_high[bank/2]=words[read_address];
  end endgenerate
  always @(posedge clk)begin
    if(reset)begin
      full<=0;drained<=0;fault<=0;expected_input_context<=0;expected_point<=0;
      expected_read_context<=0;expected_chunk<=0;
    end else begin
      drained<=0;
      if(input_valid && input_ready)begin
        if(!input_legal)fault<=1;
        else if(input_point==511)begin
          expected_point<=0;
          if(input_context==11)begin full<=1;expected_input_context<=0;end
          else expected_input_context<=expected_input_context+1'b1;
        end else expected_point<=expected_point+1'b1;
      end
      if(read_valid)begin
        if(!read_ready)fault<=1;
        else if(read_chunk==3)begin
          expected_chunk<=0;
          if(read_context==11)begin full<=0;drained<=1;expected_read_context<=0;end
          else expected_read_context<=expected_read_context+1'b1;
        end else expected_chunk<=expected_chunk+1'b1;
      end
    end
  end
endmodule

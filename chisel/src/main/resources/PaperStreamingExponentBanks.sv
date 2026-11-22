// Two context-major input frames, transposed to step-major compute reads.
// Loading the inactive frame overlaps the current bootstrap. Payload RAM is
// never reset; ready/active bits prevent access to stale or incomplete frames.
module PaperStreamingExponentBanks #(
  parameter integer STEPS=586, ADDRESS_BITS=$clog2(12*STEPS)
)(
  input wire clk,reset,
  input wire load_valid,
  output wire load_ready,
  input wire [3:0] load_context,
  input wire [9:0] load_step,load_value,
  input wire activate,retire,
  output wire activate_ready,
  input wire read_valid,
  input wire [ADDRESS_BITS-1:0] read_address,
  output reg [9:0] read_value,
  output reg fault
);
  localparam integer WORDS=12*STEPS,PHYSICAL_BITS=$clog2(2*WORDS);
  (* ram_style="block" *) reg [9:0] words [0:2*WORDS-1];
  reg [1:0] ready;
  reg active,active_bank,load_bank,next_bank;
  reg [3:0] expected_context;
  reg [9:0] expected_step;
  wire legal=load_context==expected_context && load_step==expected_step;
  wire [PHYSICAL_BITS-1:0] write_address=(load_bank?PHYSICAL_BITS'(WORDS):'0)+
    (PHYSICAL_BITS'(load_step)<<3)+(PHYSICAL_BITS'(load_step)<<2)+PHYSICAL_BITS'(load_context);
  wire [PHYSICAL_BITS-1:0] physical_read=(active_bank?PHYSICAL_BITS'(WORDS):'0)+PHYSICAL_BITS'(read_address);
  assign load_ready=!ready[load_bank] && (!active || load_bank!=active_bank) && !fault && !reset;
  assign activate_ready=!active && ready[next_bank] && !fault && !reset;
  always @(posedge clk)begin
    if(load_valid && load_ready && legal)words[write_address]<=load_value;
    if(read_valid && active && read_address<ADDRESS_BITS'(WORDS))read_value<=words[physical_read];
    if(reset)begin
      ready<=0;active<=0;active_bank<=0;load_bank<=0;next_bank<=0;
      expected_context<=0;expected_step<=0;fault<=0;
    end else begin
      if(load_valid && load_ready)begin
        if(!legal)fault<=1;
        else if(load_step==10'(STEPS-1))begin
          expected_step<=0;
          if(load_context==11)begin ready[load_bank]<=1;load_bank<=!load_bank;expected_context<=0;end
          else expected_context<=expected_context+1'b1;
        end else expected_step<=expected_step+1'b1;
      end
      if(activate)begin
        if(!activate_ready)fault<=1;
        else begin active<=1;active_bank<=next_bank;ready[next_bank]<=0;next_bank<=!next_bank;end
      end
      if(retire)begin
        if(!active)fault<=1;else active<=0;
      end
      if(read_valid && (!active || read_address>=ADDRESS_BITS'(WORDS)))fault<=1;
    end
  end
  initial if(STEPS<1 || STEPS>586)$fatal(1,"invalid exponent-bank geometry");
endmodule

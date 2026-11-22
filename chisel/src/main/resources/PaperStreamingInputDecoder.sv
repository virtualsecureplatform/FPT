// Version-2 input frames: 512 raw LUT words, then twelve raw TLWEs. DMA reads
// the 64-byte-rounded frame stride. The last beat's unused words must be zero.
// A single registered beat holds its payload across arbitrary consumer stalls.
module PaperStreamingInputDecoder #(
  parameter integer STEPS=586,
  parameter integer REFILL_ON_POP=1
)(
  input wire clk,reset,
  input wire input_valid,input_last,
  output wire input_ready,
  input wire [511:0] input_data,
  output wire lookup_valid,
  input wire lookup_ready,
  output wire [8:0] lookup_index,
  output wire [31:0] lookup_value,
  output wire tlwe_valid,
  input wire tlwe_ready,
  output wire [3:0] tlwe_context,
  output wire [9:0] tlwe_word,
  output wire [31:0] tlwe_value,
  output reg frame_done,fault
);
  localparam integer WORDS=512+12*(STEPS+1),BEATS=(WORDS+15)/16,LAST_WORDS=WORDS%16==0?16:WORDS%16;
  reg [511:0] payload;
  reg holding,in_lookup;
  reg [3:0] word_index;
  reg [4:0] words_left;
  reg [8:0] index,receive_beat;
  reg [3:0] context_index;
  reg [9:0] step_index;
  wire pop=holding && (in_lookup?lookup_ready:tlwe_ready) && !fault && !reset;
  // Disabling look-ahead refill isolates DMA ready from downstream readiness.
  // The registered holding flag then permits refill on the following cycle.
  assign input_ready=(!holding || (REFILL_ON_POP!=0 && pop && words_left==1)) && !fault && !reset;
  assign lookup_valid=holding && in_lookup && !fault && !reset;
  assign tlwe_valid=holding && !in_lookup && !fault && !reset;
  assign lookup_index=index;
  assign tlwe_context=context_index;assign tlwe_word=step_index;
  assign lookup_value=payload[word_index*32+:32];assign tlwe_value=lookup_value;
  wire bad_padding;
  generate if(LAST_WORDS<16)begin : padding_check
    assign bad_padding=|input_data[511:LAST_WORDS*32];
  end else begin : no_padding
    assign bad_padding=0;
  end endgenerate
  always @(posedge clk)begin
    if(reset)begin
      holding<=0;in_lookup<=1;word_index<=0;words_left<=0;index<=0;receive_beat<=0;
      context_index<=0;step_index<=0;frame_done<=0;fault<=0;
    end else begin
      frame_done<=0;
      if(pop)begin
        word_index<=word_index+1'b1;words_left<=words_left-1'b1;
        if(words_left==1)holding<=0;
        if(in_lookup)begin
          if(index==511)begin in_lookup<=0;index<=0;end else index<=index+1'b1;
        end else if(step_index==10'(STEPS))begin
          step_index<=0;
          if(context_index==11)begin context_index<=0;in_lookup<=1;frame_done<=1;end
          else context_index<=context_index+1'b1;
        end else step_index<=step_index+1'b1;
      end
      if(input_valid && input_ready)begin
        payload<=input_data;holding<=1;word_index<=0;
        words_left<=receive_beat==9'(BEATS-1)?5'(LAST_WORDS):5'd16;
        if(input_last!=(receive_beat==9'(BEATS-1)) || (receive_beat==9'(BEATS-1) && bad_padding))fault<=1;
        if(receive_beat==9'(BEATS-1))receive_beat<=0;else receive_beat<=receive_beat+1'b1;
      end
    end
  end
  initial if(STEPS<1 || STEPS>586)$fatal(1,"invalid input DMA geometry");
endmodule

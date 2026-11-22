// Boundary DMA adapter: 512-bit beats to 9/10 densely packed 56-bit complex
// key words. Only six carry bytes and one output beat are retained.
module PaperStreamingKeyUnpack(
  input wire clk, reset,
  input wire input_valid, input_last,
  output wire input_ready,
  input wire [511:0] input_data,
  output reg output_valid, output_last, frame_error,
  input wire output_ready,
  output reg [3:0] output_count,
  output reg [559:0] output_words
);
  reg [2:0] carry_bytes;
  reg [47:0] carry;
  wire [567:0] shifted = {56'b0,input_data} << {carry_bytes,3'b000};
  wire [567:0] combined = shifted | {{520{1'b0}},(carry_bytes==0?48'b0:carry)};
  assign input_ready = !output_valid || output_ready;
  always @(posedge clk) begin
    if(reset) begin
      carry_bytes<=0;
      output_valid<=0;
    end else if(input_ready) begin
      output_valid<=input_valid;
      if(input_valid) begin
        output_count<=carry_bytes==6?4'd10:4'd9;
        output_words<=combined[559:0];
        output_last<=input_last;
        frame_error<=input_last && carry_bytes!=6;
        if(input_last || carry_bytes==6) begin
          carry_bytes<=0;
        end else begin
          carry_bytes<=carry_bytes+1'b1;
        end
        // 9*56=504; the tenth word consumes all residual bits every seventh beat.
        carry<=combined[551:504];
      end
    end
  end
endmodule

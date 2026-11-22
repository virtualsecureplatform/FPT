// Reads one completed twelve-sample bank and emits 12,300 Torus32 words.
// Sixteen words per 512-bit beat; the final beat has twelve valid words.
// Credits include outstanding RAM replies, so arbitrary output stalls cannot
// overflow the 32-word FIFO. The bank is released after the last beat transfers.
module PaperStreamingSampleStream(
  input wire clk,reset,start,start_bank,
  input wire [1:0] sample_ready,
  output wire start_ready,
  output reg busy,done,
  output wire read_valid,read_bank,
  output wire [3:0] read_context,
  output wire [10:0] read_word,
  input wire response_valid,
  input wire [31:0] response_word,
  output reg release_valid,release_bank,
  output reg output_valid,
  input wire output_ready,
  output wire [511:0] output_data,
  output wire [63:0] output_keep,
  output reg output_last,
  output reg fault
);
  reg bank;
  reg [13:0] issued,received,packed_words;
  reg [3:0] context_index;
  reg [10:0] word_index;
  reg [4:0] write_pointer,read_pointer;
  reg [5:0] occupancy,credits;
  reg [3:0] pack_index;
  (* ram_style="distributed" *) reg [31:0] fifo [0:31];
  reg [31:0] beat [0:15];
  assign start_ready=!busy && sample_ready[start_bank] && !fault && !reset;
  assign read_valid=busy && issued<12300 && credits<32 && !reset;
  assign read_bank=bank;
  assign read_context=context_index;
  assign read_word=word_index;
  wire pop=busy && occupancy!=0 && (!output_valid || output_ready);
  assign output_keep=output_last?64'h0000ffffffffffff:64'hffffffffffffffff;
  genvar word;
  generate for(word=0;word<16;word=word+1)begin : words
    assign output_data[word*32+:32]=beat[word];
  end endgenerate
  always @(posedge clk)begin
    if(reset)begin
      busy<=0;done<=0;output_valid<=0;output_last<=0;fault<=0;
      release_valid<=0;release_bank<=0;bank<=0;
      issued<=0;received<=0;packed_words<=0;context_index<=0;word_index<=0;
      write_pointer<=0;read_pointer<=0;occupancy<=0;credits<=0;pack_index<=0;
    end else begin
      done<=0;release_valid<=0;
      case({response_valid,pop})
        2'b10:occupancy<=occupancy+1'b1;
        2'b01:occupancy<=occupancy-1'b1;
        default:begin end
      endcase
      case({read_valid,pop})
        2'b10:credits<=credits+1'b1;
        2'b01:credits<=credits-1'b1;
        default:begin end
      endcase
      if(read_valid)begin
        issued<=issued+1'b1;
        if(word_index==1024)begin word_index<=0;context_index<=context_index+1'b1;end
        else word_index<=word_index+1'b1;
      end
      if(response_valid)begin
        if(!busy || received>=issued || occupancy==32)fault<=1;
        else begin
          fifo[write_pointer]<=response_word;write_pointer<=write_pointer+1'b1;
          received<=received+1'b1;
        end
      end
      if(output_valid && output_ready)begin
        output_valid<=0;
        if(output_last)begin
          busy<=0;done<=1;release_valid<=1;release_bank<=bank;output_last<=0;
        end
      end
      if(pop)begin
        beat[pack_index]<=fifo[read_pointer];read_pointer<=read_pointer+1'b1;
        packed_words<=packed_words+1'b1;
        if(pack_index==15 || packed_words==12299)begin
          pack_index<=0;output_valid<=1;output_last<=packed_words==12299;
        end else pack_index<=pack_index+1'b1;
      end
      if(start)begin
        if(!start_ready)fault<=1;
        else begin
          busy<=1;bank<=start_bank;issued<=0;received<=0;packed_words<=0;
          context_index<=0;word_index<=0;write_pointer<=0;read_pointer<=0;
          occupancy<=0;credits<=0;pack_index<=0;output_valid<=0;output_last<=0;
        end
      end
    end
  end
endmodule

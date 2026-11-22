// Two output batches, each containing twelve extracted LWE samples.
// Capture consumes the final CMUX's updated state in inverse-native order.
// The serial read port returns Torus32 words with a fixed three-cycle latency.
// Reserving a batch precedes capture; releasing it follows the last read reply.
module PaperStreamingSampleBuffer17(
  input wire clk,reset,
  input wire reserve_valid,reserve_bank,
  output wire [1:0] available,ready,
  input wire capture_valid,
  input wire [3:0] capture_context,
  input wire [1:0] capture_component,capture_chunk,
  input wire [16:0] capture_low [0:63],capture_high [0:63],
  input wire read_valid,read_bank,
  input wire [3:0] read_context,
  input wire [10:0] read_word,
  output wire response_valid,
  output wire [31:0] response_word,
  input wire release_valid,release_bank,
  output reg fault
);
  reg [1:0] occupied,complete;
  reg capturing,active_bank;
  reg [3:0] expected_context;
  reg [1:0] expected_component,expected_chunk;
  wire capture_legal=capturing && capture_context==expected_context &&
    capture_component==expected_component && capture_chunk==expected_chunk;
  wire read_legal=complete[read_bank] && read_context<12 && read_word<=1024;
  assign available=~occupied;
  assign ready=complete;
  // Final-stage capture is registered once before its sign adjustment and RAM
  // write, keeping that logic away from the coefficient-store update adder.
  reg write_valid,write_bank;
  reg [3:0] write_context;
  reg [1:0] write_component,write_chunk;
  reg [16:0] write_low [0:63],write_high [0:63];
  reg [16:0] bodies [0:1][0:11];
  wire [7:0] write_address={write_bank,write_context,write_component[0],write_chunk};
  // a_i[j] in the extracted LWE sample is -a_i[N-j] except at j=0.
  wire [8:0] source_point=9'd0-read_word[8:0];
  wire [6:0] source_bank={source_point[3:0],source_point[7:6],source_point[8]};
  wire [7:0] read_address={read_bank,read_context,read_word[9],source_point[5:4]};
  wire [16:0] bank_word [0:127];
  genvar lane,fold;
  generate for(lane=0;lane<64;lane=lane+1)begin : lanes
    for(fold=0;fold<2;fold=fold+1)begin : halves
      localparam integer BANK=2*lane+fold;
      // Native index chunk*64+lane maps to chunk*16+lane/4+(lane%4)*64.
      wire point_zero=write_chunk==0 && lane==0 && fold==0;
      wire [16:0] code=fold==0?write_low[lane]:write_high[lane];
      wire [16:0] extracted=point_zero?code:17'd0-code;
      (* ram_style="block" *) reg [16:0] words [0:255];
      reg [16:0] read_data;
      always @(posedge clk)begin
        if(!reset && write_valid && write_component<2)words[write_address]<=extracted;
        if(read_valid && read_legal)read_data<=words[read_address];
      end
      assign bank_word[BANK]=read_data;
    end
    always @(posedge clk)begin
      write_low[lane]<=capture_low[lane];write_high[lane]<=capture_high[lane];
    end
  end endgenerate
  reg [2:0] read_pipeline;
  reg [6:0] bank_select;
  reg body_select;
  reg [16:0] body_word;
  reg [2:0] group_select;
  reg [16:0] group_word [0:7];
  reg [16:0] result;
  reg [2:0] bank_pipeline;
  assign response_valid=read_pipeline[2];
  assign response_word={result,15'b0};
  integer group;
  always @(posedge clk)begin
    bank_select<=source_bank;
    body_select<=read_word==1024;
    if(read_valid && read_legal)body_word<=bodies[read_bank][read_context];
    group_select<=bank_select[6:4];
    for(group=0;group<8;group=group+1)
      group_word[group]<=body_select?body_word:bank_word[group*16+int'(bank_select[3:0])];
    result<=group_word[group_select];
    bank_pipeline<={bank_pipeline[1:0],read_bank};
    write_bank<=active_bank;write_context<=capture_context;
    write_component<=capture_component;write_chunk<=capture_chunk;
    if(reset)begin
      occupied<=0;complete<=0;capturing<=0;active_bank<=0;
      expected_context<=0;expected_component<=0;expected_chunk<=0;
      write_valid<=0;read_pipeline<=0;fault<=0;
    end else begin
      read_pipeline<={read_pipeline[1:0],read_valid && read_legal};
      write_valid<=capture_valid && capture_legal;
      if(reserve_valid)begin
        if(occupied[reserve_bank] || capturing || write_valid)fault<=1;
        else begin
          occupied[reserve_bank]<=1;complete[reserve_bank]<=0;
          capturing<=1;active_bank<=reserve_bank;
          expected_context<=0;expected_component<=0;expected_chunk<=0;
        end
      end
      if(capture_valid)begin
        if(!capture_legal)fault<=1;
        else if(expected_chunk==3)begin
          expected_chunk<=0;
          if(expected_component==2)begin
            expected_component<=0;
            if(expected_context==11)begin capturing<=0;expected_context<=0;end
            else expected_context<=expected_context+1'b1;
          end else expected_component<=expected_component+1'b1;
        end else expected_chunk<=expected_chunk+1'b1;
      end
      if(write_valid && write_component==2 && write_chunk==0)bodies[write_bank][write_context]<=write_low[0];
      if(write_valid && write_context==11 && write_component==2 && write_chunk==3)complete[write_bank]<=1;
      if(read_valid && !read_legal)fault<=1;
      if(release_valid)begin
        if(!complete[release_bank] || (read_valid && read_bank==release_bank) ||
           (read_pipeline[0] && bank_pipeline[0]==release_bank) ||
           (read_pipeline[1] && bank_pipeline[1]==release_bank) ||
           (read_pipeline[2] && bank_pipeline[2]==release_bank))fault<=1;
        else begin occupied[release_bank]<=0;complete[release_bank]<=0;end
      end
    end
  end
endmodule

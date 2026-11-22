// 64-entry 17-bit dual-port memory in the vendor inference template: port A
// writes or reads (output unchanged on a write), port B only reads, both
// with registered outputs. All entries start at zero; entry 36 is the
// store's permanent zero word. Block RAM: distributed RAM routed worse
// on the U280 (build v20 against v19).
module PaperStreamingStateRam17(
  input wire clk,a_enable,a_write,
  input wire [5:0] a_address,
  input wire [16:0] a_write_data,
  output reg [16:0] a_read_data,
  input wire b_enable,
  input wire [5:0] b_address,
  output reg [16:0] b_read_data
);
  (* ram_style="block" *) reg [16:0] words [0:63];
  integer index;
  initial for(index=0;index<64;index=index+1)words[index]=0;
  always @(posedge clk)if(a_enable)begin
    if(a_write)words[a_address]<=a_write_data;
    else a_read_data<=words[a_address];
  end
  always @(posedge clk)if(b_enable)b_read_data<=words[b_address];
endmodule

// 512-coefficient state with a configurable fraction of native lanes in BRAM.
// Each BRAM lane keeps chunks 0/2 and 1/3 in separate bank sets. Native
// chunk order alternates the bank sets, allowing the current feedback read and
// the previous feedback commit to run together. Front reads and feedback each
// have one cycle of latency. Replacement payloads apply at commit, while the
// updated outputs always expose old+delta for final sample capture.
module PaperStreamingMixedCoefficientStore17 #(
  parameter integer BRAM_LANES=112
)(
  input wire clk,reset,
  input wire read_valid,read_initial,
  input wire [3:0] read_context,
  input wire [1:0] read_component,
  output wire read_ready,
  output reg read_response_valid,
  output wire [16:0] read_code [0:511],
  input wire write_valid,write_load,write_initial,
  input wire [3:0] write_context,
  input wire [1:0] write_component,write_chunk,
  input wire [16:0] write_low [0:63],write_high [0:63],
  input wire write_replace,
  input wire [16:0] replacement_low [0:63],replacement_high [0:63],
  output reg commit_valid,commit_load,
  output reg [3:0] commit_context,
  output reg [1:0] commit_component,commit_chunk,
  output wire [16:0] updated_low [0:63],updated_high [0:63],
  output reg fault
);
  reg [3:0] valid_chunks [0:35];
  reg [1:0] expected_chunk;
  reg [3:0] group_context;
  reg [1:0] group_component;
  wire read_legal=read_context<12 && read_component<3;
  wire input_legal=write_context<12 && write_component<3 && write_chunk==expected_chunk &&
    (expected_chunk==0 || (write_context==group_context && write_component==group_component));
  wire [5:0] read_slot=({2'b0,read_context}<<1)+{2'b0,read_context}+{4'b0,read_component};
  wire [5:0] write_slot=({2'b0,write_context}<<1)+{2'b0,write_context}+{4'b0,write_component};
  wire [5:0] commit_slot=({2'b0,commit_context}<<1)+{2'b0,commit_context}+{4'b0,commit_component};
  wire zero_read=read_initial && read_component<2;
  wire [5:0] read_address=zero_read?6'd36:read_slot;
  wire [5:0] update_address=write_load || (write_initial && write_component<2)?6'd36:write_slot;
  assign read_ready=read_legal && (zero_read || valid_chunks[read_slot]==15);
  wire [16:0] old_code [0:127],updated [0:127],stored [0:127];
  reg [16:0] delta [0:127];
  genvar lane,parity,chunk;
  generate for(lane=0;lane<128;lane=lane+1)begin : lanes
    localparam integer INDEX=lane/2,FOLD=lane%2;
    wire [16:0] replacement=FOLD==0?replacement_low[INDEX]:replacement_high[INDEX];
    assign updated[lane]=old_code[lane]+delta[lane];
    assign stored[lane]=write_replace?replacement:updated[lane];
    if(FOLD==0)assign updated_low[INDEX]=updated[lane];
    else assign updated_high[INDEX]=updated[lane];
    always @(posedge clk)delta[lane]<=FOLD==0?write_low[INDEX]:write_high[INDEX];
    if(lane<BRAM_LANES)begin : bram
      wire [16:0] old_parts [0:1];
      for(parity=0;parity<2;parity=parity+1)begin : halves
        // One plain 17-bit dual-port memory per chunk (see StateRam17). A 36-bit word
        // with 18-bit partial writes sharing a port with the feedback read
        // was mis-inferred by Vivado 2023.2 (memories split and merged; only
        // one lane kept its contents on the U280).
        wire committing=commit_valid && commit_chunk[0]==1'(parity);
        wire [16:0] feedback_parts [0:1];
        for(chunk=0;chunk<2;chunk=chunk+1)begin : coefficients
          localparam integer POINT=(2*chunk+parity)*16+INDEX/4+(INDEX%4)*64+FOLD*256;
          // The feedback read runs whenever this half is not committing: its
          // data is consumed only by the commit one cycle after a legal
          // write. commit_valid is itself reset, so no reset is needed here.
          PaperStreamingStateRam17 ram(.clk(clk),
            .a_enable(!committing || commit_chunk[1]==1'(chunk)),.a_write(committing),
            .a_address(committing?commit_slot:update_address),.a_write_data(stored[lane]),
            .a_read_data(feedback_parts[chunk]),
            .b_enable(!reset && read_valid && read_ready),.b_address(read_address),
            .b_read_data(read_code[POINT]));
        end
        assign old_parts[parity]=feedback_parts[commit_chunk[1]];
      end
      assign old_code[lane]=old_parts[commit_chunk[0]];
    end else begin : lutram
      wire [16:0] old_parts [0:3];
      for(chunk=0;chunk<4;chunk=chunk+1)begin : coefficients
        localparam integer POINT=chunk*16+INDEX/4+(INDEX%4)*64+FOLD*256;
        (* ram_style="distributed" *) reg [16:0] words [0:36];
        reg [16:0] front_data,feedback_data;
        initial words[36]=0;
        always @(posedge clk)begin
          if(!reset && read_valid && read_ready)front_data<=words[read_address];
          if(!reset && write_valid && input_legal && write_chunk==2'(chunk))feedback_data<=words[update_address];
          if(!reset && commit_valid && commit_chunk==2'(chunk))words[commit_slot]<=stored[lane];
        end
        assign read_code[POINT]=front_data;
        assign old_parts[chunk]=feedback_data;
      end
      assign old_code[lane]=old_parts[commit_chunk];
    end
  end endgenerate
  integer slot;
  always @(posedge clk)begin
    commit_load<=write_load;commit_context<=write_context;
    commit_component<=write_component;commit_chunk<=write_chunk;
    if(reset)begin
      commit_valid<=0;read_response_valid<=0;expected_chunk<=0;fault<=0;group_context<=0;group_component<=0;
      for(slot=0;slot<36;slot=slot+1)valid_chunks[slot]<=0;
    end else begin
      commit_valid<=write_valid && input_legal;
      read_response_valid<=read_valid && read_ready;
      if(read_valid && !read_ready)fault<=1;
      if(read_valid && commit_valid && read_address==commit_slot)fault<=1;
      if(write_valid)begin
        if(!input_legal)fault<=1;
        else begin
          expected_chunk<=expected_chunk+1'b1;
          if(write_chunk==0)begin group_context<=write_context;group_component<=write_component;end
          if(commit_valid && commit_chunk[0]==write_chunk[0])fault<=1;
        end
      end
      if(commit_valid)begin
        if(commit_chunk==0)valid_chunks[commit_slot]<=1;
        else begin
          if(valid_chunks[commit_slot]!=(4'b0001<<commit_chunk)-4'b0001)fault<=1;
          valid_chunks[commit_slot]<=valid_chunks[commit_slot]|(4'b0001<<commit_chunk);
        end
      end
    end
  end
  initial if(BRAM_LANES<0 || BRAM_LANES>128)$fatal(1,"invalid mixed state BRAM lane count");
endmodule

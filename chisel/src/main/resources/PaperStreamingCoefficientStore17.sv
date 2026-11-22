// Twelve-context GLWE state, packed by coefficient into 36-word local RAMs.
// One permanent zero word supplies initial masks and load overwrite bases;
// reset clears validity only, never the coefficient payload.
// Writes use the inverse FFT's native groups: chunk*64+lane -> j/4+(j%4)*64.
module PaperStreamingCoefficientStore17 #(parameter integer REPLACEMENT=0)(
  input wire clk,reset,
  input wire read_valid,read_initial,
  input wire [3:0] read_context,
  input wire [1:0] read_component,
  output wire read_ready,
  output wire [16:0] read_code [0:511],
  input wire write_valid,write_load,write_initial,
  input wire [3:0] write_context,
  input wire [1:0] write_component,write_chunk,
  input wire [16:0] write_low [0:63],write_high [0:63],
  input wire write_replace,
  input wire [16:0] replacement_low [0:63],replacement_high [0:63],
  output wire [16:0] updated_low [0:63],updated_high [0:63],
  output reg fault
);
  wire read_legal=read_context<12 && read_component<3;
  wire write_legal=write_context<12 && write_component<3;
  wire [5:0] read_slot=({2'b0,read_context}<<1)+{2'b0,read_context}+{4'b0,read_component};
  wire [5:0] write_slot=({2'b0,write_context}<<1)+{2'b0,write_context}+{4'b0,write_component};
  wire [5:0] read_address=read_initial && read_component<2?6'd36:read_slot;
  wire [5:0] update_address=write_load || (write_initial && write_component<2)?6'd36:write_slot;
  reg [3:0] valid_chunks [0:35];
  assign read_ready=read_legal && (read_address==36 || valid_chunks[read_slot]==4'b1111);
  wire [16:0] stored_low [0:63],stored_high [0:63];
  genvar point,lane,chunk;
  generate for(point=0;point<512;point=point+1)begin : storage
    localparam integer NATIVE=4*((point%256)%64)+(point%256)/64;
    localparam integer CHUNK=NATIVE/64,LANE=NATIVE%64;
    (* ram_style="distributed" *) reg [16:0] words [0:36];
    initial words[36]=0;
    assign read_code[point]=words[read_address];
    if(point<256)begin : low_half
      always @(posedge clk)if(!reset && write_valid && write_legal && write_chunk==2'(CHUNK))
        words[write_slot]<=stored_low[LANE];
    end else begin : high_half
      always @(posedge clk)if(!reset && write_valid && write_legal && write_chunk==2'(CHUNK))
        words[write_slot]<=stored_high[LANE];
    end
  end
  for(lane=0;lane<64;lane=lane+1)begin : updates
    assign stored_low[lane]=REPLACEMENT!=0 && write_replace?replacement_low[lane]:updated_low[lane];
    assign stored_high[lane]=REPLACEMENT!=0 && write_replace?replacement_high[lane]:updated_high[lane];
    wire [16:0] old_low [0:3],old_high [0:3];
    for(chunk=0;chunk<4;chunk=chunk+1)begin : choices
      localparam integer POINT=chunk*16+lane/4+(lane%4)*64;
      assign old_low[chunk]=storage[POINT].words[update_address];
      assign old_high[chunk]=storage[POINT+256].words[update_address];
    end
    // Dropping the carry is exactly modular Torus addition on the 17-bit grid.
    assign updated_low[lane]=old_low[write_chunk]+write_low[lane];
    assign updated_high[lane]=old_high[write_chunk]+write_high[lane];
  end endgenerate
  integer slot;
  always @(posedge clk)begin
    if(reset)begin
      fault<=0;
      for(slot=0;slot<36;slot=slot+1)valid_chunks[slot]<=0;
    end else begin
      if(read_valid && !read_ready)fault<=1;
      if(read_valid && write_valid && write_legal && read_address==write_slot)fault<=1;
      if(write_valid)begin
        if(!write_legal)fault<=1;
        else begin
          if(write_chunk==0)valid_chunks[write_slot]<=4'b0001;
          else begin
            if(valid_chunks[write_slot]!=(4'b0001<<write_chunk)-4'b0001)fault<=1;
            valid_chunks[write_slot]<=valid_chunks[write_slot]|(4'b0001<<write_chunk);
          end
        end
      end
    end
  end
endmodule

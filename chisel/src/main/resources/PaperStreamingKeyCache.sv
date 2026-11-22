// Ordinary Q7.21 TGGSW spectra in native butterfly-group order. Nine 56-bit
// complex values occupy seven 72-bit BRAM words, avoiding a 56/72 width loss.
// At most 43 groups x 7 RAMB36, with 32 steps in each bank's depth.
// Physical bank pointers continue across key epochs, including 586 % 32 != 0.
// Each of four loader ports supplies groups p,p+4,..., then the next row/half.
// Reads are synchronous (one cycle); retire accompanies the last context's
// final row/half request, after which that step's storage may be reused.
module PaperStreamingKeyCache #(
  parameter integer STEPS=586, PREFILL_STEPS=8, WRITE_STAGES=0, READ_STAGES=0,
  // WRITE_STAGES=3 with NEAR_GROUPS>0: groups below NEAR_GROUPS take loader
  // words from a separate second-stage copy, so they can sit in another region.
  NEAR_GROUPS=0
)(
  input wire clk,reset,
  input wire load_start_valid,
  input wire [9:0] load_step,
  output wire load_start_ready,
  input wire [3:0] load_valid,
  output wire [3:0] load_ready,
  input wire [503:0] load_data [0:3],
  output reg loaded_valid,
  output reg [9:0] loaded_step,
  output wire initial_ready,initial_window_ready,
  output wire [3:0] load_last,
  input wire read_valid,read_retire,
  input wire [9:0] read_step,
  input wire [2:0] read_row,
  input wire read_half,
  output wire read_ready,
  output reg response_valid,
  output wire signed [27:0] key_real [0:2][0:127],key_imag [0:2][0:127],
  output reg fault
);
  reg [31:0] valid_steps;
  reg [9:0] step_tag [0:31];
  reg loading;
  reg [4:0] read_bank_index,load_bank_index;
  reg [9:0] active_step,expected_load_step;
  reg [3:0] port_done;
  reg [3:0] settle;
  reg response_early;
  // Loader-side copy of the sticky fault (one cycle late), kept separate so
  // the stream handshake does not hang off the cache-wide fault register.
  (* dont_touch="true" *) reg load_fault;
  always @(posedge clk)if(reset)load_fault<=0;else load_fault<=fault;
  reg [5:0] group_index [0:3];
  reg [3:0] slot_index [0:3];
  wire [4:0] read_bank=read_bank_index,load_bank=load_bank_index;
  wire [8:0] read_address=(9'(read_bank)<<3)+(9'(read_bank)<<2)+{5'b0,read_row,read_half};
  assign initial_ready=valid_steps[read_bank] && step_tag[read_bank]==0;
  wire [31:0] window_ready;
  genvar bank_index;
  generate for(bank_index=0;bank_index<32;bank_index=bank_index+1)begin : initial_window
    wire [4:0] offset=5'(bank_index)-read_bank_index;
    assign window_ready[bank_index]=(int'(offset)>=STEPS || int'(offset)>=PREFILL_STEPS)?1'b1:
      valid_steps[bank_index] && step_tag[bank_index]=={5'b0,offset};
  end endgenerate
  assign initial_window_ready=&window_ready;
  assign read_ready=read_step<10'(STEPS) && read_row<6 && valid_steps[read_bank] && step_tag[read_bank]==read_step;
  assign load_start_ready=!loading && load_step<10'(STEPS) && load_step==expected_load_step && !valid_steps[load_bank_index] && !load_fault && !reset;
  wire [503:0] group_words [0:42];
  // Shared loader-word stages for the three-stage write pipeline: one first
  // copy per port, then one copy per side. Kept distinct on purpose.
  wire [503:0] side_data [0:1][0:3];
  localparam integer FAR_COPIES=4, FAR_SPAN=(43-NEAR_GROUPS+FAR_COPIES-1)/FAR_COPIES;
  wire [503:0] far_data [0:FAR_COPIES-1][0:3];
  genvar load_port,far_copy;
  generate for(load_port=0;load_port<4;load_port=load_port+1)begin : ports
    if(WRITE_STAGES==3)begin : shared
      // FAR_COPIES distinct far copies: each serves a few groups only.
      (* dont_touch="true" *) reg [503:0] data_first,data_near;
      (* dont_touch="true" *) reg [503:0] data_far [0:FAR_COPIES-1];
      integer copy;
      always @(posedge clk)begin
        data_first<=load_data[load_port];data_near<=data_first;
        for(copy=0;copy<FAR_COPIES;copy=copy+1)data_far[copy]<=data_first;
      end
      for(far_copy=0;far_copy<FAR_COPIES;far_copy=far_copy+1)begin : far
        assign far_data[far_copy][load_port]=data_far[far_copy];
      end
      assign side_data[0][load_port]=data_far[0];assign side_data[1][load_port]=data_near;
    end else begin : unused
      assign side_data[0][load_port]=0;assign side_data[1][load_port]=0;
      for(far_copy=0;far_copy<FAR_COPIES;far_copy=far_copy+1)begin : far
        assign far_data[far_copy][load_port]=0;
      end
    end
  end endgenerate
  genvar group,word,lane;
  generate for(group=0;group<43;group=group+1)begin : groups
    localparam integer PORT=group%4;
    wire accept_enable=load_valid[PORT] && load_ready[PORT] && group_index[PORT]==group;
    wire [8:0] accept_address=(9'(load_bank)<<3)+(9'(load_bank)<<2)+{5'b0,slot_index[PORT]};
    // Optional write pipeline: every group owns its copy of the loader word,
    // so the block RAM columns need not sit beside the stream source. The
    // step is published only after these stages have drained.
    wire write_enable;
    wire [8:0] write_address;
    wire [503:0] write_data;
    if(WRITE_STAGES==3)begin : write_staged
      (* dont_touch="true" *) reg [2:0] enable_q;
      (* dont_touch="true" *) reg [8:0] address_q [0:2];
      (* dont_touch="true" *) reg [503:0] data_last;
      always @(posedge clk)begin
        enable_q<={enable_q[1:0],accept_enable && !reset};
        address_q[0]<=accept_address;address_q[1]<=address_q[0];address_q[2]<=address_q[1];
        data_last<=group<NEAR_GROUPS?side_data[1][PORT]:far_data[(group-NEAR_GROUPS)/FAR_SPAN][PORT];
      end
      assign write_enable=enable_q[2];assign write_address=address_q[2];assign write_data=data_last;
    end else if(WRITE_STAGES!=0)begin : write_pipeline
      (* shreg_extract="no" *) reg [WRITE_STAGES-1:0] enable_q;
      (* shreg_extract="no" *) reg [8:0] address_q [0:WRITE_STAGES-1];
      (* shreg_extract="no", equivalent_register_removal="no" *) reg [503:0] data_q [0:WRITE_STAGES-1];
      integer stage;
      always @(posedge clk)begin
        enable_q[0]<=accept_enable && !reset;address_q[0]<=accept_address;data_q[0]<=load_data[PORT];
        for(stage=1;stage<WRITE_STAGES;stage=stage+1)begin
          enable_q[stage]<=enable_q[stage-1];address_q[stage]<=address_q[stage-1];data_q[stage]<=data_q[stage-1];
        end
      end
      assign write_enable=enable_q[WRITE_STAGES-1];assign write_address=address_q[WRITE_STAGES-1];
      assign write_data=data_q[WRITE_STAGES-1];
    end else begin : write_direct
      assign write_enable=accept_enable;assign write_address=accept_address;assign write_data=load_data[PORT];
    end
    // Optional per-group copy of the read command (one more cycle of latency).
    wire read_enable;
    wire [8:0] read_index;
    if(READ_STAGES!=0)begin : read_pipeline
      (* dont_touch="true" *) reg enable_q;
      (* dont_touch="true" *) reg [8:0] address_q;
      always @(posedge clk)begin enable_q<=read_valid && read_ready;address_q<=read_address;end
      assign read_enable=enable_q;assign read_index=address_q;
    end else begin : read_direct
      assign read_enable=read_valid && read_ready;assign read_index=read_address;
    end
    for(word=0;word<7;word=word+1)begin : words
      (* ram_style="block" *) reg [71:0] memory [0:383];
      reg [71:0] read_data;
      always @(posedge clk)begin
        if(write_enable)memory[write_address]<=write_data[word*72+:72];
        if(read_enable)read_data<=memory[read_index];
      end
      assign group_words[group][word*72+:72]=read_data;
    end
  end
  for(lane=0;lane<384;lane=lane+1)begin : coefficients
    assign key_real[lane/128][lane%128]=group_words[lane/9][(lane%9)*56+:28];
    assign key_imag[lane/128][lane%128]=group_words[lane/9][(lane%9)*56+28+:28];
  end
  for(lane=0;lane<4;lane=lane+1)begin : loader_ready
    assign load_last[lane]=active_step==10'(STEPS-1) && slot_index[lane]==11 && group_index[lane]+6'd4>=43;
    assign load_ready[lane]=loading && !port_done[lane] && !load_fault && !reset;
  end endgenerate
  integer port;
  always @(posedge clk)begin
    if(reset)begin
      read_bank_index<=0;load_bank_index<=0;valid_steps<=0;loading<=0;active_step<=0;expected_load_step<=0;port_done<=0;
      loaded_valid<=0;loaded_step<=0;response_valid<=0;response_early<=0;fault<=0;settle<=0;
      for(port=0;port<4;port=port+1)begin group_index[port]<=6'(port);slot_index[port]<=0;end
    end else begin
      loaded_valid<=0;response_early<=read_valid && read_ready;
      response_valid<=READ_STAGES!=0?response_early:(read_valid && read_ready);
      if(read_valid && !read_ready)fault<=1;
      if(read_retire)begin
        if(!read_valid || !read_ready || read_row!=5 || !read_half)fault<=1;
        else begin valid_steps[read_bank]<=0;read_bank_index<=read_bank_index+1'b1;end
      end
      if(load_start_valid && load_start_ready)begin
        loading<=1;active_step<=load_step;port_done<=0;
        for(port=0;port<4;port=port+1)begin group_index[port]<=6'(port);slot_index[port]<=0;end
      end
      for(port=0;port<4;port=port+1)if(load_valid[port] && load_ready[port])begin
        if(group_index[port]+6'd4>=43)begin
          group_index[port]<=6'(port);
          if(slot_index[port]==11)port_done[port]<=1;
          else slot_index[port]<=slot_index[port]+1'b1;
        end else group_index[port]<=group_index[port]+6'd4;
      end
      if(loading && (&port_done) && settle!=4'(WRITE_STAGES))settle<=settle+1'b1;
      if(loading && (&port_done) && settle==4'(WRITE_STAGES))begin
        settle<=0;loading<=0;valid_steps[load_bank]<=1;step_tag[load_bank]<=active_step;
        loaded_valid<=1;loaded_step<=active_step;load_bank_index<=load_bank_index+1'b1;
        expected_load_step<=active_step==10'(STEPS-1)?10'd0:active_step+1'b1;
      end
    end
  end
  initial if(PREFILL_STEPS<1 || PREFILL_STEPS>32)$fatal(1,"invalid key prefill window");
  initial if(NEAR_GROUPS<0 || NEAR_GROUPS>43 || (NEAR_GROUPS!=0 && WRITE_STAGES!=3))$fatal(1,"invalid near key groups");
  initial if(WRITE_STAGES<0 || WRITE_STAGES>8)$fatal(1,"invalid key write pipeline");
  initial if(READ_STAGES<0 || READ_STAGES>1)$fatal(1,"invalid key read pipeline");
  initial if(STEPS<1 || STEPS>586)$fatal(1,"invalid key cache step count");
endmodule

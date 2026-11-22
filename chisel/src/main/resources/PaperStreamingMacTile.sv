// Fixed-schedule six-row external-product MAC and PISO boundary.
// Two local accumulator banks drain directly; there is no full-frame copy.
// Each transaction accepts twelve consecutive beats and emits twelve beats:
// three components, each in four chunks of LANES/2 points in stored order.
module PaperStreamingMacTile #(
  parameter integer LANES=128, ACC_WIDTH=48, FUSED_ACCUM_ROUND=0, PHYSICAL=0,
  // NEAR_LANES>0 moves component 0, lanes 0..NEAR_LANES-1 into a self-contained
  // group (own operands, control copy, drain and rounding) that can be placed
  // away from the tile. Its rows are merged downstream after NEAR_OUTPUT_DELAY.
  NEAR_LANES=0, NEAR_OUTPUT_DELAY=0, NEAR_PORT=NEAR_LANES>0?NEAR_LANES:1
)(
  input wire clk, reset, input_valid,
  input wire [3:0] input_context,
  input wire signed [31:0] spectrum_real [0:LANES-1], spectrum_imag [0:LANES-1],
  input wire signed [27:0] key_real [0:2][0:LANES-1], key_imag [0:2][0:LANES-1],
  output wire output_valid,
  output reg [3:0] output_context,
  output reg [1:0] output_component, output_chunk,
  output wire signed [31:0] output_real [0:LANES/2-1], output_imag [0:LANES/2-1],
  // Operands for the near group, aligned with input_valid/spectrum_*.
  input wire near_valid,
  input wire signed [31:0] near_spectrum_real [0:NEAR_PORT-1], near_spectrum_imag [0:NEAR_PORT-1],
  output wire near_select,
  output wire signed [31:0] near_output_real [0:NEAR_PORT-1], near_output_imag [0:NEAR_PORT-1],
  output wire fault
);
  reg fault_q;
  wire near_fault;
  assign fault=fault_q || near_fault;
  // Any lane outside the near group: all lanes share one schedule.
  localparam integer TRIGGER=LANES*3-1;
  wire [4:0] local_reset;
  PaperStreamingResetFanout #(.ENABLE(PHYSICAL),.COPIES(5)) reset_fanout(.clk(clk),.reset(reset),.local_reset(local_reset));
  // PHYSICAL=1 adds one aligned register bank per component, so each lane's
  // spectrum and key operands are launched beside it (one extra cycle).
  wire staged_valid;
  wire [3:0] staged_context;
  wire [2:0] lane_valid;
  wire signed [31:0] staged_real [0:2][0:LANES-1], staged_imag [0:2][0:LANES-1];
  wire signed [27:0] staged_key_real [0:2][0:LANES-1], staged_key_imag [0:2][0:LANES-1];
  genvar bank_component,bank_lane;
  generate if(PHYSICAL!=0)begin : operand_bank
    reg bank_valid;
    reg [3:0] bank_context_q;
    always @(posedge clk)begin
      if(local_reset[4])bank_valid<=0;else bank_valid<=input_valid;
      bank_context_q<=input_context;
    end
    assign staged_valid=bank_valid;assign staged_context=bank_context_q;
    for(bank_component=0;bank_component<3;bank_component=bank_component+1)begin : components
      (* keep="true" *) reg valid_q;
      always @(posedge clk)if(local_reset[bank_component])valid_q<=0;else valid_q<=input_valid;
      assign lane_valid[bank_component]=valid_q;
      for(bank_lane=0;bank_lane<LANES;bank_lane=bank_lane+1)begin : lanes
       if(bank_component==0 && bank_lane<NEAR_LANES)begin : moved
        assign staged_real[bank_component][bank_lane]=0;assign staged_imag[bank_component][bank_lane]=0;
        assign staged_key_real[bank_component][bank_lane]=0;assign staged_key_imag[bank_component][bank_lane]=0;
       end else begin : kept
        (* keep="true" *) reg signed [31:0] real_q,imag_q;
        reg signed [27:0] key_real_q,key_imag_q;
        always @(posedge clk)begin
          real_q<=spectrum_real[bank_lane];imag_q<=spectrum_imag[bank_lane];
          key_real_q<=key_real[bank_component][bank_lane];key_imag_q<=key_imag[bank_component][bank_lane];
        end
        assign staged_real[bank_component][bank_lane]=real_q;assign staged_imag[bank_component][bank_lane]=imag_q;
        assign staged_key_real[bank_component][bank_lane]=key_real_q;assign staged_key_imag[bank_component][bank_lane]=key_imag_q;
       end
      end
    end
  end else begin : direct_input
    assign staged_valid=input_valid;assign staged_context=input_context;assign lane_valid={3{input_valid}};
    for(bank_component=0;bank_component<3;bank_component=bank_component+1)begin : components
      for(bank_lane=0;bank_lane<LANES;bank_lane=bank_lane+1)begin : lanes
        assign staged_real[bank_component][bank_lane]=spectrum_real[bank_lane];assign staged_imag[bank_component][bank_lane]=spectrum_imag[bank_lane];
        assign staged_key_real[bank_component][bank_lane]=key_real[bank_component][bank_lane];assign staged_key_imag[bank_component][bank_lane]=key_imag[bank_component][bank_lane];
      end
    end
  end endgenerate
  reg [3:0] input_phase;
  reg input_bank;
  (* ram_style="registers" *) reg [3:0] bank_context [0:1];
  reg draining,drain_bank;
  reg [3:0] drain_context;
  reg [3:0] drain_step;
  wire [LANES*3-1:0] result_valid,result_bank,result_half,lane_overflow;
  // Copies of {drain_bank, drain_step[1:0]} with the same next-state function,
  // one per component and block of READ_BLOCK lane pairs, so that the
  // accumulator read address is launched beside the lanes it addresses.
  localparam integer READ_BLOCK=16, READ_COPIES=(LANES/2+READ_BLOCK-1)/READ_BLOCK;
  wire drain_trigger=result_valid[TRIGGER] && result_half[TRIGGER];
  wire [1:0] drain_step_next=drain_trigger?2'd0:(draining && drain_step!=11)?drain_step[1:0]+1'b1:drain_step[1:0];
  wire drain_bank_next=drain_trigger?result_bank[TRIGGER]:drain_bank;
  (* dont_touch="true" *) reg [2:0] read_copy [0:2][0:READ_COPIES-1];
  integer rc,rb;
  always @(posedge clk)for(rc=0;rc<3;rc=rc+1)for(rb=0;rb<READ_COPIES;rb=rb+1)
    if(local_reset[4])read_copy[rc][rb]<=0;else read_copy[rc][rb]<={drain_bank_next,drain_step_next};
  wire [1:0] drain_component=drain_step[3:2];
  wire drain_half=drain_step[1];
  wire signed [ACC_WIDTH-1:0] bank_real [0:2][0:LANES-1], bank_imag [0:2][0:LANES-1];
  wire [LANES/2-1:0] round_valid_real,round_valid_imag,round_overflow_real,round_overflow_imag;
  assign output_valid=round_valid_real[0];
  wire round_valid;
  wire [3:0] round_step,round_context;
  generate if(PHYSICAL!=0)begin : registered_drain
    reg valid_q;
    reg [3:0] step_q,context_q;
    always @(posedge clk)begin
      if(local_reset[3])valid_q<=0;else valid_q<=draining;
      step_q<=drain_step;context_q<=drain_context;
    end
    assign round_valid=valid_q;assign round_step=step_q;assign round_context=context_q;
  end else begin : direct_drain
    assign round_valid=draining;assign round_step=drain_step;assign round_context=drain_context;
  end endgenerate
  genvar component,lane;
  generate for(component=0;component<3;component=component+1) begin : components
    for(lane=0;lane<LANES;lane=lane+1) begin : lanes
      localparam integer INDEX=component*LANES+lane;
     if(component==0 && lane<NEAR_LANES)begin : moved
      assign result_valid[INDEX]=0;assign result_bank[INDEX]=0;assign result_half[INDEX]=0;assign lane_overflow[INDEX]=0;
      assign bank_real[component][lane]=0;assign bank_imag[component][lane]=0;
     end else begin : kept
      PaperStreamingMacLane #(.USE_SPLIT(1),.ACC_WIDTH(ACC_WIDTH),.FUSED_ACCUM_ROUND(FUSED_ACCUM_ROUND),.PRODUCT_PIPELINE(PHYSICAL)) mac(
        .clk(clk),.reset(local_reset[component]),.input_valid(lane_valid[component]),
        .input_row(input_phase[3:1]),.input_bank(input_bank),.input_half(input_phase[0]),
        .ar(staged_real[component][lane]),.ai(staged_imag[component][lane]),
        .br(staged_key_real[component][lane]),.bi(staged_key_imag[component][lane]),
        .result_valid(result_valid[INDEX]),.result_bank(result_bank[INDEX]),
        .result_half(result_half[INDEX]),.overflow(lane_overflow[INDEX]),
        .read_bank(read_copy[component][(lane%(LANES/2))/READ_BLOCK][2]),.read_half(read_copy[component][(lane%(LANES/2))/READ_BLOCK][1]),
        .read_real(bank_real[component][lane]),.read_imag(bank_imag[component][lane]));
     end
    end
  end
  if(NEAR_LANES!=0)begin : near_group
    PaperStreamingMacNear #(.LANES(NEAR_LANES),.ACC_WIDTH(ACC_WIDTH),.FUSED_ACCUM_ROUND(FUSED_ACCUM_ROUND),.OUTPUT_DELAY(NEAR_OUTPUT_DELAY)) near(
      .clk(clk),.reset(reset),.input_valid(near_valid),.spectrum_real(near_spectrum_real),.spectrum_imag(near_spectrum_imag),
      .key_real(key_real[0][0:NEAR_LANES-1]),.key_imag(key_imag[0][0:NEAR_LANES-1]),
      .output_select(near_select),.output_real(near_output_real),.output_imag(near_output_imag),.fault(near_fault));
  end else begin : no_near_group
    assign near_select=0;assign near_fault=0;
    assign near_output_real[0]=0;assign near_output_imag[0]=0;
  end
  for(lane=0;lane<LANES/2;lane=lane+1) begin : drain_lanes
    wire signed [ACC_WIDTH-1:0] selected_real,selected_imag;
    if(PHYSICAL!=0)begin : registered_half
      // Select the frame half beside each component, then the component.
      reg signed [ACC_WIDTH-1:0] half_real [0:2],half_imag [0:2];
      integer c;
      always @(posedge clk)for(c=0;c<3;c=c+1)begin
        half_real[c]<=read_copy[c][lane/READ_BLOCK][0]?bank_real[c][lane+LANES/2]:bank_real[c][lane];
        half_imag[c]<=read_copy[c][lane/READ_BLOCK][0]?bank_imag[c][lane+LANES/2]:bank_imag[c][lane];
      end
      assign selected_real=half_real[round_step[3:2]];assign selected_imag=half_imag[round_step[3:2]];
    end else begin : direct_half
      assign selected_real=drain_step[0]?bank_real[drain_component][lane+LANES/2]:bank_real[drain_component][lane];
      assign selected_imag=drain_step[0]?bank_imag[drain_component][lane+LANES/2]:bank_imag[drain_component][lane];
    end
    PaperStreamingRequantize #(.INPUT_WIDTH(ACC_WIDTH),.OUTPUT_WIDTH(32),.SHIFT(ACC_WIDTH-38)) real_round(
      .clk(clk),.reset(local_reset[3]),.input_valid(round_valid),.value(selected_real),
      .output_valid(round_valid_real[lane]),.overflow(round_overflow_real[lane]),.result(output_real[lane]));
    PaperStreamingRequantize #(.INPUT_WIDTH(ACC_WIDTH),.OUTPUT_WIDTH(32),.SHIFT(ACC_WIDTH-38)) imag_round(
      .clk(clk),.reset(local_reset[3]),.input_valid(round_valid),.value(selected_imag),
      .output_valid(round_valid_imag[lane]),.overflow(round_overflow_imag[lane]),.result(output_imag[lane]));
  end endgenerate
  always @(posedge clk) begin
    if(local_reset[4]) begin
      input_phase<=0;input_bank<=0;draining<=0;drain_bank<=0;drain_step<=0;fault_q<=0;
    end else begin
      if(staged_valid) begin
        if(input_phase==0)bank_context[input_bank]<=staged_context;
        else if(staged_context!=bank_context[input_bank])fault_q<=1;
        if(input_phase==11)begin input_phase<=0;input_bank<=!input_bank;end
        else input_phase<=input_phase+1'b1;
      end else if(input_phase!=0)fault_q<=1;
      if(draining) begin
        if(drain_step==11)draining<=0;
        else drain_step<=drain_step+1'b1;
      end
      if(result_valid[TRIGGER] && result_half[TRIGGER]) begin
        if(draining && drain_step!=11)fault_q<=1;
        draining<=1;drain_step<=0;drain_bank<=result_bank[TRIGGER];
        drain_context<=bank_context[result_bank[TRIGGER]];
      end
      output_context<=round_context;
      output_component<=round_step[3:2];output_chunk<=round_step[1:0];
      if((|lane_overflow) || (output_valid && ((|round_overflow_real) || (|round_overflow_imag))))fault_q<=1;
    end
  end
  initial if(LANES<2 || LANES%2!=0)$fatal(1,"MAC tile requires an even lane count");
  initial if(NEAR_LANES<0 || NEAR_LANES>LANES/2 || (NEAR_LANES!=0 && PHYSICAL==0))$fatal(1,"invalid near MAC group");
endmodule

// Component 0, lanes 0..LANES-1 of a PHYSICAL MAC tile with a private copy of
// the tile schedule. Timing is identical to the tile: operand bank, lanes,
// registered drain, rounding. These lanes are drained only on the first-half
// beats of component 0 (drain steps 0 and 2); output_select marks those rows.
module PaperStreamingMacNear #(
  parameter integer LANES=63, ACC_WIDTH=48, FUSED_ACCUM_ROUND=0, OUTPUT_DELAY=0
)(
  input wire clk, reset, input_valid,
  input wire signed [31:0] spectrum_real [0:LANES-1], spectrum_imag [0:LANES-1],
  input wire signed [27:0] key_real [0:LANES-1], key_imag [0:LANES-1],
  output wire output_select,
  output wire signed [31:0] output_real [0:LANES-1], output_imag [0:LANES-1],
  output reg fault
);
  wire local_reset;
  PaperStreamingResetFanout #(.ENABLE(1),.COPIES(1)) reset_fanout(.clk(clk),.reset(reset),.local_reset(local_reset));
  // A deliberate copy of the tile schedule: it must not be merged back.
  (* dont_touch="true" *) reg staged_valid;
  (* dont_touch="true" *) reg [3:0] input_phase;
  (* dont_touch="true" *) reg input_bank;
  (* dont_touch="true" *) reg draining,drain_bank;
  (* dont_touch="true" *) reg [3:0] drain_step;
  (* dont_touch="true" *) reg round_valid;
  (* dont_touch="true" *) reg [3:0] round_step;
  wire [LANES-1:0] result_valid,result_bank,result_half,lane_overflow;
  wire [LANES-1:0] rounded_valid,overflow_real,overflow_imag;
  wire signed [31:0] rounded_real [0:LANES-1], rounded_imag [0:LANES-1];
  wire round_select=round_valid && round_step[3:2]==0 && !round_step[0];
  localparam integer READ_BLOCK=16, READ_COPIES=(LANES+READ_BLOCK-1)/READ_BLOCK;
  wire drain_trigger=result_valid[0] && result_half[0];
  wire drain_half_next=drain_trigger?1'b0:(draining && drain_step!=11)?drain_step[1]^drain_step[0]:drain_step[1];
  wire drain_bank_next=drain_trigger?result_bank[0]:drain_bank;
  (* dont_touch="true" *) reg [1:0] read_copy [0:READ_COPIES-1];
  integer rb;
  always @(posedge clk)for(rb=0;rb<READ_COPIES;rb=rb+1)
    if(local_reset)read_copy[rb]<=0;else read_copy[rb]<={drain_bank_next,drain_half_next};
  genvar lane;
  generate for(lane=0;lane<LANES;lane=lane+1)begin : lanes
    (* keep="true" *) reg signed [31:0] real_q,imag_q;
    reg signed [27:0] key_real_q,key_imag_q;
    wire signed [ACC_WIDTH-1:0] read_real,read_imag;
    reg signed [ACC_WIDTH-1:0] half_real,half_imag;
    always @(posedge clk)begin
      real_q<=spectrum_real[lane];imag_q<=spectrum_imag[lane];
      key_real_q<=key_real[lane];key_imag_q<=key_imag[lane];
      half_real<=read_real;half_imag<=read_imag;
    end
    PaperStreamingMacLane #(.USE_SPLIT(1),.ACC_WIDTH(ACC_WIDTH),.FUSED_ACCUM_ROUND(FUSED_ACCUM_ROUND),.PRODUCT_PIPELINE(1)) mac(
      .clk(clk),.reset(local_reset),.input_valid(staged_valid),
      .input_row(input_phase[3:1]),.input_bank(input_bank),.input_half(input_phase[0]),
      .ar(real_q),.ai(imag_q),.br(key_real_q),.bi(key_imag_q),
      .result_valid(result_valid[lane]),.result_bank(result_bank[lane]),
      .result_half(result_half[lane]),.overflow(lane_overflow[lane]),
      .read_bank(read_copy[lane/READ_BLOCK][1]),.read_half(read_copy[lane/READ_BLOCK][0]),.read_real(read_real),.read_imag(read_imag));
    PaperStreamingRequantize #(.INPUT_WIDTH(ACC_WIDTH),.OUTPUT_WIDTH(32),.SHIFT(ACC_WIDTH-38)) real_round(
      .clk(clk),.reset(local_reset),.input_valid(round_select),.value(half_real),
      .output_valid(rounded_valid[lane]),.overflow(overflow_real[lane]),.result(rounded_real[lane]));
    PaperStreamingRequantize #(.INPUT_WIDTH(ACC_WIDTH),.OUTPUT_WIDTH(32),.SHIFT(ACC_WIDTH-38)) imag_round(
      .clk(clk),.reset(local_reset),.input_valid(round_select),.value(half_imag),
      .output_valid(),.overflow(overflow_imag[lane]),.result(rounded_imag[lane]));
    if(OUTPUT_DELAY!=0)begin : delayed
      (* shreg_extract="no" *) reg signed [31:0] real_d [0:OUTPUT_DELAY-1],imag_d [0:OUTPUT_DELAY-1];
      integer index;
      always @(posedge clk)begin
        real_d[0]<=rounded_real[lane];imag_d[0]<=rounded_imag[lane];
        for(index=1;index<OUTPUT_DELAY;index=index+1)begin real_d[index]<=real_d[index-1];imag_d[index]<=imag_d[index-1];end
      end
      assign output_real[lane]=real_d[OUTPUT_DELAY-1];assign output_imag[lane]=imag_d[OUTPUT_DELAY-1];
    end else begin : direct
      assign output_real[lane]=rounded_real[lane];assign output_imag[lane]=rounded_imag[lane];
    end
  end
  if(OUTPUT_DELAY!=0)begin : delayed_select
    (* shreg_extract="no" *) reg [OUTPUT_DELAY-1:0] select_d;
    always @(posedge clk)if(local_reset)select_d<=0;else select_d<=OUTPUT_DELAY'({select_d,rounded_valid[0]});
    assign output_select=select_d[OUTPUT_DELAY-1];
  end else begin : direct_select
    assign output_select=rounded_valid[0];
  end endgenerate
  always @(posedge clk)begin
    round_step<=drain_step;
    if(local_reset)begin
      staged_valid<=0;input_phase<=0;input_bank<=0;draining<=0;drain_bank<=0;drain_step<=0;round_valid<=0;fault<=0;
    end else begin
      staged_valid<=input_valid;round_valid<=draining;
      if(staged_valid)begin
        if(input_phase==11)begin input_phase<=0;input_bank<=!input_bank;end
        else input_phase<=input_phase+1'b1;
      end
      if(draining)begin
        if(drain_step==11)draining<=0;
        else drain_step<=drain_step+1'b1;
      end
      if(result_valid[0] && result_half[0])begin
        draining<=1;drain_step<=0;drain_bank<=result_bank[0];
      end
      if((|lane_overflow) || (rounded_valid[0] && ((|overflow_real) || (|overflow_imag))))fault<=1;
    end
  end
  initial if(LANES<1 || OUTPUT_DELAY<0)$fatal(1,"invalid near MAC group");
endmodule

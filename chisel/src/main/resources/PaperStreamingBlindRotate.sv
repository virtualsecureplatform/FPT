// Fixed-schedule twelve-context feedback core. Body initialization and key
// DMA are boundary interfaces; exponent loads preserve the TLWE context order.
module PaperStreamingBlindRotate #(
  parameter integer STEPS=586, DIGIT_REVERSED=0, DSP_FORWARD_PAIRS=0, KEY_READ_LATENCY=0, OVERLAP_INPUT=0, FUSED_ACCUM_ROUND=0, DSP_INVERSE_LAST=0, BRAM_STATE_LANES=0, PHYSICAL=0
)(
  input wire clk,reset,start,
  output wire start_ready,busy,
  output reg done,
  input wire exponent_load_valid,
  output wire exponent_load_ready,
  input wire [3:0] exponent_load_context,
  input wire [9:0] exponent_load_step,exponent_load_value,
  input wire body_load_valid,
  input wire [3:0] body_load_context,
  input wire [1:0] body_load_chunk,
  input wire [16:0] body_load_low [0:63],body_load_high [0:63],
  input wire next_body_full,
  input wire [16:0] next_body_low [0:63],next_body_high [0:63],
  output wire next_body_read_valid,
  output wire [3:0] next_body_read_context,
  output wire [1:0] next_body_read_chunk,
  output wire key_request,
  output wire [3:0] key_context,
  output wire [9:0] key_step,
  output wire [2:0] key_row,
  output wire key_half,
  input wire key_valid,
  input wire signed [27:0] key_real [0:2][0:127],key_imag [0:2][0:127],
  input wire debug_read_valid,
  input wire [3:0] debug_context,
  input wire [1:0] debug_component,
  output wire debug_ready,
  output wire [16:0] debug_code [0:511],
  output wire final_valid,
  output wire [3:0] final_context,
  output wire [1:0] final_component,final_chunk,
  output wire [16:0] final_low [0:63],final_high [0:63],
  output wire fault
);
  localparam integer WORDS=12*STEPS,ADDRESS_BITS=$clog2(WORDS);
  localparam [9:0] LAST_STEP=10'(STEPS-1);
  localparam [2:0] IDLE=0,ARM=1,RUN=2,DRAIN=3,FAILED=4;
  reg [2:0] state;
  reg [3:0] context_index,phase,expected_load_context;
  reg [9:0] step_index,expected_load_step,legacy_exponent;
  wire [9:0] exponent;
  wire overlap_ready,overlap_fault;
  reg replacing_batch;
  reg [11:0] body_ready;
  reg exponents_ready,protocol_fault;
  (* ram_style="block" *) reg [9:0] exponent_memory [0:WORDS-1];
  wire exponent_load_legal=exponent_load_context<12 && exponent_load_step<=LAST_STEP;
  wire [ADDRESS_BITS-1:0] load_address=(ADDRESS_BITS'(exponent_load_step)<<3)+(ADDRESS_BITS'(exponent_load_step)<<2)+ADDRESS_BITS'(exponent_load_context);
  wire [ADDRESS_BITS-1:0] current_address=(ADDRESS_BITS'(step_index)<<3)+(ADDRESS_BITS'(step_index)<<2)+ADDRESS_BITS'(context_index);
  wire last_issue=step_index==LAST_STEP && context_index==11;
  wire exponent_prefetch=state==ARM || (state==RUN && phase==11 && !last_issue);
  wire [ADDRESS_BITS-1:0] next_address=state==ARM?'0:current_address+1'b1;
  wire issue=state==RUN;
  wire store_ready,store_fault,front_valid,front_fault,ep_valid,ep_fault,store_response_valid;
  wire commit_valid,commit_load;
  wire [3:0] commit_context;
  wire [1:0] commit_component,commit_chunk;
  wire [9:0] commit_step;
  reg issue_delayed;
  reg [9:0] issue_exponent,ep_step_delayed;
  reg [3:0] issue_context,issue_phase;
  wire frontend_issue=BRAM_STATE_LANES!=0?(issue_delayed && store_response_valid):issue;
  wire [9:0] frontend_exponent=BRAM_STATE_LANES!=0?issue_exponent:exponent;
  wire [3:0] frontend_context=BRAM_STATE_LANES!=0?issue_context:context_index;
  wire [3:0] frontend_phase=BRAM_STATE_LANES!=0?issue_phase:phase;
  wire [16:0] coefficients [0:511];
  wire signed [7:0] digit_low [0:127],digit_high [0:127];
  wire [3:0] front_context,ep_context;
  wire [2:0] front_row;
  wire front_half;
  wire [9:0] ep_step;
  wire [1:0] ep_component,ep_chunk;
  wire [31:0] ep_low [0:63],ep_high [0:63];
  wire [16:0] write_low [0:63],write_high [0:63];
  wire loading=body_load_valid && state==IDLE;
  wire [3:0] local_reset;
  PaperStreamingResetFanout #(.ENABLE(PHYSICAL),.COPIES(4)) reset_fanout(.clk(clk),.reset(reset),.local_reset(local_reset));
  PaperStreamingFaultRegister #(.ENABLE(PHYSICAL)) fault_register(.clk(clk),.reset(local_reset[0]),.fault_input(protocol_fault || store_fault || front_fault || ep_fault || overlap_fault),.fault(fault));
  assign start_ready=state==IDLE && (OVERLAP_INPUT!=0?overlap_ready:exponents_ready) && (&body_ready) && !fault && !local_reset[0];
  assign busy=state!=IDLE;
  assign debug_ready=state==IDLE && (BRAM_STATE_LANES!=0?store_response_valid:store_ready);
  assign final_valid=commit_valid && !commit_load && commit_step==LAST_STEP;
  assign final_context=commit_context;
  assign final_component=commit_component;assign final_chunk=commit_chunk;
  wire last_commit=final_valid && commit_context==11 && commit_component==2 && commit_chunk==3;
  wire final_body=final_valid && commit_component==2;
  wire first_final_body=final_body && commit_context==0 && commit_chunk==0;
  wire replace_body=OVERLAP_INPUT!=0 && final_body && (first_final_body?next_body_full:replacing_batch);
  assign next_body_read_valid=replace_body;
  assign next_body_read_context=commit_context;assign next_body_read_chunk=commit_chunk;
  generate if(OVERLAP_INPUT!=0)begin : overlapped_exponents
    PaperStreamingExponentBanks #(.STEPS(STEPS),.ADDRESS_BITS(ADDRESS_BITS)) banks(
      .clk(clk),.reset(local_reset[3]),.load_valid(exponent_load_valid),.load_ready(exponent_load_ready),
      .load_context(exponent_load_context),.load_step(exponent_load_step),.load_value(exponent_load_value),
      .activate(start && start_ready),.retire(last_commit),.activate_ready(overlap_ready),
      .read_valid(exponent_prefetch),.read_address(next_address),.read_value(exponent),.fault(overlap_fault));
  end else begin : serial_exponents
    assign exponent=legacy_exponent;assign overlap_ready=0;assign overlap_fault=0;
    assign exponent_load_ready=state==IDLE && !local_reset[0] && !fault;
  end endgenerate
  genvar lane;
  generate for(lane=0;lane<64;lane=lane+1)begin : write_data
    assign write_low[lane]=loading?body_load_low[lane]:ep_low[lane][31:15];
    assign write_high[lane]=loading?body_load_high[lane]:ep_high[lane][31:15];
  end
  for(lane=0;lane<512;lane=lane+1)begin : debug_data
    assign debug_code[lane]=coefficients[lane];
  end endgenerate
  generate if(BRAM_STATE_LANES!=0)begin : mixed_state
    assign commit_step=ep_step_delayed;
    PaperStreamingMixedCoefficientStore17 #(.BRAM_LANES(BRAM_STATE_LANES)) store(
      .clk(clk),.reset(local_reset[1]),.read_valid(issue || (state==IDLE && debug_read_valid)),
      .read_initial(issue && step_index==0),.read_context(issue?context_index:debug_context),
      .read_component(issue?phase[3:2]:debug_component),.read_ready(store_ready),.read_response_valid(store_response_valid),.read_code(coefficients),
      .write_valid(loading || ep_valid),.write_load(loading),.write_initial(!loading && ep_step==0),
      .write_context(loading?body_load_context:ep_context),.write_component(loading?2'd2:ep_component),
      .write_chunk(loading?body_load_chunk:ep_chunk),.write_low(write_low),.write_high(write_high),
      .write_replace(replace_body),.replacement_low(next_body_low),.replacement_high(next_body_high),
      .commit_valid(commit_valid),.commit_load(commit_load),.commit_context(commit_context),
      .commit_component(commit_component),.commit_chunk(commit_chunk),.updated_low(final_low),.updated_high(final_high),.fault(store_fault));
  end else begin : distributed_state
    assign commit_valid=loading || ep_valid;assign commit_load=loading;assign commit_step=ep_step;
    assign commit_context=loading?body_load_context:ep_context;
    assign commit_component=loading?2'd2:ep_component;assign commit_chunk=loading?body_load_chunk:ep_chunk;
    assign store_response_valid=0;
  PaperStreamingCoefficientStore17 #(.REPLACEMENT(OVERLAP_INPUT)) store(
    .clk(clk),.reset(local_reset[1]),.read_valid(issue || (state==IDLE && debug_read_valid)),
    .read_initial(issue && step_index==0),.read_context(issue?context_index:debug_context),
    .read_component(issue?phase[3:2]:debug_component),.read_ready(store_ready),.read_code(coefficients),
    .write_valid(loading || ep_valid),.write_load(loading),.write_initial(!loading && ep_step==0),
    .write_context(loading?body_load_context:ep_context),.write_component(loading?2'd2:ep_component),
    .write_chunk(loading?body_load_chunk:ep_chunk),.write_low(write_low),.write_high(write_high),
    .write_replace(replace_body),.replacement_low(next_body_low),.replacement_high(next_body_high),
    .updated_low(final_low),.updated_high(final_high),.fault(store_fault));
  end endgenerate
  PaperStreamingCoefficientFrontend17 #(.DIGIT_REVERSED(DIGIT_REVERSED)) frontend(
    .clk(clk),.reset(local_reset[2]),.input_valid(frontend_issue),.input_exponent(frontend_exponent),.input_context(frontend_context),
    .input_row(frontend_phase[3:1]),.input_half(frontend_phase[0]),.input_code(coefficients),.output_valid(front_valid),
    .output_context(front_context),.output_row(front_row),.output_half(front_half),
    .digit_low(digit_low),.digit_high(digit_high),.fault(front_fault));
  PaperStreamingExternalProduct #(.ACC_WIDTH(40),.LEG_GUARD(4),.FUSED_ROUND(1),.STEPS(STEPS),.DIGIT_REVERSED(DIGIT_REVERSED),.DSP_FORWARD_PAIRS(DSP_FORWARD_PAIRS),.KEY_READ_LATENCY(KEY_READ_LATENCY),.FUSED_ACCUM_ROUND(FUSED_ACCUM_ROUND),.DSP_INVERSE_LAST(DSP_INVERSE_LAST),.PHYSICAL(PHYSICAL)) arithmetic(
    .clk(clk),.reset(reset),.input_valid(front_valid),.digit_low(digit_low),.digit_high(digit_high),
    .key_request(key_request),.key_row(key_row),.key_half(key_half),.key_context(key_context),.key_step(key_step),
    .key_valid(key_valid),.key_real(key_real),.key_imag(key_imag),.output_valid(ep_valid),
    .output_context(ep_context),.output_step(ep_step),.output_component(ep_component),.output_chunk(ep_chunk),
    .output_low(ep_low),.output_high(ep_high),.fault(ep_fault));
  always @(posedge clk)begin
    if(local_reset[0])issue_delayed<=0;else issue_delayed<=issue;
    issue_exponent<=exponent;issue_context<=context_index;issue_phase<=phase;ep_step_delayed<=ep_step;
    if(OVERLAP_INPUT==0 && exponent_load_valid && exponent_load_legal && state==IDLE)exponent_memory[load_address]<=exponent_load_value;
    if(OVERLAP_INPUT==0 && exponent_prefetch)legacy_exponent<=exponent_memory[next_address];
    if(local_reset[0])begin
      state<=IDLE;phase<=0;context_index<=0;step_index<=0;done<=0;protocol_fault<=0;
      replacing_batch<=0;exponents_ready<=0;expected_load_context<=0;expected_load_step<=0;body_ready<=0;
    end else begin
      done<=0;
      if(OVERLAP_INPUT==0 && exponent_load_valid)begin
        if(state!=IDLE || !exponent_load_legal)protocol_fault<=1;
        else if(exponent_load_context==0 && exponent_load_step==0)begin
          exponents_ready<=0;
          expected_load_context<=STEPS==1?4'd1:4'd0;expected_load_step<=STEPS==1?10'd0:10'd1;
        end else if(exponent_load_context!=expected_load_context || exponent_load_step!=expected_load_step)protocol_fault<=1;
        else if(exponent_load_step==LAST_STEP)begin
          expected_load_step<=0;
          if(exponent_load_context==11)begin exponents_ready<=1;expected_load_context<=0;end
          else expected_load_context<=expected_load_context+1'b1;
        end else expected_load_step<=expected_load_step+1'b1;
      end
      if(body_load_valid)begin
        if(state!=IDLE || body_load_context>=12)protocol_fault<=1;
        else if(body_load_chunk==0)body_ready[body_load_context]<=0;
        else if(body_load_chunk==3)body_ready[body_load_context]<=1;
      end
      if(start)begin
        if(!start_ready)protocol_fault<=1;
        else begin state<=ARM;phase<=0;context_index<=0;step_index<=0;replacing_batch<=0;end
      end
      if(state==ARM)state<=RUN;
      if(state==RUN)begin
        if(phase==11)begin
          phase<=0;
          if(context_index==11)begin
            context_index<=0;
            if(step_index==LAST_STEP)state<=DRAIN;else step_index<=step_index+1'b1;
          end else context_index<=context_index+1'b1;
        end else phase<=phase+1'b1;
      end
      if(first_final_body)replacing_batch<=OVERLAP_INPUT!=0 && next_body_full;
      if(replace_body && !next_body_full)protocol_fault<=1;
      if(last_commit)begin
        if(state!=DRAIN)protocol_fault<=1;
        else begin state<=IDLE;done<=1;body_ready<=replace_body?12'hfff:12'h000;end
      end
      if(fault)state<=FAILED;
    end
  end
  initial if(STEPS<1 || STEPS>586)$fatal(1,"invalid blind-rotation dimension");
endmodule

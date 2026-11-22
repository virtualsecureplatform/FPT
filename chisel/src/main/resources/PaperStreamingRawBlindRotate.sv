// Raw Torus32 TLWE/LUT boundary, overlapped preparation, cached encrypted keys
// and extracted-sample output stream. Send the LUT before each TLWE frame (or
// reuse an already loaded LUT). Preparation runs ahead into the inactive
// exponent table and initial-body buffer while the current batch computes.
module PaperStreamingRawBlindRotate #(
  parameter integer STEPS=586, DSP_FORWARD_PAIRS=1, FUSED_ACCUM_ROUND=0, DSP_INVERSE_LAST=0, BRAM_STATE_LANES=0, REGISTER_LAUNCH=0, REGISTER_PREP_FAULT=0, PHYSICAL=0
)(
  input wire clk,reset,start,start_bank,
  output wire start_ready,busy,done,
  input wire lookup_valid,
  output wire lookup_ready,
  input wire [8:0] lookup_index,
  input wire [31:0] lookup_value,
  input wire tlwe_valid,
  output wire tlwe_ready,
  input wire [3:0] tlwe_context,
  input wire [9:0] tlwe_word,
  input wire [31:0] tlwe_value,
  input wire [3:0] key_stream_valid,key_stream_last,
  output wire [3:0] key_stream_ready,
  input wire [511:0] key_stream_data [0:3],
  output wire key_request,
  output wire [3:0] key_context,
  output wire [9:0] key_step,
  output wire [2:0] key_row,
  output wire key_half,
  output wire [1:0] output_available,output_ready,
  input wire stream_start,stream_bank,
  output wire stream_start_ready,stream_busy,stream_done,
  output wire output_valid,
  input wire output_accept,
  output wire [511:0] output_data,
  output wire [63:0] output_keep,
  output wire output_last,
  output wire fault
);
  wire prep_ready,prep_busy,prep_done,prep_fault,body_valid,body_accept,body_last;
  wire [3:0] body_context;
  wire [8:0] body_point;
  wire [16:0] body_code;
  wire exponent_valid,exponent_ready;
  wire [3:0] exponent_context;
  wire [9:0] exponent_step,exponent_value;
  wire bodies_full,bodies_drained,bodies_ready,bodies_fault;
  wire [16:0] body_low [0:63],body_high [0:63];
  wire core_ready,core_busy,core_fault,next_read;
  wire [3:0] next_context;
  wire [1:0] next_chunk;
  reg initialized,loading_initial,protocol_fault;
  reg [3:0] initial_context;
  reg [1:0] initial_chunk;
  wire [3:0] read_context=loading_initial?initial_context:next_context;
  wire [1:0] read_chunk=loading_initial?initial_chunk:next_chunk;
  wire initial_write=loading_initial && bodies_ready;
  wire [2:0] local_reset;
  PaperStreamingResetFanout #(.ENABLE(PHYSICAL),.COPIES(3)) reset_fanout(.clk(clk),.reset(reset),.local_reset(local_reset));
  PaperStreamingFaultRegister #(.ENABLE(PHYSICAL)) fault_register(.clk(clk),.reset(local_reset[0]),.fault_input(prep_fault || bodies_fault || core_fault || protocol_fault),.fault(fault));
  assign start_ready=core_ready && initialized && !loading_initial && !fault;
  assign busy=core_busy || loading_initial;
  // Keep the global arithmetic fault tree out of preparation ready/write enables.
  // Public fault reporting remains immediate. After a fatal fault, this local
  // observation may permit one final internal preparation start before stopping.
  wire preparation_fault;
  generate if(REGISTER_PREP_FAULT!=0)begin : registered_preparation_fault
    reg stopped;
    always @(posedge clk)begin
      if(local_reset[0])stopped<=0;
      else stopped<=fault;
    end
    assign preparation_fault=stopped;
  end else begin : direct_preparation_fault
    assign preparation_fault=fault;
  end endgenerate
  PaperStreamingInputPrepare17 #(.STEPS(STEPS)) prepare(
    .clk(clk),.reset(local_reset[1]),.start(prep_ready && !preparation_fault),.start_ready(prep_ready),.busy(prep_busy),.done(prep_done),
    .lookup_valid(lookup_valid),.lookup_ready(lookup_ready),.lookup_index(lookup_index),.lookup_value(lookup_value),
    .tlwe_valid(tlwe_valid),.tlwe_ready(tlwe_ready),.tlwe_context(tlwe_context),.tlwe_word(tlwe_word),.tlwe_value(tlwe_value),
    .exponent_valid(exponent_valid),.exponent_ready(exponent_ready),.exponent_context(exponent_context),
    .exponent_step(exponent_step),.exponent_value(exponent_value),
    .body_valid(body_valid),.body_ready(body_accept),.body_context(body_context),.body_point(body_point),
    .body_code(body_code),.body_last(body_last),.fault(prep_fault));
  PaperStreamingInitialBodyBuffer17 bodies(
    .clk(clk),.reset(local_reset[2]),.input_valid(body_valid),.input_ready(body_accept),
    .input_context(body_context),.input_point(body_point),.input_code(body_code),.full(bodies_full),.drained(bodies_drained),
    .read_valid(loading_initial || next_read),.read_ready(bodies_ready),.read_context(read_context),.read_chunk(read_chunk),
    .read_low(body_low),.read_high(body_high),.fault(bodies_fault));
  // start_ready includes core_ready, so the qualified child start below
  // implies the child's readiness. This wrapper checks rejected starts itself.
  PaperStreamingKeyedBlindRotate #(.STEPS(STEPS),.DSP_FORWARD_PAIRS(DSP_FORWARD_PAIRS),.OVERLAP_INPUT(1),.FUSED_ACCUM_ROUND(FUSED_ACCUM_ROUND),.DSP_INVERSE_LAST(DSP_INVERSE_LAST),.BRAM_STATE_LANES(BRAM_STATE_LANES),.REGISTER_LAUNCH(REGISTER_LAUNCH),.CHECK_START_PROTOCOL(0),.PHYSICAL(PHYSICAL)) core(
    .clk(clk),.reset(reset),.start(start && start_ready),.start_bank(start_bank),.start_ready(core_ready),.busy(core_busy),.done(done),
    .exponent_load_valid(exponent_valid),.exponent_load_ready(exponent_ready),.exponent_load_context(exponent_context),
    .exponent_load_step(exponent_step),.exponent_load_value(exponent_value),
    .body_load_valid(initial_write),.body_load_context(initial_context),.body_load_chunk(initial_chunk),
    .body_load_low(body_low),.body_load_high(body_high),.next_body_full(bodies_full),
    .next_body_low(body_low),.next_body_high(body_high),.next_body_read_valid(next_read),
    .next_body_read_context(next_context),.next_body_read_chunk(next_chunk),
    .key_stream_valid(key_stream_valid),.key_stream_last(key_stream_last),.key_stream_ready(key_stream_ready),.key_stream_data(key_stream_data),
    .key_request(key_request),.key_context(key_context),.key_step(key_step),.key_row(key_row),.key_half(key_half),
    .output_available(output_available),.output_ready(output_ready),.stream_start(stream_start),.stream_bank(stream_bank),
    .stream_start_ready(stream_start_ready),.stream_busy(stream_busy),.stream_done(stream_done),
    .output_valid(output_valid),.output_accept(output_accept),.output_data(output_data),.output_keep(output_keep),.output_last(output_last),.fault(core_fault));
  always @(posedge clk)begin
    if(local_reset[0])begin initialized<=0;loading_initial<=0;initial_context<=0;initial_chunk<=0;protocol_fault<=0;end
    else begin
      if(!initialized && !core_busy && bodies_full && !loading_initial)begin
        loading_initial<=1;initial_context<=0;initial_chunk<=0;
      end
      if(initial_write)begin
        if(initial_chunk==3)begin
          initial_chunk<=0;
          if(initial_context==11)begin loading_initial<=0;initialized<=1;end
          else initial_context<=initial_context+1'b1;
        end else initial_chunk<=initial_chunk+1'b1;
      end
      if(next_read && next_context==11 && next_chunk==3)initialized<=1;
      if(start && start_ready)initialized<=0;
      if((start && !start_ready) || (loading_initial && next_read))protocol_fault<=1;
    end
  end
endmodule

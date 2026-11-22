// Streaming feedback/output engine with automatic 32-step TGGSW prefetch.
// Four 512-bit key streams use grouped-key layout version 2; each repeats the
// complete ordinary encrypted key image for each twelve-context batch.
module PaperStreamingKeyedBlindRotate #(
  parameter integer STEPS=586, DSP_FORWARD_PAIRS=1, OVERLAP_INPUT=0, FUSED_ACCUM_ROUND=0, DSP_INVERSE_LAST=0, BRAM_STATE_LANES=0, REGISTER_LAUNCH=0, CHECK_START_PROTOCOL=1, PHYSICAL=0
)(
  input wire clk,reset,start,start_bank,
  output wire start_ready,busy,done,
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
  wire core_ready,core_fault,cache_fault,key_valid;
  wire cache_window,cache_initial,cache_read_ready,load_start_ready,loaded_valid;
  wire [9:0] loaded_step;
  wire [3:0] expected_last,stream_error;
  wire [503:0] load_data [0:3];
  wire signed [27:0] key_real [0:2][0:127],key_imag [0:2][0:127];
  wire unused_response_valid;
  wire [31:0] unused_response_word;
  reg [9:0] next_load_step;
  reg protocol_fault;
  wire core_start,core_start_bank,core_busy,launch_pending;
  wire [1:0] local_reset;
  PaperStreamingResetFanout #(.ENABLE(PHYSICAL),.COPIES(2)) reset_fanout(.clk(clk),.reset(reset),.local_reset(local_reset));
  // The key cache sits in another SLR than the launch control. Its sticky
  // fault and its window credit cross through registers on both sides; the
  // window is only consumed long after a launch, so the delay is harmless.
  wire cache_fault_seen,cache_window_seen,protocol_fault_seen;
  generate if(PHYSICAL!=0)begin : cache_status_crossing
    (* shreg_extract="no" *) reg fault_near,fault_far,window_near;
    always @(posedge clk)begin
      if(local_reset[1])begin fault_near<=0;window_near<=0;end
      else begin fault_near<=cache_fault;window_near<=cache_window && !cache_fault;end
      if(local_reset[0])fault_far<=0;else fault_far<=fault_near;
    end
    // The stream-format fault is raised beside the key ports; the launch
    // control reads a local registered copy.
    (* shreg_extract="no" *) reg protocol_far;
    always @(posedge clk)if(local_reset[0])protocol_far<=0;else protocol_far<=protocol_fault;
    assign protocol_fault_seen=protocol_far;
    assign cache_fault_seen=fault_far;assign cache_window_seen=window_near;
  end else begin : cache_status_direct
    assign protocol_fault_seen=protocol_fault;
    assign cache_fault_seen=cache_fault;assign cache_window_seen=cache_window;
  end endgenerate
  generate if(REGISTER_LAUNCH!=0)begin : accepted_launch_pipeline
    // Capture the accepted request before returning from cache qualification
    // to the compute core. Carry its bank with it; the buffered child performs
    // computation launch and output reservation together on the following edge.
    reg pending,bank;
    always @(posedge clk)begin
      if(local_reset[0])begin pending<=0;bank<=0;end
      else begin
        pending<=start && start_ready;
        if(start && start_ready)bank<=start_bank;
      end
    end
    assign core_start=pending && core_ready && !cache_fault_seen && !protocol_fault_seen && !local_reset[0];
    // Advertise availability for the caller's next bank while idle. Once
    // accepted, keep the captured bank through the deferred launch edge.
    assign core_start_bank=pending?bank:start_bank;
    assign launch_pending=pending;
    assign busy=core_busy || pending;
  end else begin : direct_accepted_launch
    assign core_start=start && start_ready;
    assign core_start_bank=start_bank;
    assign launch_pending=1'b0;
    assign busy=core_busy;
  end endgenerate
  PaperStreamingFaultRegister #(.ENABLE(PHYSICAL)) fault_register(.clk(clk),.reset(local_reset[0]),.fault_input(core_fault || cache_fault_seen || protocol_fault),.fault(fault));
  generate if(REGISTER_LAUNCH!=0)begin : launch_pipeline
    // Before a launch, neither the idle core nor its prefetched key window
    // is consumed except by start. Register these credits at the SLR boundary
    // instead of returning the complete ready tree through cache and core.
    // Consume the advertised credit on the launch edge, preventing a second
    // pulse while the registered core-busy state is catching up. Bind it to
    // the sampled output bank so a caller changing banks cannot reuse it.
    (* dont_touch="yes" *) reg cache_ready;
    (* dont_touch="yes" *) reg ready,ready_bank;
    always @(posedge clk)begin
      if(local_reset[0])begin cache_ready<=0;ready<=0;ready_bank<=0;end
      else begin
        cache_ready<=cache_window_seen && !cache_fault_seen && !protocol_fault_seen && !start && !launch_pending;
        ready<=core_ready && cache_ready && !core_fault && !start && !launch_pending;
        ready_bank<=start_bank;
      end
    end
    assign start_ready=ready && ready_bank==start_bank && !fault && !local_reset[0];
  end else begin : combinational_launch
    assign start_ready=core_ready && cache_window && !fault;
  end endgenerate
  genvar port;
  generate for(port=0;port<4;port=port+1)begin : streams
    assign load_data[port]=key_stream_data[port][503:0];
    assign stream_error[port]=key_stream_valid[port] && key_stream_ready[port] &&
      (key_stream_last[port]!=expected_last[port] || key_stream_data[port][511:504]!=0);
  end endgenerate
  PaperStreamingKeyCache #(.STEPS(STEPS),.WRITE_STAGES(3*PHYSICAL),.NEAR_GROUPS(5*PHYSICAL),.READ_STAGES(PHYSICAL)) cache(
    .clk(clk),.reset(local_reset[1]),.load_start_valid(1'b1),.load_step(next_load_step),.load_start_ready(load_start_ready),
    .load_valid(key_stream_valid),.load_ready(key_stream_ready),.load_data(load_data),.load_last(expected_last),
    .loaded_valid(loaded_valid),.loaded_step(loaded_step),.initial_ready(cache_initial),.initial_window_ready(cache_window),
    .read_valid(key_request),.read_retire(key_request && key_context==11 && key_row==5 && key_half),
    .read_step(key_step),.read_row(key_row),.read_half(key_half),.read_ready(cache_read_ready),
    .response_valid(key_valid),.key_real(key_real),.key_imag(key_imag),.fault(cache_fault));
  PaperStreamingBufferedBlindRotate #(.STEPS(STEPS),.DIGIT_REVERSED(1),.DSP_FORWARD_PAIRS(DSP_FORWARD_PAIRS),.KEY_READ_LATENCY(1+PHYSICAL),.STREAM_OUTPUT(1),.OVERLAP_INPUT(OVERLAP_INPUT),.FUSED_ACCUM_ROUND(FUSED_ACCUM_ROUND),.DSP_INVERSE_LAST(DSP_INVERSE_LAST),.BRAM_STATE_LANES(BRAM_STATE_LANES),.PHYSICAL(PHYSICAL)) core(
    .clk(clk),.reset(reset),.start(core_start),.start_bank(core_start_bank),.start_ready(core_ready),.busy(core_busy),.done(done),
    .exponent_load_valid(exponent_load_valid),.exponent_load_ready(exponent_load_ready),.exponent_load_context(exponent_load_context),
    .exponent_load_step(exponent_load_step),.exponent_load_value(exponent_load_value),
    .body_load_valid(body_load_valid),.body_load_context(body_load_context),.body_load_chunk(body_load_chunk),
    .body_load_low(body_load_low),.body_load_high(body_load_high),
    .next_body_full(next_body_full),.next_body_low(next_body_low),.next_body_high(next_body_high),
    .next_body_read_valid(next_body_read_valid),.next_body_read_context(next_body_read_context),.next_body_read_chunk(next_body_read_chunk),
    .key_request(key_request),.key_context(key_context),.key_step(key_step),.key_row(key_row),.key_half(key_half),
    .key_valid(key_valid),.key_real(key_real),.key_imag(key_imag),
    .output_available(output_available),.output_ready(output_ready),
    .read_valid(1'b0),.read_bank(1'b0),.read_context(4'd0),.read_word(11'd0),
    .response_valid(unused_response_valid),.response_word(unused_response_word),.release_valid(1'b0),.release_bank(1'b0),
    .stream_start(stream_start),.stream_bank(stream_bank),.stream_start_ready(stream_start_ready),
    .stream_busy(stream_busy),.stream_done(stream_done),.output_valid(output_valid),.output_accept(output_accept),
    .output_data(output_data),.output_keep(output_keep),.output_last(output_last),.fault(core_fault));
  always @(posedge clk)begin
    if(local_reset[0])begin next_load_step<=0;protocol_fault<=0;end
    else begin
      if(loaded_valid)next_load_step<=loaded_step==10'(STEPS-1)?10'd0:loaded_step+1'b1;
      // An enclosing wrapper may already qualify start with this exact
      // start_ready. In that connection the duplicate check is unreachable;
      // omit its long fault/ready return cone, retaining stream checks.
      if((CHECK_START_PROTOCOL!=0 && start && !start_ready) || (|stream_error))protocol_fault<=1;
    end
  end
endmodule

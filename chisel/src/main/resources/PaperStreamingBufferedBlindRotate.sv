// Feedback core plus two extracted-sample output banks. A reserved output bank
// makes the fixed arithmetic schedule independent of downstream read stalls.
module PaperStreamingBufferedBlindRotate #(
  parameter integer STEPS=586, DIGIT_REVERSED=1, DSP_FORWARD_PAIRS=0, KEY_READ_LATENCY=0, STREAM_OUTPUT=0, OVERLAP_INPUT=0, FUSED_ACCUM_ROUND=0, DSP_INVERSE_LAST=0, BRAM_STATE_LANES=0, PHYSICAL=0
)(
  input wire clk,reset,start,start_bank,
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
  output wire [1:0] output_available,output_ready,
  input wire read_valid,read_bank,
  input wire [3:0] read_context,
  input wire [10:0] read_word,
  output wire response_valid,
  output wire [31:0] response_word,
  input wire release_valid,release_bank,
  input wire stream_start,stream_bank,
  output wire stream_start_ready,stream_busy,stream_done,
  output wire output_valid,
  input wire output_accept,
  output wire [511:0] output_data,
  output wire [63:0] output_keep,
  output wire output_last,
  output wire fault
);
  wire core_ready,core_busy,core_done,core_fault,buffer_fault;
  wire final_valid;
  wire [3:0] final_context;
  wire [1:0] final_component,final_chunk;
  wire [16:0] final_low [0:63],final_high [0:63];
  wire unused_debug_ready;
  wire [16:0] unused_debug_code [0:511];
  wire dma_read_valid,dma_read_bank,dma_release_valid,dma_release_bank,dma_fault;
  wire [3:0] dma_read_context;
  wire [10:0] dma_read_word;
  wire selected_read_valid=STREAM_OUTPUT!=0?dma_read_valid:read_valid;
  wire selected_read_bank=STREAM_OUTPUT!=0?dma_read_bank:read_bank;
  wire [3:0] selected_read_context=STREAM_OUTPUT!=0?dma_read_context:read_context;
  wire [10:0] selected_read_word=STREAM_OUTPUT!=0?dma_read_word:read_word;
  wire selected_release_valid=STREAM_OUTPUT!=0?dma_release_valid:release_valid;
  wire selected_release_bank=STREAM_OUTPUT!=0?dma_release_bank:release_bank;
  reg protocol_fault;
  wire finishing=core_done;
  wire [2:0] local_reset;
  PaperStreamingResetFanout #(.ENABLE(PHYSICAL),.COPIES(3)) reset_fanout(.clk(clk),.reset(reset),.local_reset(local_reset));
  PaperStreamingFaultRegister #(.ENABLE(PHYSICAL)) fault_register(.clk(clk),.reset(local_reset[0]),.fault_input(core_fault || buffer_fault || dma_fault || protocol_fault),.fault(fault));
  assign start_ready=core_ready && output_available[start_bank] && !finishing && !fault;
  assign busy=core_busy || finishing;
  PaperStreamingBlindRotate #(.STEPS(STEPS),.DIGIT_REVERSED(DIGIT_REVERSED),.DSP_FORWARD_PAIRS(DSP_FORWARD_PAIRS),.KEY_READ_LATENCY(KEY_READ_LATENCY),.OVERLAP_INPUT(OVERLAP_INPUT),.FUSED_ACCUM_ROUND(FUSED_ACCUM_ROUND),.DSP_INVERSE_LAST(DSP_INVERSE_LAST),.BRAM_STATE_LANES(BRAM_STATE_LANES),.PHYSICAL(PHYSICAL)) core(
    .clk(clk),.reset(reset),.start(start && start_ready),.start_ready(core_ready),.busy(core_busy),.done(core_done),
    .exponent_load_valid(exponent_load_valid),.exponent_load_ready(exponent_load_ready),.exponent_load_context(exponent_load_context),
    .exponent_load_step(exponent_load_step),.exponent_load_value(exponent_load_value),
    .body_load_valid(body_load_valid),.body_load_context(body_load_context),.body_load_chunk(body_load_chunk),
    .body_load_low(body_load_low),.body_load_high(body_load_high),
    .next_body_full(next_body_full),.next_body_low(next_body_low),.next_body_high(next_body_high),
    .next_body_read_valid(next_body_read_valid),.next_body_read_context(next_body_read_context),.next_body_read_chunk(next_body_read_chunk),
    .key_request(key_request),.key_context(key_context),.key_step(key_step),.key_row(key_row),.key_half(key_half),
    .key_valid(key_valid),.key_real(key_real),.key_imag(key_imag),
    .debug_read_valid(1'b0),.debug_context(4'd0),.debug_component(2'd0),
    .debug_ready(unused_debug_ready),.debug_code(unused_debug_code),
    .final_valid(final_valid),.final_context(final_context),.final_component(final_component),.final_chunk(final_chunk),
    .final_low(final_low),.final_high(final_high),.fault(core_fault));
  PaperStreamingSampleBuffer17 samples(
    .clk(clk),.reset(local_reset[1]),.reserve_valid(start && start_ready),.reserve_bank(start_bank),
    .available(output_available),.ready(output_ready),.capture_valid(final_valid),.capture_context(final_context),
    .capture_component(final_component),.capture_chunk(final_chunk),.capture_low(final_low),.capture_high(final_high),
    .read_valid(selected_read_valid),.read_bank(selected_read_bank),.read_context(selected_read_context),.read_word(selected_read_word),
    .response_valid(response_valid),.response_word(response_word),.release_valid(selected_release_valid),.release_bank(selected_release_bank),
    .fault(buffer_fault));
  generate if(STREAM_OUTPUT!=0)begin : streamed_output
    PaperStreamingSampleStream dma(
      .clk(clk),.reset(local_reset[2]),.start(stream_start),.start_bank(stream_bank),.sample_ready(output_ready),
      .start_ready(stream_start_ready),.busy(stream_busy),.done(stream_done),
      .read_valid(dma_read_valid),.read_bank(dma_read_bank),.read_context(dma_read_context),.read_word(dma_read_word),
      .response_valid(response_valid),.response_word(response_word),
      .release_valid(dma_release_valid),.release_bank(dma_release_bank),
      .output_valid(output_valid),.output_ready(output_accept),.output_data(output_data),
      .output_keep(output_keep),.output_last(output_last),.fault(dma_fault));
  end else begin : serial_output
    assign dma_read_valid=0;assign dma_read_bank=0;assign dma_read_context=0;assign dma_read_word=0;
    assign dma_release_valid=0;assign dma_release_bank=0;assign dma_fault=0;
    assign stream_start_ready=0;assign stream_busy=0;assign stream_done=0;
    assign output_valid=0;assign output_data=0;assign output_keep=0;assign output_last=0;
  end endgenerate
  always @(posedge clk)begin
    if(local_reset[0])begin done<=0;protocol_fault<=0;end
    else begin
      done<=core_done;
      if(start && !start_ready)protocol_fault<=1;
      if(STREAM_OUTPUT!=0 && (read_valid || release_valid))protocol_fault<=1;
    end
  end
endmodule

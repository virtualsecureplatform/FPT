// DataMover command/stream/status boundary for the paper streaming backend.
// Vendor AXI masters remain outside this module, so the complete custom DMA
// protocol can be verified against ordinary encrypted fixtures before linking.
module PaperStreamingDmaEngine #(
  parameter integer STEPS=586, BRAM_STATE_LANES=112,
  PHYSICAL=1, INPUT_SLICES=PHYSICAL, KEY_SLICES=4*PHYSICAL, OUTPUT_SLICES=PHYSICAL
)(
  input wire clk,reset,start,
  output wire idle,ready,busy,done,
  input wire [31:0] batch_count,
  input wire [63:0] input_pointer,output_pointer,
  input wire [63:0] key_pointer [0:3],
  output wire [5:0] command_valid,
  input wire [5:0] command_ready,
  output wire [103:0] command_data [0:5],
  input wire [5:0] status_valid,dma_error,
  input wire [7:0] status_data [0:5],
  input wire input_valid,input_last,
  output wire input_ready,
  input wire [511:0] input_data,
  input wire [3:0] key_valid,key_last,
  output wire [3:0] key_ready,
  input wire [511:0] key_data [0:3],
  output wire output_valid,output_last,
  input wire output_ready,
  output wire [511:0] output_data,
  output wire [63:0] output_keep,
  output wire [31:0] status,cycles,first_compute_cycle,last_compute_cycle,
  output wire [31:0] input_frames,computes_started,computes_done,streams_done,
  output reg [31:0] key_beats,key_blocked_cycles
);
  wire engine_reset,decoder_ready,lookup_valid,lookup_ready,tlwe_valid,tlwe_ready,input_frame_done,decoder_fault;
  wire [8:0] lookup_index;
  wire [31:0] lookup_value,tlwe_value;
  wire [3:0] tlwe_context;
  wire [9:0] tlwe_word;
  wire compute_start,compute_bank,compute_ready,compute_done,raw_busy,raw_fault;
  wire stream_start,stream_bank,stream_ready,stream_done;
  wire [1:0] sample_available,sample_ready;
  wire [3:0] raw_key_ready;
  wire raw_output_valid,raw_output_last;
  wire [8:0] errors;
  wire key_request;
  wire [3:0] unused_key_context;
  wire [9:0] unused_key_step;
  wire [2:0] unused_key_row;
  wire unused_key_half,unused_stream_busy;
  assign status={busy,22'b0,errors};
  // One registered root, then uniform two-register copies in every block:
  // the whole engine leaves reset on the same edge, three cycles after RUN.
  wire reset_root;
  generate if(PHYSICAL!=0)begin : registered_reset_root
    (* keep="true" *) reg root=1'b1;
    always @(posedge clk)root<=engine_reset;
    assign reset_root=root;
  end else begin : direct_reset_root
    assign reset_root=engine_reset;
  end endgenerate
  wire [4:0] local_reset;
  PaperStreamingResetFanout #(.ENABLE(PHYSICAL),.COPIES(5)) reset_fanout(.clk(clk),.reset(reset_root),.local_reset(local_reset));
  // Stop the vendor streams immediately; release them with the engine.
  wire boundary_reset=engine_reset || local_reset[0];
  // Registered slices carry the DataMover streams across SLR boundaries.
  wire slice_input_ready,decoder_valid,decoder_last;
  wire [511:0] decoder_data;
  assign input_ready=slice_input_ready && !boundary_reset;
  PaperStreamingStreamPipeline #(.WIDTH(513),.STAGES(INPUT_SLICES)) input_slices(
    .clk(clk),.reset(local_reset[2]),.input_valid(input_valid && !boundary_reset),.input_ready(slice_input_ready),
    .input_data({input_last,input_data}),.output_valid(decoder_valid),.output_ready(decoder_ready),
    .output_data({decoder_last,decoder_data}));
  wire [3:0] slice_key_ready,raw_key_valid,raw_key_last;
  wire [511:0] raw_key_data [0:3];
  assign key_ready=slice_key_ready & {4{!boundary_reset}};
  genvar port;
  generate for(port=0;port<4;port=port+1)begin : key_slices
    PaperStreamingStreamPipeline #(.WIDTH(513),.STAGES(KEY_SLICES)) slices(
      .clk(clk),.reset(local_reset[3]),.input_valid(key_valid[port] && !boundary_reset),.input_ready(slice_key_ready[port]),
      .input_data({key_last[port],key_data[port]}),.output_valid(raw_key_valid[port]),.output_ready(raw_key_ready[port]),
      .output_data({raw_key_last[port],raw_key_data[port]}));
  end endgenerate
  wire raw_output_accept,slice_output_valid;
  wire [511:0] raw_output_data;
  wire [63:0] raw_output_keep;
  assign output_valid=slice_output_valid && !boundary_reset;
  PaperStreamingStreamPipeline #(.WIDTH(577),.STAGES(OUTPUT_SLICES)) output_slices(
    .clk(clk),.reset(local_reset[4]),.input_valid(raw_output_valid),.input_ready(raw_output_accept),
    .input_data({raw_output_last,raw_output_keep,raw_output_data}),.output_valid(slice_output_valid),
    .output_ready(output_ready && !boundary_reset),.output_data({output_last,output_keep,output_data}));
  PaperStreamingDmaSequencer #(.STEPS(STEPS),.REGISTER_COMPUTE_START(1),.REGISTER_STREAM_START(PHYSICAL)) sequencer(
    .clk(clk),.reset(reset),.start(start),.idle(idle),.ready(ready),.busy(busy),.done(done),.engine_reset(engine_reset),
    .batch_count(batch_count),.input_pointer(input_pointer),.output_pointer(output_pointer),.key_pointer(key_pointer),
    .command_valid(command_valid),.command_ready(command_ready),.command_data(command_data),
    .status_valid(status_valid),.dma_error(dma_error),.status_data(status_data),
    .input_frame_done(input_frame_done),.key_frame_done(key_valid & key_ready & key_last),
    .engine_fault(raw_fault),.decoder_fault(decoder_fault),
    .compute_start(compute_start),.compute_bank(compute_bank),.compute_ready(compute_ready),.compute_done(compute_done),
    .stream_start(stream_start),.stream_bank(stream_bank),.stream_ready(stream_ready),.stream_done(stream_done),
    .errors(errors),.cycles(cycles),.first_compute_cycle(first_compute_cycle),.last_compute_cycle(last_compute_cycle),
    .input_frames(input_frames),.computes_started(computes_started),.computes_done(computes_done),.streams_done(streams_done));
  PaperStreamingInputDecoder #(.STEPS(STEPS),.REFILL_ON_POP(0)) decoder(
    .clk(clk),.reset(local_reset[1]),.input_valid(decoder_valid),.input_last(decoder_last),
    .input_ready(decoder_ready),.input_data(decoder_data),
    .lookup_valid(lookup_valid),.lookup_ready(lookup_ready),.lookup_index(lookup_index),.lookup_value(lookup_value),
    .tlwe_valid(tlwe_valid),.tlwe_ready(tlwe_ready),.tlwe_context(tlwe_context),.tlwe_word(tlwe_word),.tlwe_value(tlwe_value),
    .frame_done(input_frame_done),.fault(decoder_fault));
  PaperStreamingRawBlindRotate #(.STEPS(STEPS),.BRAM_STATE_LANES(BRAM_STATE_LANES),.DSP_INVERSE_LAST(1),.FUSED_ACCUM_ROUND(0),.REGISTER_LAUNCH(1),.REGISTER_PREP_FAULT(1),.PHYSICAL(PHYSICAL)) engine(
    .clk(clk),.reset(reset_root),.start(compute_start),.start_bank(compute_bank),.start_ready(compute_ready),.busy(raw_busy),.done(compute_done),
    .lookup_valid(lookup_valid),.lookup_ready(lookup_ready),.lookup_index(lookup_index),.lookup_value(lookup_value),
    .tlwe_valid(tlwe_valid),.tlwe_ready(tlwe_ready),.tlwe_context(tlwe_context),.tlwe_word(tlwe_word),.tlwe_value(tlwe_value),
    .key_stream_valid(raw_key_valid),.key_stream_last(raw_key_last),.key_stream_ready(raw_key_ready),.key_stream_data(raw_key_data),
    .key_request(key_request),.key_context(unused_key_context),.key_step(unused_key_step),.key_row(unused_key_row),.key_half(unused_key_half),
    .output_available(sample_available),.output_ready(sample_ready),
    .stream_start(stream_start),.stream_bank(stream_bank),.stream_start_ready(stream_ready),.stream_busy(unused_stream_busy),.stream_done(stream_done),
    .output_valid(raw_output_valid),.output_accept(raw_output_accept),.output_data(raw_output_data),
    .output_keep(raw_output_keep),.output_last(raw_output_last),.fault(raw_fault));
  reg key_request_seen;
  always @(posedge clk)key_request_seen<=key_request && !local_reset[0];
  always @(posedge clk)begin
    if(reset || (start && idle))begin key_beats<=0;key_blocked_cycles<=0;end
    else if(!engine_reset)begin
      if(key_request_seen)key_beats<=key_beats+1'b1;
      if(|(key_valid & ~key_ready))key_blocked_cycles<=key_blocked_cycles+1'b1;
    end
  end
endmodule

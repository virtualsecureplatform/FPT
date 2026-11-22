// One finite multi-batch job over the existing six DataMover channels:
// input, four key images, output. Each key image repeats from its base address.
// Success waits for all stream frames and all DMA status responses, including
// the final output write acknowledgement. Pointer registers snapshot at start.
module PaperStreamingDmaSequencer #(
  parameter integer STEPS=586, REGISTER_COMPUTE_START=0, REGISTER_STREAM_START=0
)(
  input wire clk,reset,start,
  output wire idle,ready,busy,engine_reset,
  output reg done,
  input wire [31:0] batch_count,
  input wire [63:0] input_pointer,output_pointer,
  input wire [63:0] key_pointer [0:3],
  output wire [5:0] command_valid,
  input wire [5:0] command_ready,
  output wire [103:0] command_data [0:5],
  input wire [5:0] status_valid,dma_error,
  input wire [7:0] status_data [0:5],
  input wire input_frame_done,
  input wire [3:0] key_frame_done,
  input wire engine_fault,decoder_fault,
  output wire compute_start,compute_bank,
  input wire compute_ready,compute_done,
  output wire stream_start,stream_bank,
  input wire stream_ready,stream_done,
  output reg [8:0] errors,
  output reg [31:0] cycles,first_compute_cycle,last_compute_cycle,
  output reg [31:0] input_frames,computes_started,computes_done,streams_done
);
  localparam [1:0] IDLE=0,RESET_ENGINE=1,RUN=2;
  localparam integer INPUT_STRIDE=((512+12*(STEPS+1)+15)/16)*64;
  localparam integer OUTPUT_BYTES=49200,OUTPUT_STRIDE=49216;
  reg [1:0] state;
  reg [31:0] target,streams_started;
  reg [31:0] commands [0:5],statuses [0:5],key_frames [0:3];
  reg [63:0] input_address,output_address,keys [0:3];
  wire [5:0] channel_complete;
  wire [3:0] key_complete;
  assign idle=state==IDLE;assign ready=idle;assign busy=!idle;
  assign engine_reset=reset || state!=RUN;
  generate if(REGISTER_COMPUTE_START!=0)begin : registered_compute_launch
    // Readiness is consumed only by this launch during a legal idle interval.
    // Keep one pending request, with the counter/bank unchanged until it fires,
    // to break the ready -> sequencer -> start round trip across SLRs.
    // The raw engine still checks readiness and faults on the launch edge.
    reg pending;
    always @(posedge clk)begin
      if(reset || state!=RUN)pending<=0;
      else pending<=!pending && errors==0 && computes_started<target && compute_ready;
    end
    assign compute_start=state==RUN && errors==0 && pending;
  end else begin : combinational_compute_launch
    assign compute_start=state==RUN && errors==0 && computes_started<target && compute_ready;
  end endgenerate
  assign compute_bank=computes_started[0];
  generate if(REGISTER_STREAM_START!=0)begin : registered_stream_launch
    // The sample stream's readiness is consumed only by this launch and
    // falls on the edge after start; one pending request cuts the round trip.
    reg pending;
    always @(posedge clk)begin
      if(reset || state!=RUN)pending<=0;
      else pending<=!pending && errors==0 && streams_started<computes_done && stream_ready;
    end
    assign stream_start=state==RUN && errors==0 && pending;
  end else begin : combinational_stream_launch
    assign stream_start=state==RUN && errors==0 && streams_started<computes_done && stream_ready;
  end endgenerate
  assign stream_bank=streams_started[0];
  function automatic [103:0] make_command(input [63:0] address,input [22:0] bytes);
    make_command={8'b0,address,1'b0,1'b1,6'b0,1'b1,bytes};
  endfunction
  assign command_data[0]=make_command(input_address,23'(INPUT_STRIDE));
  assign command_data[5]=make_command(output_address,23'(OUTPUT_BYTES));
  genvar port;
  generate for(port=0;port<6;port=port+1)begin : channels
    assign command_valid[port]=state==RUN && errors==0 && commands[port]<target;
    assign channel_complete[port]=commands[port]==target && statuses[port]==target;
  end
  for(port=0;port<4;port=port+1)begin : key_channels
    localparam integer BYTES=(port==3?120:132)*STEPS*64;
    assign command_data[port+1]=make_command(keys[port],23'(BYTES));
    assign key_complete[port]=key_frames[port]==target;
  end endgenerate
  wire all_complete=(&channel_complete) && (&key_complete) && input_frames==target &&
    computes_started==target && computes_done==target && streams_started==target && streams_done==target;
  integer channel;
  always @(posedge clk)begin
    if(reset)begin
      state<=IDLE;done<=0;target<=0;errors<=0;cycles<=0;first_compute_cycle<=0;last_compute_cycle<=0;
      input_frames<=0;computes_started<=0;computes_done<=0;streams_started<=0;streams_done<=0;
      input_address<=0;output_address<=0;
      for(channel=0;channel<6;channel=channel+1)begin commands[channel]<=0;statuses[channel]<=0;end
      for(channel=0;channel<4;channel=channel+1)begin key_frames[channel]<=0;keys[channel]<=0;end
    end else begin
      done<=0;
      if(state==IDLE && start)begin
        state<=RESET_ENGINE;target<=batch_count;input_address<=input_pointer;output_address<=output_pointer;
        errors<=0;cycles<=0;first_compute_cycle<=0;last_compute_cycle<=0;
        input_frames<=0;computes_started<=0;computes_done<=0;streams_started<=0;streams_done<=0;
        for(channel=0;channel<6;channel=channel+1)begin commands[channel]<=0;statuses[channel]<=0;end
        for(channel=0;channel<4;channel=channel+1)begin key_frames[channel]<=0;keys[channel]<=key_pointer[channel];end
      end
      if(state==RESET_ENGINE)begin
        if(target==0 || target>4096 || (|input_address[5:0]) || (|output_address[5:0]) ||
           (|keys[0][5:0]) || (|keys[1][5:0]) || (|keys[2][5:0]) || (|keys[3][5:0]))begin
          errors[7]<=1;done<=1;state<=IDLE;
        end else state<=RUN;
      end
      if(state==RUN)begin
        cycles<=cycles+1'b1;
        if(engine_fault || decoder_fault)errors[0]<=1;
        for(channel=0;channel<6;channel=channel+1)begin
          if(command_valid[channel] && command_ready[channel])begin
            commands[channel]<=commands[channel]+1'b1;
            if(channel==0)input_address<=input_address+64'(INPUT_STRIDE);
            if(channel==5)output_address<=output_address+64'(OUTPUT_STRIDE);
          end
          if(status_valid[channel])begin
            if(statuses[channel]>=commands[channel])errors[8]<=1;
            else statuses[channel]<=statuses[channel]+1'b1;
            if(!status_data[channel][7] || (|status_data[channel][6:4]))errors[channel+1]<=1;
          end
          if(dma_error[channel])errors[channel+1]<=1;
        end
        for(channel=0;channel<4;channel=channel+1)if(key_frame_done[channel])begin
          if(key_frames[channel]>=target)errors[8]<=1;else key_frames[channel]<=key_frames[channel]+1'b1;
        end
        if(input_frame_done)begin
          if(input_frames>=target)errors[8]<=1;else input_frames<=input_frames+1'b1;
        end
        if(compute_start)begin
          if(computes_started==0)first_compute_cycle<=cycles;
          last_compute_cycle<=cycles;computes_started<=computes_started+1'b1;
        end
        if(compute_done)begin
          if(computes_done>=computes_started)errors[8]<=1;else computes_done<=computes_done+1'b1;
        end
        if(stream_start)streams_started<=streams_started+1'b1;
        if(stream_done)begin
          if(streams_done>=streams_started)errors[8]<=1;else streams_done<=streams_done+1'b1;
        end
        if(all_complete)begin done<=1;state<=IDLE;end
      end
    end
  end
  initial if(STEPS<1 || STEPS>586)$fatal(1,"invalid DMA sequencer geometry");
endmodule

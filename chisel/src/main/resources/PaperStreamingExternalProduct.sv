// Complete fixed-schedule guarded32 external product. Six folded digit rows
// enter in twelve beats. Keys are requested at the FFT consumer boundary in
// native butterfly-group order, and are reused across twelve contexts/step.
// Output is a modular Torus update: each lane contains coefficients j,j+256.
module PaperStreamingExternalProduct #(
  parameter integer ACC_WIDTH=48, FUSED_ROUND=0, LEG_GUARD=-1, STEPS=586, DIGIT_REVERSED=0, DSP_FORWARD_PAIRS=0, KEY_READ_LATENCY=0, FUSED_ACCUM_ROUND=0, DSP_INVERSE_LAST=0,
  PHYSICAL=0, DIGIT_STAGES=2*PHYSICAL, TWIST_STAGES=0, SPECTRUM_STAGES=2*PHYSICAL, MAC_STAGES=4*PHYSICAL,
  // Component-0 MAC lanes computed beside the forward transform (see MacTile).
  MAC_NEAR_LANES=45*PHYSICAL, NEAR_PORT=MAC_NEAR_LANES>0?MAC_NEAR_LANES:1
)(
  input wire clk,reset,input_valid,
  input wire signed [7:0] digit_low [0:127],digit_high [0:127],
  output wire key_request,
  output wire [2:0] key_row,
  output wire key_half,
  output reg [3:0] key_context,
  output reg [9:0] key_step,
  input wire key_valid,
  input wire signed [27:0] key_real [0:2][0:127],key_imag [0:2][0:127],
  output wire output_valid,
  output reg [3:0] output_context,
  output reg [9:0] output_step,
  output wire [1:0] output_component,output_chunk,
  output wire [31:0] output_low [0:63],output_high [0:63],
  output wire fault
);
  wire forward_valid,mac_valid,inverse_valid;
  wire forward_fault,mac_fault,inverse_fault;
  wire signed [31:0] spectrum_real [0:127],spectrum_imag [0:127];
  wire signed [31:0] digit_real [0:127],digit_imag [0:127];
  wire signed [31:0] inverse_input_real [0:63],inverse_input_imag [0:63];
  wire signed [31:0] inverse_real [0:63],inverse_imag [0:63];
  reg [3:0] input_phase,key_phase,output_phase;
  reg protocol_fault;
  wire consumer_valid;
  wire [3:0] consumer_context;
  wire signed [31:0] consumer_real [0:127],consumer_imag [0:127];
  localparam [9:0] LAST_STEP=10'(STEPS-1);
  assign key_request=forward_valid;
  assign key_row=key_phase[3:1];assign key_half=key_phase[0];
  assign output_valid=inverse_valid;
  assign output_component=output_phase[3:2];assign output_chunk=output_phase[1:0];
  wire [5:0] local_reset;
  PaperStreamingResetFanout #(.ENABLE(PHYSICAL),.COPIES(6)) reset_fanout(.clk(clk),.reset(reset),.local_reset(local_reset));
  PaperStreamingFaultRegister #(.ENABLE(PHYSICAL)) fault_register(.clk(clk),.reset(local_reset[0]),.fault_input(protocol_fault || forward_fault || mac_fault || inverse_fault),.fault(fault));
  // Fixed-latency crossing banks: frontend digits to the forward transform,
  // spectrum to the MAC/key boundary, and MAC rows to the inverse transform.
  wire staged_valid,fft_valid,mac_raw_valid;
  wire signed [7:0] staged_low [0:127],staged_high [0:127];
  wire signed [31:0] fft_real [0:127],fft_imag [0:127];
  wire signed [31:0] mac_raw_real [0:63],mac_raw_imag [0:63];
  PaperStreamingLanePipeline #(.LANES(128),.WIDTH(8),.STAGES(DIGIT_STAGES)) digit_pipeline(
    .clk(clk),.reset(local_reset[1]),.input_valid(input_valid),.input_real(digit_low),.input_imag(digit_high),
    .output_valid(staged_valid),.output_real(staged_low),.output_imag(staged_high));
  PaperStreamingLanePipeline #(.LANES(128),.WIDTH(32),.STAGES(SPECTRUM_STAGES)) spectrum_pipeline(
    .clk(clk),.reset(local_reset[2]),.input_valid(fft_valid),.input_real(fft_real),.input_imag(fft_imag),
    .output_valid(forward_valid),.output_real(spectrum_real),.output_imag(spectrum_imag));
  wire near_valid,near_select;
  wire signed [31:0] near_spectrum_real [0:NEAR_PORT-1],near_spectrum_imag [0:NEAR_PORT-1];
  wire signed [31:0] near_row_real [0:NEAR_PORT-1],near_row_imag [0:NEAR_PORT-1];
  localparam integer FAR_STAGES=MAC_STAGES/2;
  genvar lane;
  generate if(MAC_NEAR_LANES!=0)begin : split_output
    // The near group sees the transform output through its own delay line and
    // its rounded rows replace the tile's rows half way down the MAC pipeline.
    wire signed [31:0] tap_real [0:NEAR_PORT-1],tap_imag [0:NEAR_PORT-1];
    wire middle_valid;
    wire signed [31:0] middle_real [0:63],middle_imag [0:63],merged_real [0:63],merged_imag [0:63];
    for(lane=0;lane<MAC_NEAR_LANES;lane=lane+1)begin : taps
      assign tap_real[lane]=fft_real[lane];assign tap_imag[lane]=fft_imag[lane];
    end
    for(lane=0;lane<64;lane=lane+1)begin : merge
      if(lane<MAC_NEAR_LANES)begin : near
        assign merged_real[lane]=near_select?near_row_real[lane]:middle_real[lane];
        assign merged_imag[lane]=near_select?near_row_imag[lane]:middle_imag[lane];
      end else begin : far
        assign merged_real[lane]=middle_real[lane];assign merged_imag[lane]=middle_imag[lane];
      end
    end
    PaperStreamingKeptLanePipeline #(.LANES(MAC_NEAR_LANES),.WIDTH(32),.STAGES(SPECTRUM_STAGES+KEY_READ_LATENCY)) spectrum_pipeline(
      .clk(clk),.reset(local_reset[5]),.input_valid(fft_valid),.input_real(tap_real),.input_imag(tap_imag),
      .output_valid(near_valid),.output_real(near_spectrum_real),.output_imag(near_spectrum_imag));
    PaperStreamingLanePipeline #(.LANES(64),.WIDTH(32),.STAGES(FAR_STAGES)) far_pipeline(
      .clk(clk),.reset(local_reset[4]),.input_valid(mac_raw_valid),.input_real(mac_raw_real),.input_imag(mac_raw_imag),
      .output_valid(middle_valid),.output_real(middle_real),.output_imag(middle_imag));
    PaperStreamingLanePipeline #(.LANES(64),.WIDTH(32),.STAGES(MAC_STAGES-FAR_STAGES)) near_pipeline(
      .clk(clk),.reset(local_reset[4]),.input_valid(middle_valid),.input_real(merged_real),.input_imag(merged_imag),
      .output_valid(mac_valid),.output_real(inverse_input_real),.output_imag(inverse_input_imag));
  end else begin : single_output
    assign near_valid=0;assign near_spectrum_real[0]=0;assign near_spectrum_imag[0]=0;
    PaperStreamingLanePipeline #(.LANES(64),.WIDTH(32),.STAGES(MAC_STAGES)) mac_pipeline(
      .clk(clk),.reset(local_reset[4]),.input_valid(mac_raw_valid),.input_real(mac_raw_real),.input_imag(mac_raw_imag),
      .output_valid(mac_valid),.output_real(inverse_input_real),.output_imag(inverse_input_imag));
  end endgenerate
  generate for(lane=0;lane<128;lane=lane+1)begin : digit_expansion
    assign digit_real[lane]={staged_low[lane][7],staged_low[lane],23'b0};
    assign digit_imag[lane]={staged_high[lane][7],staged_high[lane],23'b0};
  end
  for(lane=0;lane<64;lane=lane+1)begin : torus_conversion
    // The inverse defers 1/256 normalization: its logical binary point is
    // 9+8=17. High integer bits intentionally wrap only at this boundary.
    assign output_low[lane]=inverse_real[lane]<<15;
    assign output_high[lane]=inverse_imag[lane]<<15;
  end endgenerate
  generate if(KEY_READ_LATENCY>=1)begin : synchronous_key
    (* shreg_extract="no" *) reg [KEY_READ_LATENCY-1:0] delayed_valid;
    (* shreg_extract="no" *) reg [3:0] delayed_context [0:KEY_READ_LATENCY-1];
    integer delay;
    always @(posedge clk)begin
      if(local_reset[3])delayed_valid<=0;
      else delayed_valid<=KEY_READ_LATENCY'({delayed_valid,forward_valid});
      delayed_context[0]<=key_context;
      for(delay=1;delay<KEY_READ_LATENCY;delay=delay+1)delayed_context[delay]<=delayed_context[delay-1];
    end
    for(lane=0;lane<128;lane=lane+1)begin : payload
      (* shreg_extract="no" *) reg signed [31:0] delayed_real [0:KEY_READ_LATENCY-1],delayed_imag [0:KEY_READ_LATENCY-1];
      integer index;
      always @(posedge clk)begin
        delayed_real[0]<=spectrum_real[lane];delayed_imag[0]<=spectrum_imag[lane];
        for(index=1;index<KEY_READ_LATENCY;index=index+1)begin
          delayed_real[index]<=delayed_real[index-1];delayed_imag[index]<=delayed_imag[index-1];
        end
      end
      assign consumer_real[lane]=delayed_real[KEY_READ_LATENCY-1];assign consumer_imag[lane]=delayed_imag[KEY_READ_LATENCY-1];
    end
    assign consumer_valid=delayed_valid[KEY_READ_LATENCY-1];assign consumer_context=delayed_context[KEY_READ_LATENCY-1];
  end else begin : immediate_key
    assign consumer_valid=forward_valid;assign consumer_context=key_context;
    for(lane=0;lane<128;lane=lane+1)begin : payload
      assign consumer_real[lane]=spectrum_real[lane];assign consumer_imag[lane]=spectrum_imag[lane];
    end
  end endgenerate
  PaperStreamingTransform #(.LANES(128),.INVERSE(0),.NATIVE(1),.DIGIT_REVERSED(DIGIT_REVERSED),.FUSED_ROUND(FUSED_ROUND),.LEG_GUARD(LEG_GUARD),.DSP_FORWARD_PAIRS(DSP_FORWARD_PAIRS),.PHYSICAL(PHYSICAL),.TWIST_STAGES(TWIST_STAGES),.TWIST_ROM(PHYSICAL)) forward_fft(
    .clk(clk),.reset(reset),.input_valid(staged_valid),.input_real(digit_real),.input_imag(digit_imag),
    .output_valid(fft_valid),.output_real(fft_real),.output_imag(fft_imag),.fault(forward_fault));
  PaperStreamingMacTile #(.LANES(128),.ACC_WIDTH(ACC_WIDTH),.FUSED_ACCUM_ROUND(FUSED_ACCUM_ROUND),.PHYSICAL(PHYSICAL),.NEAR_LANES(MAC_NEAR_LANES),.NEAR_OUTPUT_DELAY(FAR_STAGES)) mac(
    .clk(clk),.reset(reset),.input_valid(consumer_valid),.input_context(consumer_context),
    .spectrum_real(consumer_real),.spectrum_imag(consumer_imag),.key_real(key_real),.key_imag(key_imag),
    .output_valid(mac_raw_valid),.output_context(),.output_component(),.output_chunk(),
    .output_real(mac_raw_real),.output_imag(mac_raw_imag),
    .near_valid(near_valid),.near_spectrum_real(near_spectrum_real),.near_spectrum_imag(near_spectrum_imag),
    .near_select(near_select),.near_output_real(near_row_real),.near_output_imag(near_row_imag),.fault(mac_fault));
  PaperStreamingTransform #(.LANES(64),.INVERSE(1),.NATIVE(1),.FUSED_ROUND(FUSED_ROUND),.LEG_GUARD(LEG_GUARD),.DSP_INVERSE_LAST(DSP_INVERSE_LAST),.PHYSICAL(PHYSICAL)) inverse_fft(
    .clk(clk),.reset(reset),.input_valid(mac_valid),.input_real(inverse_input_real),.input_imag(inverse_input_imag),
    .output_valid(inverse_valid),.output_real(inverse_real),.output_imag(inverse_imag),.fault(inverse_fault));
  always @(posedge clk)begin
    if(local_reset[0])begin
      input_phase<=0;key_phase<=0;key_context<=0;key_step<=0;
      output_phase<=0;output_context<=0;output_step<=0;protocol_fault<=0;
    end else begin
      if(input_valid)begin
        if(input_phase==11)input_phase<=0;
        else input_phase<=input_phase+1'b1;
      end else if(input_phase!=0)protocol_fault<=1;
      if(key_valid!=consumer_valid)protocol_fault<=1;
      if(forward_valid)begin
        if(key_phase==11)begin
          key_phase<=0;
          if(key_context==11)begin
            key_context<=0;
            if(key_step==LAST_STEP)key_step<=0;else key_step<=key_step+1'b1;
          end else key_context<=key_context+1'b1;
        end else key_phase<=key_phase+1'b1;
      end
      if(inverse_valid)begin
        if(output_phase==11)begin
          output_phase<=0;
          if(output_context==11)begin
            output_context<=0;
            if(output_step==LAST_STEP)output_step<=0;else output_step<=output_step+1'b1;
          end else output_context<=output_context+1'b1;
        end else output_phase<=output_phase+1'b1;
      end
    end
  end
  initial if(MAC_NEAR_LANES!=0 && (PHYSICAL==0 || KEY_READ_LATENCY<1 || MAC_STAGES<2))$fatal(1,"near MAC group needs the physical pipeline");
  initial if(KEY_READ_LATENCY<0 || KEY_READ_LATENCY>3)$fatal(1,"unsupported key read latency");
  initial if(STEPS<1 || STEPS>586)$fatal(1,"invalid blind-rotation dimension");
endmodule

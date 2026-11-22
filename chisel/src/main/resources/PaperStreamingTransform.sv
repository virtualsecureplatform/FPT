// Opt-in guarded32 folded negacyclic transform. NATIVE=0 uses natural order;
// NATIVE=1 uses stage-3 butterfly groups (point j/4+(j%4)*64) at the forward
// output and both inverse ports, eliminating the final reorder buffers.
// DIGIT_REVERSED=1 accepts forward points in base-4 digit-reversed order,
// applies their matching twists, and removes the initial frame permutation.
// Forward: 128 lanes, two beats/frame. Inverse: 64 lanes, four beats/frame.
// The inverse keeps its eight normalization bits in the output binary point.
module PaperStreamingTransform #(
  parameter integer INVERSE=0, LANES=128, NATIVE=0, FUSED_ROUND=0, LEG_GUARD=-1, DIGIT_REVERSED=0, DSP_FORWARD_PAIRS=0, DSP_INVERSE_LAST=0, PHYSICAL=0, TWIST_STAGES=0, TWIST_ROM=0
)(
  input wire clk, reset, input_valid,
  input wire signed [31:0] input_real [0:LANES-1],input_imag [0:LANES-1],
  output wire output_valid,
  output wire signed [31:0] output_real [0:LANES-1],output_imag [0:LANES-1],
  output wire fault
);
  wire signed [31:0] data_real [0:7][0:LANES-1] /* verilator split_var */;
  wire signed [31:0] data_imag [0:7][0:LANES-1] /* verilator split_var */;
  wire [7:0] valid;
  wire [8:0] faults;
  localparam integer FINAL_NODE=NATIVE!=0?6:7;
  wire [7:0] local_reset;
  PaperStreamingResetFanout #(.ENABLE(PHYSICAL),.COPIES(8)) reset_fanout(.clk(clk),.reset(reset),.local_reset(local_reset));
  PaperStreamingFaultRegister #(.ENABLE(PHYSICAL)) fault_register(.clk(clk),.reset(local_reset[7]),.fault_input(|faults),.fault(fault));
  wire twist_valid;
  wire signed [31:0] twist_real [0:LANES-1],twist_imag [0:LANES-1];
  genvar lane,stage;
  generate if(INVERSE==0)begin : forward_twist
    PaperStreamingTwist #(.LANES(LANES),.MODE(1),.DIGIT_REVERSED(DIGIT_REVERSED),.PRODUCT_PIPELINE(PHYSICAL),.ROM(TWIST_ROM)) twist(
      .clk(clk),.reset(local_reset[0]),.input_valid(input_valid),.input_real(input_real),.input_imag(input_imag),
      .output_valid(twist_valid),.output_real(twist_real),.output_imag(twist_imag),.fault(faults[0]));
    // Optional crossing bank between the twist and the first butterfly stage.
    PaperStreamingLanePipeline #(.LANES(LANES),.WIDTH(32),.STAGES(TWIST_STAGES)) twist_pipeline(
      .clk(clk),.reset(local_reset[6]),.input_valid(twist_valid),.input_real(twist_real),.input_imag(twist_imag),
      .output_valid(valid[0]),.output_real(data_real[0]),.output_imag(data_imag[0]));
  end else begin : inverse_input
    assign valid[0]=input_valid;assign faults[0]=0;
    for(lane=0;lane<LANES;lane=lane+1)begin : wires
      assign data_real[0][lane]=input_real[lane];assign data_imag[0][lane]=input_imag[lane];
    end
  end
  for(stage=0;stage<4;stage=stage+1)begin : stages
    localparam integer INPUT_NODE=stage==3?5:stage+1;
    localparam integer OUTPUT_NODE=stage==3?6:stage+2;
    wire stage_valid;
    wire signed [31:0] stage_real [0:LANES-1],stage_imag [0:LANES-1];
    PaperStreamingFFTStage #(.LANES(LANES),.STAGE(stage),.INVERSE(INVERSE),.FUSED_ROUND(FUSED_ROUND),.LEG_GUARD(LEG_GUARD),.DSP_PAIRS(int'((DSP_FORWARD_PAIRS!=0 && INVERSE==0 && stage>0) || (DSP_INVERSE_LAST!=0 && INVERSE==1 && stage==3))),.PRODUCT_PIPELINE(PHYSICAL)) fft_stage(
      .clk(clk),.reset(local_reset[stage+1]),.input_valid(valid[INPUT_NODE]),
      .input_real(data_real[INPUT_NODE]),.input_imag(data_imag[INPUT_NODE]),
      .output_valid(stage_valid),.output_real(stage_real),.output_imag(stage_imag),
      .fault(faults[OUTPUT_NODE]));
    // Forward stages 0..1 feed the next stage's multipliers directly: give
    // the placer a fabric register between them.
    PaperStreamingLanePipeline #(.LANES(LANES),.WIDTH(32),.STAGES(int'(PHYSICAL!=0 && INVERSE==0 && stage<2))) output_pipeline(
      .clk(clk),.reset(local_reset[stage+1]),.input_valid(stage_valid),.input_real(stage_real),.input_imag(stage_imag),
      .output_valid(valid[OUTPUT_NODE]),.output_real(data_real[OUTPUT_NODE]),.output_imag(data_imag[OUTPUT_NODE]));
  end
  if(INVERSE!=0)begin : inverse_untwist
    PaperStreamingTwist #(.LANES(LANES),.MODE(2),.NATIVE(NATIVE),.PRODUCT_PIPELINE(PHYSICAL)) untwist(
      .clk(clk),.reset(local_reset[7]),.input_valid(valid[FINAL_NODE]),.input_real(data_real[FINAL_NODE]),.input_imag(data_imag[FINAL_NODE]),
      .output_valid(output_valid),.output_real(output_real),.output_imag(output_imag),.fault(faults[8]));
  end else begin : forward_output
    assign output_valid=valid[FINAL_NODE];assign faults[8]=0;
    for(lane=0;lane<LANES;lane=lane+1)begin : wires
      assign output_real[lane]=data_real[FINAL_NODE][lane];assign output_imag[lane]=data_imag[FINAL_NODE][lane];
    end
  end endgenerate
  generate if(INVERSE==0 && DIGIT_REVERSED!=0)begin : direct_fft_input
    assign valid[1]=valid[0];assign faults[1]=0;
    for(lane=0;lane<LANES;lane=lane+1)begin : wires
      assign data_real[1][lane]=data_real[0][lane];assign data_imag[1][lane]=data_imag[0][lane];
    end
  end else begin : reordered_fft_input
  PaperStreamingFramePermute #(.LANES(LANES),.MODE(INVERSE!=0 && NATIVE!=0?4:1)) digit_reverse(
    .clk(clk),.reset(local_reset[6]),.input_valid(valid[0]),.input_real(data_real[0]),.input_imag(data_imag[0]),
    .output_valid(valid[1]),.output_real(data_real[1]),.output_imag(data_imag[1]),.fault(faults[1]));
  end endgenerate
  // The gather is a lane permutation with long wires on both sides.
  wire gather_input_valid,gather_output_valid;
  wire signed [31:0] gather_input_real [0:LANES-1],gather_input_imag [0:LANES-1];
  wire signed [31:0] gather_output_real [0:LANES-1],gather_output_imag [0:LANES-1];
  PaperStreamingLanePipeline #(.LANES(LANES),.WIDTH(32),.STAGES(PHYSICAL)) gather_input_pipeline(
    .clk(clk),.reset(local_reset[5]),.input_valid(valid[4]),.input_real(data_real[4]),.input_imag(data_imag[4]),
    .output_valid(gather_input_valid),.output_real(gather_input_real),.output_imag(gather_input_imag));
  PaperStreamingFramePermute #(.LANES(LANES),.MODE(2)) gather_last_stage(
    .clk(clk),.reset(local_reset[5]),.input_valid(gather_input_valid),.input_real(gather_input_real),.input_imag(gather_input_imag),
    .output_valid(gather_output_valid),.output_real(gather_output_real),.output_imag(gather_output_imag),.fault(faults[5]));
  PaperStreamingLanePipeline #(.LANES(LANES),.WIDTH(32),.STAGES(PHYSICAL)) gather_output_pipeline(
    .clk(clk),.reset(local_reset[5]),.input_valid(gather_output_valid),.input_real(gather_output_real),.input_imag(gather_output_imag),
    .output_valid(valid[5]),.output_real(data_real[5]),.output_imag(data_imag[5]));
  generate if(NATIVE==0)begin : reordered_output
    PaperStreamingFramePermute #(.LANES(LANES),.MODE(3)) natural_order(
    .clk(clk),.reset(local_reset[6]),.input_valid(valid[6]),.input_real(data_real[6]),.input_imag(data_imag[6]),
      .output_valid(valid[7]),.output_real(data_real[7]),.output_imag(data_imag[7]),.fault(faults[7]));
  end else begin : native_output
    assign valid[7]=0;assign faults[7]=0;
    for(lane=0;lane<LANES;lane=lane+1)begin : wires
      assign data_real[7][lane]=0;assign data_imag[7][lane]=0;
    end
  end endgenerate
  initial if((INVERSE==0 && LANES!=128) || (INVERSE==1 && LANES!=64))
    $fatal(1,"guarded32 transform lane count mismatch");
endmodule

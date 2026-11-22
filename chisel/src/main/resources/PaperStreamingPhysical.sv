// Physical-design helpers for the three-SLR U280 floorplan. Every module is a
// plain wire when disabled, so existing unit configurations are unchanged.

// Uniform two-register reset fan-out. Each copy is an independent chain so a
// floorplanned block owns its reset source; all copies release on one edge.
module PaperStreamingResetFanout #(
  parameter integer ENABLE=0, COPIES=1
)(
  input wire clk,reset,
  output wire [COPIES-1:0] local_reset
);
  generate if(ENABLE!=0)begin : registered
    // The first rank is kept distinct per copy; the second may be replicated
    // by synthesis so that no copy drives more than a local neighbourhood.
    (* keep="true", shreg_extract="no" *) reg [COPIES-1:0] first={COPIES{1'b1}};
    (* max_fanout=128, shreg_extract="no" *) reg [COPIES-1:0] second={COPIES{1'b1}};
    always @(posedge clk)begin first<={COPIES{reset}};second<=first;end
    assign local_reset=second;
  end else begin : direct
    assign local_reset={COPIES{reset}};
  end endgenerate
endmodule

// Sticky fault observations may be delayed: cut one level of the global OR.
module PaperStreamingFaultRegister #(
  parameter integer ENABLE=0
)(
  input wire clk,reset,fault_input,
  output wire fault
);
  generate if(ENABLE!=0)begin : registered
    reg observed;
    always @(posedge clk)if(reset)observed<=0;else observed<=fault_input;
    assign fault=observed;
  end else begin : direct
    assign fault=fault_input;
  end endgenerate
endmodule

// One register stage of a lane pipeline; a module so that each stage is a
// hierarchical cell the floorplan can assign to one side of an SLR boundary.
module PaperStreamingLaneStage #(
  parameter integer LANES=128, WIDTH=32
)(
  input wire clk,reset,input_valid,
  input wire signed [WIDTH-1:0] input_real [0:LANES-1],input_imag [0:LANES-1],
  output wire output_valid,
  output wire signed [WIDTH-1:0] output_real [0:LANES-1],output_imag [0:LANES-1]
);
  (* shreg_extract="no" *) reg valid_q;
  always @(posedge clk)if(reset)valid_q<=0;else valid_q<=input_valid;
  assign output_valid=valid_q;
  genvar lane;
  generate for(lane=0;lane<LANES;lane=lane+1)begin : lanes
    (* shreg_extract="no" *) reg signed [WIDTH-1:0] real_q,imag_q;
    always @(posedge clk)begin real_q<=input_real[lane];imag_q<=input_imag[lane];end
    assign output_real[lane]=real_q;assign output_imag[lane]=imag_q;
  end endgenerate
endmodule

// Fixed-latency complex lane pipeline for SLR crossings. Only valid resets.
module PaperStreamingLanePipeline #(
  parameter integer LANES=128, WIDTH=32, STAGES=0
)(
  input wire clk,reset,input_valid,
  input wire signed [WIDTH-1:0] input_real [0:LANES-1],input_imag [0:LANES-1],
  output wire output_valid,
  output wire signed [WIDTH-1:0] output_real [0:LANES-1],output_imag [0:LANES-1]
);
  wire [STAGES:0] valid;
  wire signed [WIDTH-1:0] data_real [0:STAGES][0:LANES-1] /* verilator split_var */;
  wire signed [WIDTH-1:0] data_imag [0:STAGES][0:LANES-1] /* verilator split_var */;
  assign valid[0]=input_valid;assign output_valid=valid[STAGES];
  genvar stage,lane;
  generate for(lane=0;lane<LANES;lane=lane+1)begin : boundary
    assign data_real[0][lane]=input_real[lane];assign data_imag[0][lane]=input_imag[lane];
    assign output_real[lane]=data_real[STAGES][lane];assign output_imag[lane]=data_imag[STAGES][lane];
  end
  for(stage=0;stage<STAGES;stage=stage+1)begin : stages
    PaperStreamingLaneStage #(.LANES(LANES),.WIDTH(WIDTH)) bank(
      .clk(clk),.reset(reset),.input_valid(valid[stage]),.input_real(data_real[stage]),.input_imag(data_imag[stage]),
      .output_valid(valid[stage+1]),.output_real(data_real[stage+1]),.output_imag(data_imag[stage+1]));
  end endgenerate
endmodule

// Lane pipeline whose registers must survive as written: used where a delay
// line deliberately duplicates another one so each copy can be placed apart.
module PaperStreamingKeptLanePipeline #(
  parameter integer LANES=128, WIDTH=32, STAGES=1
)(
  input wire clk,reset,input_valid,
  input wire signed [WIDTH-1:0] input_real [0:LANES-1],input_imag [0:LANES-1],
  output wire output_valid,
  output wire signed [WIDTH-1:0] output_real [0:LANES-1],output_imag [0:LANES-1]
);
  (* dont_touch="true" *) reg [STAGES-1:0] valid_q;
  always @(posedge clk)if(reset)valid_q<=0;else valid_q<=STAGES'({valid_q,input_valid});
  assign output_valid=valid_q[STAGES-1];
  genvar lane;
  generate for(lane=0;lane<LANES;lane=lane+1)begin : lanes
    (* dont_touch="true" *) reg signed [WIDTH-1:0] real_q [0:STAGES-1],imag_q [0:STAGES-1];
    integer index;
    always @(posedge clk)begin
      real_q[0]<=input_real[lane];imag_q[0]<=input_imag[lane];
      for(index=1;index<STAGES;index=index+1)begin real_q[index]<=real_q[index-1];imag_q[index]<=imag_q[index-1];end
    end
    assign output_real[lane]=real_q[STAGES-1];assign output_imag[lane]=imag_q[STAGES-1];
  end endgenerate
  initial if(STAGES<1)$fatal(1,"kept lane pipeline needs a stage");
endmodule

// Fully registered ready/valid slice: no combinational path in either direction.
module PaperStreamingStreamSlice #(
  parameter integer WIDTH=512
)(
  input wire clk,reset,input_valid,
  output wire input_ready,
  input wire [WIDTH-1:0] input_data,
  output reg output_valid,
  input wire output_ready,
  output reg [WIDTH-1:0] output_data
);
  reg skid_valid;
  reg [WIDTH-1:0] skid_data;
  assign input_ready=!skid_valid;
  wire take=input_valid && input_ready;
  always @(posedge clk)begin
    if(output_ready || !output_valid)begin
      if(skid_valid)output_data<=skid_data;else output_data<=input_data;
    end else if(take)skid_data<=input_data;
    if(reset)begin output_valid<=0;skid_valid<=0;end
    else if(output_ready || !output_valid)begin
      output_valid<=skid_valid || take;skid_valid<=0;
    end else if(take)skid_valid<=1;
  end
endmodule

// STAGES chained slices; STAGES=0 is a wire.
module PaperStreamingStreamPipeline #(
  parameter integer WIDTH=512, STAGES=0
)(
  input wire clk,reset,input_valid,
  output wire input_ready,
  input wire [WIDTH-1:0] input_data,
  output wire output_valid,
  input wire output_ready,
  output wire [WIDTH-1:0] output_data
);
  wire [STAGES:0] valid,ready;
  wire [WIDTH-1:0] data [0:STAGES];
  assign valid[0]=input_valid;assign data[0]=input_data;assign input_ready=ready[0];
  assign output_valid=valid[STAGES];assign output_data=data[STAGES];assign ready[STAGES]=output_ready;
  genvar stage;
  generate for(stage=0;stage<STAGES;stage=stage+1)begin : slices
    PaperStreamingStreamSlice #(.WIDTH(WIDTH)) slice(
      .clk(clk),.reset(reset),.input_valid(valid[stage]),.input_ready(ready[stage]),.input_data(data[stage]),
      .output_valid(valid[stage+1]),.output_ready(ready[stage+1]),.output_data(data[stage+1]));
  end endgenerate
endmodule

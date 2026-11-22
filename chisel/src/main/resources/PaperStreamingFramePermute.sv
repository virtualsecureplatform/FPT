// Fixed-schedule frame commutator. Two banks permit gathering a new frame
// while the previous frame drains; stored payload has no reset.
// MODE 1: base-4 digit reversal; 2: natural to stage-3 butterfly groups;
// MODE 3: stage-3 butterfly groups to natural.
// MODE 4: stage-3 butterfly groups to base-4 digit-reversed inputs.
module PaperStreamingFramePermute #(
  parameter integer LANES=128, MODE=1
)(
  input wire clk, reset, input_valid,
  input wire signed [31:0] input_real [0:LANES-1],input_imag [0:LANES-1],
  output reg output_valid,
  output reg signed [31:0] output_real [0:LANES-1],output_imag [0:LANES-1],
  output reg fault
);
  localparam integer BEATS=256/LANES,PHASE_BITS=$clog2(BEATS);
  localparam [PHASE_BITS-1:0] LAST_PHASE=PHASE_BITS'(BEATS-1);
  reg write_bank,read_bank,reading;
  reg [PHASE_BITS-1:0] write_phase,read_phase;
  function automatic integer source_point(input integer point);
    integer digit,index,reversed;
    begin
      if(MODE==2)source_point=point/4+(point%4)*64;
      else if(MODE==3)source_point=4*(point%64)+point/64;
      else begin
        index=point;reversed=0;
        for(digit=0;digit<4;digit=digit+1)begin reversed=4*reversed+index%4;index=index/4;end
        source_point=MODE==4?4*(reversed%64)+reversed/64:reversed;
      end
    end
  endfunction
  genvar lane,phase;
  generate for(lane=0;lane<LANES;lane=lane+1) begin : storage
    (* ram_style="registers" *) reg signed [31:0] real_words [0:2*BEATS-1],imag_words [0:2*BEATS-1];
    always @(posedge clk)if(input_valid && !reset)begin
      real_words[{write_bank,write_phase}]<=input_real[lane];
      imag_words[{write_bank,write_phase}]<=input_imag[lane];
    end
  end
  for(lane=0;lane<LANES;lane=lane+1) begin : outputs
    wire signed [31:0] choices_real [0:BEATS-1],choices_imag [0:BEATS-1];
    for(phase=0;phase<BEATS;phase=phase+1)begin : choices
      localparam integer POINT=source_point(phase*LANES+lane);
      localparam integer SOURCE_LANE=POINT%LANES,SOURCE_PHASE=POINT/LANES;
      assign choices_real[phase]=storage[SOURCE_LANE].real_words[(read_bank?BEATS:0)+SOURCE_PHASE];
      assign choices_imag[phase]=storage[SOURCE_LANE].imag_words[(read_bank?BEATS:0)+SOURCE_PHASE];
    end
    always @(posedge clk)if(reading && !reset)begin
      output_real[lane]<=choices_real[read_phase];output_imag[lane]<=choices_imag[read_phase];
    end
  end endgenerate
  always @(posedge clk) begin
    if(reset)begin
      write_bank<=0;read_bank<=0;reading<=0;write_phase<=0;read_phase<=0;output_valid<=0;fault<=0;
    end else begin
      output_valid<=reading;
      if(reading) begin
        if(read_phase==LAST_PHASE)reading<=0;
        else read_phase<=read_phase+1'b1;
      end
      if(input_valid)begin
        if(write_phase==LAST_PHASE)begin
          if(reading && read_phase!=LAST_PHASE)fault<=1;
          reading<=1;read_phase<=0;read_bank<=write_bank;
          write_phase<=0;write_bank<=!write_bank;
        end else write_phase<=write_phase+1'b1;
      end else if(write_phase!=0)fault<=1;
    end
  end
  initial if((LANES!=64 && LANES!=128) || MODE<1 || MODE>4)
    $fatal(1,"invalid streaming frame commutator geometry");
endmodule

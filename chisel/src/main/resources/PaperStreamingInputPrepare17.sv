// Public Torus32 input preparation for the opt-in 17-bit-state profile.
// Load one 512-word LUT and twelve ordinary 587-word TLWEs. Mask words emit
// ten-bit exponents; the body word selects LUT rotation by -round(b*1024).
// A single BRAM read per coefficient builds initial bodies ahead of compute,
// avoiding a second wide rotation datapath. Lookup loads use modular RNE17.
module PaperStreamingInputPrepare17 #(parameter integer STEPS=586)(
  input wire clk,reset,start,
  output wire start_ready,busy,
  output reg done,
  input wire lookup_valid,
  output wire lookup_ready,
  input wire [8:0] lookup_index,
  input wire [31:0] lookup_value,
  input wire tlwe_valid,
  output wire tlwe_ready,
  input wire [3:0] tlwe_context,
  input wire [9:0] tlwe_word,
  input wire [31:0] tlwe_value,
  output wire exponent_valid,
  input wire exponent_ready,
  output wire [3:0] exponent_context,
  output wire [9:0] exponent_step,exponent_value,
  output reg body_valid,
  input wire body_ready,
  output reg [3:0] body_context,
  output reg [8:0] body_point,
  output reg [16:0] body_code,
  output reg body_last,
  output reg fault
);
  initial if(STEPS<1 || STEPS>586)$fatal(1,"invalid input preparation geometry");
  (* ram_style="block" *) reg [16:0] lookup_memory [0:511];
  reg [9:0] body_offset [0:11];
  reg lookup_complete,inputs_complete,processing,generating,read_pending;
  reg [8:0] expected_lookup,issue_point,read_point;
  reg [3:0] expected_context,issue_context,read_context;
  reg [9:0] expected_word;
  reg [16:0] read_data;
  reg read_negative,read_last;
  wire lookup_legal=lookup_index==expected_lookup;
  wire tlwe_legal=tlwe_context==expected_context && tlwe_word==expected_word;
  wire [32:0] biased_input={1'b0,tlwe_value}+33'd2097152;
  wire [9:0] switched=biased_input[31:22];
  wire increment=lookup_value[14] && ((|lookup_value[13:0]) || lookup_value[15]);
  wire [16:0] rounded_lookup=lookup_value[31:15]+{16'b0,increment};
  wire [9:0] source={1'b0,issue_point}+body_offset[issue_context];
  wire advance=!body_valid || body_ready;
  assign busy=processing;
  assign start_ready=lookup_complete && inputs_complete && !processing && !fault && !reset;
  assign lookup_ready=!processing && !start && !fault && !reset;
  assign tlwe_ready=!processing && !start && !inputs_complete && (tlwe_word==10'(STEPS) || exponent_ready) && !fault && !reset;
  assign exponent_valid=tlwe_valid && tlwe_ready && tlwe_legal && tlwe_word<10'(STEPS);
  assign exponent_context=tlwe_context;assign exponent_step=tlwe_word;assign exponent_value=switched;
  always @(posedge clk)begin
    if(lookup_valid && lookup_ready && lookup_legal)lookup_memory[lookup_index]<=rounded_lookup;
    if(advance && generating)read_data<=lookup_memory[source[8:0]];
    if(reset)begin
      lookup_complete<=0;inputs_complete<=0;processing<=0;generating<=0;read_pending<=0;
      expected_lookup<=0;expected_context<=0;expected_word<=0;issue_context<=0;issue_point<=0;
      body_valid<=0;done<=0;fault<=0;
    end else begin
      done<=0;
      if(lookup_valid && lookup_ready)begin
        if(!lookup_legal)fault<=1;
        else if(lookup_index==511)begin lookup_complete<=1;expected_lookup<=0;end
        else begin lookup_complete<=0;expected_lookup<=expected_lookup+1'b1;end
      end
      if(tlwe_valid && tlwe_ready)begin
        if(!tlwe_legal)fault<=1;
        else if(tlwe_word==10'(STEPS))begin
          body_offset[tlwe_context]<=switched;expected_word<=0;
          if(tlwe_context==11)begin inputs_complete<=1;expected_context<=0;end
          else expected_context<=expected_context+1'b1;
        end else expected_word<=expected_word+1'b1;
      end
      if(advance)begin
        body_valid<=read_pending;read_pending<=generating;
        if(read_pending)begin
          body_code<=read_negative?17'd0-read_data:read_data;
          body_context<=read_context;body_point<=read_point;body_last<=read_last;
        end
        if(generating)begin
          read_context<=issue_context;read_point<=issue_point;read_negative<=source[9];
          read_last<=issue_context==11 && issue_point==511;
          if(issue_point==511)begin
            issue_point<=0;
            if(issue_context==11)generating<=0;else issue_context<=issue_context+1'b1;
          end else issue_point<=issue_point+1'b1;
        end
      end
      if(body_valid && body_ready && body_last)begin done<=1;processing<=0;end
      if(start)begin
        if(!start_ready)fault<=1;
        else begin processing<=1;generating<=1;issue_context<=0;issue_point<=0;inputs_complete<=0;end
      end
    end
  end
endmodule

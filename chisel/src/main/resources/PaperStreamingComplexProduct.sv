// Fixed-latency arithmetic primitive for the opt-in paper streaming backend.
// Three registered stages; bubbles travel in valid, payload is free-running.
module PaperStreamingComplexProduct #(
  parameter integer A_WIDTH = 29,
  parameter integer B_WIDTH = 26,
  parameter integer P_WIDTH = A_WIDTH + B_WIDTH + 2
)(
  input wire clk, reset, input_valid,
  input wire signed [A_WIDTH-1:0] ar, ai,
  input wire signed [B_WIDTH-1:0] br, bi,
  output reg output_valid,
  output reg signed [P_WIDTH-1:0] pr, pi
);
  localparam integer M_WIDTH = A_WIDTH + B_WIDTH + 1;
  reg valid1, valid2;
  reg signed [A_WIDTH:0] a_difference;
  reg signed [A_WIDTH-1:0] a_real, a_imag;
  reg signed [B_WIDTH:0] b_difference, b_sum;
  reg signed [B_WIDTH-1:0] b_real;
  (* use_dsp = "yes" *) reg signed [M_WIDTH-1:0] z, real_term, imag_term;

  always @(posedge clk) begin
    if (reset) begin
      valid1 <= 1'b0;
      valid2 <= 1'b0;
      output_valid <= 1'b0;
    end else begin
      valid1 <= input_valid;
      valid2 <= valid1;
      output_valid <= valid2;
    end
    a_difference <= {ar[A_WIDTH-1],ar} - {ai[A_WIDTH-1],ai};
    a_real <= ar;
    a_imag <= ai;
    b_difference <= {br[B_WIDTH-1],br} - {bi[B_WIDTH-1],bi};
    b_sum <= {br[B_WIDTH-1],br} + {bi[B_WIDTH-1],bi};
    b_real <= br;
    z <= a_difference * b_real;
    real_term <= a_imag * b_difference;
    imag_term <= a_real * b_sum;
    pr <= {{(P_WIDTH-M_WIDTH){z[M_WIDTH-1]}},z} +
          {{(P_WIDTH-M_WIDTH){real_term[M_WIDTH-1]}},real_term};
    pi <= {{(P_WIDTH-M_WIDTH){imag_term[M_WIDTH-1]}},imag_term} -
          {{(P_WIDTH-M_WIDTH){z[M_WIDTH-1]}},z};
  end
  initial begin
    if (A_WIDTH < 2 || B_WIDTH < 2 || P_WIDTH < M_WIDTH+1)
      $fatal(1,"invalid streaming product widths");
  end
endmodule

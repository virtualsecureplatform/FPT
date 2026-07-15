`default_nettype none

// Lane-parallel pointwise multiply-accumulate for the frequency-domain
// External Product. Every lane has the same one-cycle valid pipeline as
// fpt_complex_mac; valid_out is shared because all lanes advance together.
module fpt_complex_mac_wide #(
    parameter int LANES = 4,
    parameter int A_WIDTH = 38,
    parameter int A_FRAC = 20,
    parameter int B_WIDTH = 32,
    parameter int B_FRAC = 24,
    parameter int ACC_WIDTH = 41,
    parameter int ACC_FRAC = 14
) (
    input  logic clk,
    input  logic rst_n,
    input  logic valid_in,
    input  logic signed [LANES-1:0][A_WIDTH-1:0] a_real,
    input  logic signed [LANES-1:0][A_WIDTH-1:0] a_imag,
    input  logic signed [LANES-1:0][B_WIDTH-1:0] b_real,
    input  logic signed [LANES-1:0][B_WIDTH-1:0] b_imag,
    input  logic signed [LANES-1:0][ACC_WIDTH-1:0] accumulator_real,
    input  logic signed [LANES-1:0][ACC_WIDTH-1:0] accumulator_imag,
    output logic valid_out,
    output logic signed [LANES-1:0][ACC_WIDTH-1:0] result_real,
    output logic signed [LANES-1:0][ACC_WIDTH-1:0] result_imag
);
    logic [LANES-1:0] lane_valid;
    assign valid_out = &lane_valid;

    genvar lane;
    generate
        for (lane = 0; lane < LANES; lane = lane + 1) begin : mac_lanes
            fpt_complex_mac #(
                .A_WIDTH(A_WIDTH), .A_FRAC(A_FRAC),
                .B_WIDTH(B_WIDTH), .B_FRAC(B_FRAC),
                .ACC_WIDTH(ACC_WIDTH), .ACC_FRAC(ACC_FRAC)
            ) mac (
                .clk,
                .rst_n,
                .valid_in,
                .a_real(a_real[lane]),
                .a_imag(a_imag[lane]),
                .b_real(b_real[lane]),
                .b_imag(b_imag[lane]),
                .accumulator_real(accumulator_real[lane]),
                .accumulator_imag(accumulator_imag[lane]),
                .valid_out(lane_valid[lane]),
                .result_real(result_real[lane]),
                .result_imag(result_imag[lane])
            );
        end
    endgenerate
endmodule

`default_nettype wire

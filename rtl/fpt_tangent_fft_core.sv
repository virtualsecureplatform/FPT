`default_nettype none

// Forward tangent FFT wrapper for a 2*POINTS coefficient polynomial. Each
// input beat folds coefficient i and i+POINTS into a complex value, applies
// psi^i, and feeds the cyclic POINTS-point FFT core.
module fpt_tangent_fft_core #(
    parameter int POINTS = 16,
    parameter int DATA_WIDTH = 30,
    parameter int TWIDDLE_WIDTH = 26,
    parameter int TWIDDLE_FRAC = 24,
    parameter logic [$clog2(POINTS)-1:0] SCALE_MASK = '0
) (
    input  logic clk,
    input  logic rst_n,
    input  logic pair_valid,
    output logic pair_ready,
    input  logic signed [DATA_WIDTH-1:0] coefficient_low,
    input  logic signed [DATA_WIDTH-1:0] coefficient_high,
    output logic [$clog2(POINTS)-1:0] twist_index,
    input  logic signed [TWIDDLE_WIDTH-1:0] twist_c,
    input  logic signed [TWIDDLE_WIDTH-1:0] twist_c_minus_d,
    input  logic signed [TWIDDLE_WIDTH-1:0] twist_c_plus_d,
    output logic [$clog2(POINTS)-2:0] fft_twiddle_index,
    input  logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c,
    input  logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c_minus_d,
    input  logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c_plus_d,
    output logic output_valid,
    input  logic output_ready,
    output logic signed [DATA_WIDTH-1:0] output_real,
    output logic signed [DATA_WIDTH-1:0] output_imag,
    output logic busy,
    output logic done
);
    localparam int INDEX_WIDTH = $clog2(POINTS);
    localparam logic [INDEX_WIDTH-1:0] LAST_INDEX =
        INDEX_WIDTH'(POINTS - 1);

    logic [INDEX_WIDTH-1:0] pair_index;
    logic signed [DATA_WIDTH-1:0] twisted_real;
    logic signed [DATA_WIDTH-1:0] twisted_imag;
    logic fft_input_ready;

    assign twist_index = pair_index;
    assign pair_ready = fft_input_ready;

    fpt_gauss_mul #(
        .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
        .TWIDDLE_FRAC(TWIDDLE_FRAC)
    ) tangent_twist (
        .a_real(coefficient_low),
        .a_imag(coefficient_high),
        .twiddle_c(twist_c),
        .twiddle_c_minus_d(twist_c_minus_d),
        .twiddle_c_plus_d(twist_c_plus_d),
        .result_real(twisted_real),
        .result_imag(twisted_imag)
    );

    fpt_fft_core #(
        .POINTS(POINTS),
        .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
        .TWIDDLE_FRAC(TWIDDLE_FRAC),
        .SCALE_MASK(SCALE_MASK)
    ) cyclic_fft (
        .clk,
        .rst_n,
        .input_valid(pair_valid),
        .input_ready(fft_input_ready),
        .input_real(twisted_real),
        .input_imag(twisted_imag),
        .twiddle_index(fft_twiddle_index),
        .twiddle_c(fft_twiddle_c),
        .twiddle_c_minus_d(fft_twiddle_c_minus_d),
        .twiddle_c_plus_d(fft_twiddle_c_plus_d),
        .output_valid,
        .output_ready,
        .output_real,
        .output_imag,
        .busy,
        .done
    );

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            pair_index <= '0;
        end else if (pair_valid && pair_ready) begin
            if (pair_index == LAST_INDEX)
                pair_index <= '0;
            else
                pair_index <= pair_index + 1'b1;
        end
    end
endmodule

`default_nettype wire


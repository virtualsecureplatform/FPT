`default_nettype none

// Inverse tangent FFT wrapper. The cyclic core is driven with inverse FFT
// twiddles supplied by the caller. Natural-order complex outputs are untwisted
// and unpacked into coefficient i and i+POINTS. NORMALIZE_SHIFT is normally
// log2(POINTS), reduced when an inverse-stage scaling schedule needs explicit
// compensation.
module fpt_tangent_ifft_core #(
    parameter int POINTS = 16,
    parameter int DATA_WIDTH = 30,
    parameter int TWIDDLE_WIDTH = 26,
    parameter int TWIDDLE_FRAC = 24,
    parameter int NORMALIZE_SHIFT = $clog2(POINTS),
    parameter logic [$clog2(POINTS)-1:0] SCALE_MASK = '0
) (
    input  logic clk,
    input  logic rst_n,
    input  logic input_valid,
    output logic input_ready,
    input  logic signed [DATA_WIDTH-1:0] input_real,
    input  logic signed [DATA_WIDTH-1:0] input_imag,
    output logic [$clog2(POINTS)-2:0] fft_twiddle_index,
    input  logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c,
    input  logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c_minus_d,
    input  logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c_plus_d,
    output logic [$clog2(POINTS)-1:0] untwist_index,
    input  logic signed [TWIDDLE_WIDTH-1:0] untwist_c,
    input  logic signed [TWIDDLE_WIDTH-1:0] untwist_c_minus_d,
    input  logic signed [TWIDDLE_WIDTH-1:0] untwist_c_plus_d,
    output logic output_valid,
    input  logic output_ready,
    output logic signed [DATA_WIDTH-1:0] coefficient_low,
    output logic signed [DATA_WIDTH-1:0] coefficient_high,
    output logic busy,
    output logic done
);
    localparam int INDEX_WIDTH = $clog2(POINTS);
    localparam logic [INDEX_WIDTH-1:0] LAST_INDEX =
        INDEX_WIDTH'(POINTS - 1);

    logic cyclic_output_valid;
    logic signed [DATA_WIDTH-1:0] cyclic_output_real;
    logic signed [DATA_WIDTH-1:0] cyclic_output_imag;
    logic signed [DATA_WIDTH-1:0] untwisted_real;
    logic signed [DATA_WIDTH-1:0] untwisted_imag;
    logic signed [DATA_WIDTH-1:0] normalized_real;
    logic signed [DATA_WIDTH-1:0] normalized_imag;
    logic [INDEX_WIDTH-1:0] output_index;

    initial begin
        if (NORMALIZE_SHIFT < 0 || NORMALIZE_SHIFT >= DATA_WIDTH)
            $error("invalid fpt_tangent_ifft_core normalization shift");
    end

    assign untwist_index = output_index;
    assign output_valid = cyclic_output_valid;
    assign normalized_real = untwisted_real >>> NORMALIZE_SHIFT;
    assign normalized_imag = untwisted_imag >>> NORMALIZE_SHIFT;
    assign coefficient_low = normalized_real;
    assign coefficient_high = normalized_imag;

    fpt_fft_core #(
        .POINTS(POINTS),
        .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
        .TWIDDLE_FRAC(TWIDDLE_FRAC),
        .SCALE_MASK(SCALE_MASK)
    ) cyclic_ifft (
        .clk,
        .rst_n,
        .input_valid,
        .input_ready,
        .input_real,
        .input_imag,
        .twiddle_index(fft_twiddle_index),
        .twiddle_c(fft_twiddle_c),
        .twiddle_c_minus_d(fft_twiddle_c_minus_d),
        .twiddle_c_plus_d(fft_twiddle_c_plus_d),
        .output_valid(cyclic_output_valid),
        .output_ready,
        .output_real(cyclic_output_real),
        .output_imag(cyclic_output_imag),
        .busy,
        .done
    );

    fpt_gauss_mul #(
        .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
        .TWIDDLE_FRAC(TWIDDLE_FRAC)
    ) tangent_untwist (
        .a_real(cyclic_output_real),
        .a_imag(cyclic_output_imag),
        .twiddle_c(untwist_c),
        .twiddle_c_minus_d(untwist_c_minus_d),
        .twiddle_c_plus_d(untwist_c_plus_d),
        .result_real(untwisted_real),
        .result_imag(untwisted_imag)
    );

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            output_index <= '0;
        end else if (cyclic_output_valid && output_ready) begin
            if (output_index == LAST_INDEX)
                output_index <= '0;
            else
                output_index <= output_index + 1'b1;
        end
    end
endmodule

`default_nettype wire


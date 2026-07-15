`default_nettype none

// LANES-wide inverse tangent FFT. The caller supplies inverse cyclic twiddles;
// each natural-order output beat is untwisted, normalized, and unpacked into
// LANES coefficient pairs.
module fpt_tangent_ifft_wide_core #(
    parameter int POINTS = 16,
    parameter int LANES = 4,
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
    input  logic signed [LANES-1:0][DATA_WIDTH-1:0] input_real,
    input  logic signed [LANES-1:0][DATA_WIDTH-1:0] input_imag,
    output logic [LANES-1:0][$clog2(POINTS)-2:0] fft_twiddle_index,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] fft_twiddle_c,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0]
        fft_twiddle_c_minus_d,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0]
        fft_twiddle_c_plus_d,
    output logic [LANES-1:0][$clog2(POINTS)-1:0] untwist_index,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] untwist_c,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] untwist_c_minus_d,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] untwist_c_plus_d,
    output logic output_valid,
    input  logic output_ready,
    output logic signed [LANES-1:0][DATA_WIDTH-1:0] coefficient_low,
    output logic signed [LANES-1:0][DATA_WIDTH-1:0] coefficient_high,
    output logic busy,
    output logic done
);
    localparam int INDEX_WIDTH = $clog2(POINTS);
    localparam int LOG_LANES = $clog2(LANES);
    localparam int OUTPUT_BEATS = POINTS / LANES;
    localparam int OUTPUT_BEAT_WIDTH =
        OUTPUT_BEATS > 1 ? $clog2(OUTPUT_BEATS) : 1;
    localparam logic [OUTPUT_BEAT_WIDTH-1:0] LAST_OUTPUT_BEAT =
        OUTPUT_BEAT_WIDTH'(OUTPUT_BEATS - 1);

    logic cyclic_output_valid;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] cyclic_output_real;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] cyclic_output_imag;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] untwisted_real;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] untwisted_imag;
    logic [OUTPUT_BEAT_WIDTH-1:0] output_beat;

    initial begin
        if (POINTS % LANES != 0)
            $error("fpt_tangent_ifft_wide_core LANES must divide POINTS");
        if (NORMALIZE_SHIFT < 0 || NORMALIZE_SHIFT >= DATA_WIDTH)
            $error("invalid fpt_tangent_ifft_wide_core normalization shift");
    end

    integer index_lane;
    always @* begin
        for (index_lane = 0; index_lane < LANES;
             index_lane = index_lane + 1) begin
            untwist_index[index_lane] =
                (INDEX_WIDTH'(output_beat) << LOG_LANES) +
                INDEX_WIDTH'(index_lane);
            coefficient_low[index_lane] =
                untwisted_real[index_lane] >>> NORMALIZE_SHIFT;
            coefficient_high[index_lane] =
                untwisted_imag[index_lane] >>> NORMALIZE_SHIFT;
        end
        output_valid = cyclic_output_valid;
    end

    fpt_fft_wide_core #(
        .POINTS(POINTS),
        .LANES(LANES),
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

    genvar untwist_lane;
    generate
        for (untwist_lane = 0; untwist_lane < LANES;
             untwist_lane = untwist_lane + 1) begin : untwists
            fpt_gauss_mul #(
                .DATA_WIDTH(DATA_WIDTH),
                .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
                .TWIDDLE_FRAC(TWIDDLE_FRAC)
            ) tangent_untwist (
                .a_real(cyclic_output_real[untwist_lane]),
                .a_imag(cyclic_output_imag[untwist_lane]),
                .twiddle_c(untwist_c[untwist_lane]),
                .twiddle_c_minus_d(untwist_c_minus_d[untwist_lane]),
                .twiddle_c_plus_d(untwist_c_plus_d[untwist_lane]),
                .result_real(untwisted_real[untwist_lane]),
                .result_imag(untwisted_imag[untwist_lane])
            );
        end
    endgenerate

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            output_beat <= '0;
        end else if (cyclic_output_valid && output_ready) begin
            if (output_beat == LAST_OUTPUT_BEAT)
                output_beat <= '0;
            else
                output_beat <= output_beat + 1'b1;
        end
    end
endmodule

`default_nettype wire

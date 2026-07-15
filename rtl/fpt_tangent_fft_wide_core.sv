`default_nettype none

// LANES-wide forward tangent FFT wrapper for a 2*POINTS coefficient
// polynomial. Each accepted beat folds LANES low/high coefficient pairs,
// applies the corresponding negacyclic twists, and feeds the wide cyclic FFT.
module fpt_tangent_fft_wide_core #(
    parameter int POINTS = 16,
    parameter int LANES = 4,
    parameter int DATA_WIDTH = 30,
    parameter int TWIDDLE_WIDTH = 26,
    parameter int TWIDDLE_FRAC = 24,
    parameter logic [$clog2(POINTS)-1:0] SCALE_MASK = '0
) (
    input  logic clk,
    input  logic rst_n,
    input  logic pair_valid,
    output logic pair_ready,
    input  logic signed [LANES-1:0][DATA_WIDTH-1:0] coefficient_low,
    input  logic signed [LANES-1:0][DATA_WIDTH-1:0] coefficient_high,
    output logic [LANES-1:0][$clog2(POINTS)-1:0] twist_index,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] twist_c,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] twist_c_minus_d,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] twist_c_plus_d,
    output logic [LANES-1:0][$clog2(POINTS)-2:0] fft_twiddle_index,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] fft_twiddle_c,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0]
        fft_twiddle_c_minus_d,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0]
        fft_twiddle_c_plus_d,
    output logic output_valid,
    input  logic output_ready,
    output logic signed [LANES-1:0][DATA_WIDTH-1:0] output_real,
    output logic signed [LANES-1:0][DATA_WIDTH-1:0] output_imag,
    output logic busy,
    output logic done
);
    localparam int INDEX_WIDTH = $clog2(POINTS);
    localparam int LOG_LANES = $clog2(LANES);
    localparam int PAIR_BEATS = POINTS / LANES;
    localparam int PAIR_BEAT_WIDTH = PAIR_BEATS > 1 ? $clog2(PAIR_BEATS) : 1;
    localparam logic [PAIR_BEAT_WIDTH-1:0] LAST_PAIR_BEAT =
        PAIR_BEAT_WIDTH'(PAIR_BEATS - 1);

    logic [PAIR_BEAT_WIDTH-1:0] pair_beat;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] twisted_real;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] twisted_imag;
    logic fft_input_ready;

    initial begin
        if (POINTS % LANES != 0)
            $error("fpt_tangent_fft_wide_core LANES must divide POINTS");
    end

    integer index_lane;
    always @* begin
        for (index_lane = 0; index_lane < LANES;
            index_lane = index_lane + 1)
            twist_index[index_lane] =
                (INDEX_WIDTH'(pair_beat) << LOG_LANES) +
                INDEX_WIDTH'(index_lane);
        pair_ready = fft_input_ready;
    end

    genvar twist_lane;
    generate
        for (twist_lane = 0; twist_lane < LANES;
             twist_lane = twist_lane + 1) begin : twists
            fpt_gauss_mul #(
                .DATA_WIDTH(DATA_WIDTH),
                .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
                .TWIDDLE_FRAC(TWIDDLE_FRAC)
            ) tangent_twist (
                .a_real(coefficient_low[twist_lane]),
                .a_imag(coefficient_high[twist_lane]),
                .twiddle_c(twist_c[twist_lane]),
                .twiddle_c_minus_d(twist_c_minus_d[twist_lane]),
                .twiddle_c_plus_d(twist_c_plus_d[twist_lane]),
                .result_real(twisted_real[twist_lane]),
                .result_imag(twisted_imag[twist_lane])
            );
        end
    endgenerate

    fpt_fft_wide_core #(
        .POINTS(POINTS),
        .LANES(LANES),
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
            pair_beat <= '0;
        end else if (pair_valid && pair_ready) begin
            if (pair_beat == LAST_PAIR_BEAT)
                pair_beat <= '0;
            else
                pair_beat <= pair_beat + 1'b1;
        end
    end
endmodule

`default_nettype wire

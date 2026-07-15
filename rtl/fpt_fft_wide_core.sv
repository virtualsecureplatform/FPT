`default_nettype none

// Multi-lane iterative cyclic FFT reference. LANES butterflies are evaluated
// per compute cycle, while LANES complex samples are loaded and emitted per
// cycle. This is a throughput-scalable bridge between the one-butterfly model
// and a future fully overlapped SGen/radix-2^4 pipeline.
//
// The memory is intentionally described as a register array with 2*LANES
// writes. It makes arithmetic and scheduling explicit and synthesisable, but
// is not expected to infer BRAM for large lane counts.
module fpt_fft_wide_core #(
    parameter int POINTS = 16,
    parameter int LANES = 4,
    parameter int DATA_WIDTH = 30,
    parameter int TWIDDLE_WIDTH = 26,
    parameter int TWIDDLE_FRAC = 24,
    parameter logic [$clog2(POINTS)-1:0] SCALE_MASK = '0
) (
    input  logic clk,
    input  logic rst_n,
    input  logic input_valid,
    output logic input_ready,
    input  logic signed [LANES-1:0][DATA_WIDTH-1:0] input_real,
    input  logic signed [LANES-1:0][DATA_WIDTH-1:0] input_imag,
    output logic [LANES-1:0][$clog2(POINTS)-2:0] twiddle_index,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] twiddle_c,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0]
        twiddle_c_minus_d,
    input  logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0]
        twiddle_c_plus_d,
    output logic output_valid,
    input  logic output_ready,
    output logic signed [LANES-1:0][DATA_WIDTH-1:0] output_real,
    output logic signed [LANES-1:0][DATA_WIDTH-1:0] output_imag,
    output logic busy,
    output logic done
);
    localparam int LOG_POINTS = $clog2(POINTS);
    localparam int INDEX_WIDTH = LOG_POINTS;
    localparam int TWIDDLE_INDEX_WIDTH = LOG_POINTS - 1;
    localparam int LOG_LANES = $clog2(LANES);
    localparam int STAGE_WIDTH = $clog2(LOG_POINTS);
    localparam int LOAD_BEATS = POINTS / LANES;
    localparam int LOAD_BEAT_WIDTH = LOAD_BEATS > 1 ? $clog2(LOAD_BEATS) : 1;
    localparam int BUTTERFLIES_PER_STAGE = POINTS / 2;
    localparam int COMPUTE_CYCLES = BUTTERFLIES_PER_STAGE / LANES;
    localparam int COMPUTE_CYCLE_WIDTH =
        COMPUTE_CYCLES > 1 ? $clog2(COMPUTE_CYCLES) : 1;
    localparam logic [STAGE_WIDTH-1:0] LAST_STAGE =
        STAGE_WIDTH'(LOG_POINTS - 1);
    localparam logic [LOAD_BEAT_WIDTH-1:0] LAST_LOAD_BEAT =
        LOAD_BEAT_WIDTH'(LOAD_BEATS - 1);
    localparam logic [COMPUTE_CYCLE_WIDTH-1:0] LAST_COMPUTE_CYCLE =
        COMPUTE_CYCLE_WIDTH'(COMPUTE_CYCLES - 1);

    typedef enum logic [1:0] {LOAD, COMPUTE, OUTPUT_STREAM} state_t;
    state_t state;

    logic signed [DATA_WIDTH-1:0] real_memory [0:POINTS-1];
    logic signed [DATA_WIDTH-1:0] imag_memory [0:POINTS-1];
    logic [LOAD_BEAT_WIDTH-1:0] load_beat;
    logic [LOAD_BEAT_WIDTH-1:0] output_beat;
    logic [STAGE_WIDTH-1:0] stage_index;
    logic [COMPUTE_CYCLE_WIDTH-1:0] compute_cycle;
    logic [INDEX_WIDTH-1:0] half_size;

    logic [INDEX_WIDTH-1:0] natural_input_address [0:LANES-1];
    logic [INDEX_WIDTH-1:0] natural_output_address [0:LANES-1];
    logic [INDEX_WIDTH-1:0] butterfly_slot [0:LANES-1];
    logic [INDEX_WIDTH-1:0] butterfly_index [0:LANES-1];
    logic [INDEX_WIDTH-1:0] even_address [0:LANES-1];
    logic [INDEX_WIDTH-1:0] odd_address [0:LANES-1];
    logic signed [DATA_WIDTH-1:0] twiddled_real [0:LANES-1];
    logic signed [DATA_WIDTH-1:0] twiddled_imag [0:LANES-1];
    logic signed [DATA_WIDTH:0] upper_real_wide [0:LANES-1];
    logic signed [DATA_WIDTH:0] upper_imag_wide [0:LANES-1];
    logic signed [DATA_WIDTH:0] lower_real_wide [0:LANES-1];
    logic signed [DATA_WIDTH:0] lower_imag_wide [0:LANES-1];
    logic signed [DATA_WIDTH:0] selected_upper_real [0:LANES-1];
    logic signed [DATA_WIDTH:0] selected_upper_imag [0:LANES-1];
    logic signed [DATA_WIDTH:0] selected_lower_real [0:LANES-1];
    logic signed [DATA_WIDTH:0] selected_lower_imag [0:LANES-1];

    function automatic [INDEX_WIDTH-1:0] bit_reverse(
        input logic [INDEX_WIDTH-1:0] value);
        integer bit_index;
        begin
            for (bit_index = 0; bit_index < INDEX_WIDTH;
                 bit_index = bit_index + 1)
                bit_reverse[bit_index] = value[INDEX_WIDTH - 1 - bit_index];
        end
    endfunction

    initial begin
        if (POINTS < 4 || (POINTS & (POINTS - 1)) != 0)
            $error("fpt_fft_wide_core POINTS must be a power of two >= 4");
        if (LANES < 1 || (LANES & (LANES - 1)) != 0 ||
            LANES > POINTS / 2 || POINTS % LANES != 0)
            $error("fpt_fft_wide_core LANES must be a power of two dividing POINTS/2");
    end

    genvar multiplier_lane;
    generate
        for (multiplier_lane = 0; multiplier_lane < LANES;
             multiplier_lane = multiplier_lane + 1) begin : multipliers
            fpt_gauss_mul #(
                .DATA_WIDTH(DATA_WIDTH),
                .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
                .TWIDDLE_FRAC(TWIDDLE_FRAC)
            ) twiddle_multiplier (
                .a_real(real_memory[odd_address[multiplier_lane]]),
                .a_imag(imag_memory[odd_address[multiplier_lane]]),
                .twiddle_c(twiddle_c[multiplier_lane]),
                .twiddle_c_minus_d(twiddle_c_minus_d[multiplier_lane]),
                .twiddle_c_plus_d(twiddle_c_plus_d[multiplier_lane]),
                .result_real(twiddled_real[multiplier_lane]),
                .result_imag(twiddled_imag[multiplier_lane])
            );
        end
    endgenerate

    integer lane;
    always @* begin
        half_size = INDEX_WIDTH'(1) << stage_index;
        for (lane = 0; lane < LANES; lane = lane + 1) begin
            natural_input_address[lane] =
                (INDEX_WIDTH'(load_beat) << LOG_LANES) +
                INDEX_WIDTH'(lane);
            natural_output_address[lane] =
                (INDEX_WIDTH'(output_beat) << LOG_LANES) +
                INDEX_WIDTH'(lane);
            butterfly_slot[lane] =
                (INDEX_WIDTH'(compute_cycle) << LOG_LANES) +
                INDEX_WIDTH'(lane);
            butterfly_index[lane] = butterfly_slot[lane] & (half_size - 1'b1);
            even_address[lane] =
                ((butterfly_slot[lane] >> stage_index) <<
                 (stage_index + 1)) + butterfly_index[lane];
            odd_address[lane] = even_address[lane] + half_size;
            twiddle_index[lane] = TWIDDLE_INDEX_WIDTH'(
                butterfly_index[lane] <<
                (INDEX_WIDTH'(LOG_POINTS) - INDEX_WIDTH'(stage_index) - 1'b1));
        end
    end

    always @* begin
        for (lane = 0; lane < LANES; lane = lane + 1) begin
            upper_real_wide[lane] =
                {real_memory[even_address[lane]][DATA_WIDTH-1],
                 real_memory[even_address[lane]]} +
                {twiddled_real[lane][DATA_WIDTH-1], twiddled_real[lane]};
            upper_imag_wide[lane] =
                {imag_memory[even_address[lane]][DATA_WIDTH-1],
                 imag_memory[even_address[lane]]} +
                {twiddled_imag[lane][DATA_WIDTH-1], twiddled_imag[lane]};
            lower_real_wide[lane] =
                {real_memory[even_address[lane]][DATA_WIDTH-1],
                 real_memory[even_address[lane]]} -
                {twiddled_real[lane][DATA_WIDTH-1], twiddled_real[lane]};
            lower_imag_wide[lane] =
                {imag_memory[even_address[lane]][DATA_WIDTH-1],
                 imag_memory[even_address[lane]]} -
                {twiddled_imag[lane][DATA_WIDTH-1], twiddled_imag[lane]};

            if (SCALE_MASK[stage_index]) begin
                selected_upper_real[lane] = upper_real_wide[lane] >>> 1;
                selected_upper_imag[lane] = upper_imag_wide[lane] >>> 1;
                selected_lower_real[lane] = lower_real_wide[lane] >>> 1;
                selected_lower_imag[lane] = lower_imag_wide[lane] >>> 1;
            end else begin
                selected_upper_real[lane] = upper_real_wide[lane];
                selected_upper_imag[lane] = upper_imag_wide[lane];
                selected_lower_real[lane] = lower_real_wide[lane];
                selected_lower_imag[lane] = lower_imag_wide[lane];
            end

            output_real[lane] = real_memory[natural_output_address[lane]];
            output_imag[lane] = imag_memory[natural_output_address[lane]];
        end
    end

    always @* begin
        input_ready = state == LOAD;
        output_valid = state == OUTPUT_STREAM;
        busy = state != LOAD;
    end

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            state <= LOAD;
            load_beat <= '0;
            output_beat <= '0;
            stage_index <= '0;
            compute_cycle <= '0;
            done <= 1'b0;
        end else begin
            done <= 1'b0;
            case (state)
                LOAD: begin
                    if (input_valid) begin
                        for (lane = 0; lane < LANES; lane = lane + 1) begin
                            real_memory[bit_reverse(natural_input_address[lane])]
                                <= input_real[lane];
                            imag_memory[bit_reverse(natural_input_address[lane])]
                                <= input_imag[lane];
                        end
                        if (load_beat == LAST_LOAD_BEAT) begin
                            load_beat <= '0;
                            stage_index <= '0;
                            compute_cycle <= '0;
                            state <= COMPUTE;
                        end else begin
                            load_beat <= load_beat + 1'b1;
                        end
                    end
                end
                COMPUTE: begin
                    for (lane = 0; lane < LANES; lane = lane + 1) begin
                        real_memory[even_address[lane]] <=
                            selected_upper_real[lane][DATA_WIDTH-1:0];
                        imag_memory[even_address[lane]] <=
                            selected_upper_imag[lane][DATA_WIDTH-1:0];
                        real_memory[odd_address[lane]] <=
                            selected_lower_real[lane][DATA_WIDTH-1:0];
                        imag_memory[odd_address[lane]] <=
                            selected_lower_imag[lane][DATA_WIDTH-1:0];
                    end
                    if (compute_cycle == LAST_COMPUTE_CYCLE) begin
                        compute_cycle <= '0;
                        if (stage_index == LAST_STAGE) begin
                            output_beat <= '0;
                            state <= OUTPUT_STREAM;
                        end else begin
                            stage_index <= stage_index + 1'b1;
                        end
                    end else begin
                        compute_cycle <= compute_cycle + 1'b1;
                    end
                end
                OUTPUT_STREAM: begin
                    if (output_ready) begin
                        if (output_beat == LAST_LOAD_BEAT) begin
                            output_beat <= '0;
                            state <= LOAD;
                            done <= 1'b1;
                        end else begin
                            output_beat <= output_beat + 1'b1;
                        end
                    end
                end
                default: state <= LOAD;
            endcase
        end
    end
endmodule

`default_nettype wire

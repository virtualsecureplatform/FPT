`default_nettype none

// A synthesisable, one-butterfly iterative FFT core used as the first complete
// RTL reference. Natural-order inputs are written in bit-reversed order, so
// natural-order outputs emerge after the radix-2 DIT stages. Twiddles are
// supplied by an external ROM through twiddle_index; this keeps coefficient
// generation and storage technology outside the arithmetic core.
module fpt_fft_core #(
    parameter int POINTS = 16,
    parameter int DATA_WIDTH = 30,
    parameter int TWIDDLE_WIDTH = 26,
    parameter int TWIDDLE_FRAC = 24,
    parameter logic [$clog2(POINTS)-1:0] SCALE_MASK = '0
) (
    input  logic clk,
    input  logic rst_n,
    input  logic input_valid,
    output logic input_ready,
    input  logic signed [DATA_WIDTH-1:0] input_real,
    input  logic signed [DATA_WIDTH-1:0] input_imag,
    output logic [$clog2(POINTS)-2:0] twiddle_index,
    input  logic signed [TWIDDLE_WIDTH-1:0] twiddle_c,
    input  logic signed [TWIDDLE_WIDTH-1:0] twiddle_c_minus_d,
    input  logic signed [TWIDDLE_WIDTH-1:0] twiddle_c_plus_d,
    output logic output_valid,
    input  logic output_ready,
    output logic signed [DATA_WIDTH-1:0] output_real,
    output logic signed [DATA_WIDTH-1:0] output_imag,
    output logic busy,
    output logic done
);
    localparam int LOG_POINTS = $clog2(POINTS);
    localparam int INDEX_WIDTH = LOG_POINTS;
    localparam int STAGE_WIDTH = $clog2(LOG_POINTS);
    localparam logic [INDEX_WIDTH-1:0] LAST_INDEX =
        INDEX_WIDTH'(POINTS - 1);
    localparam logic [STAGE_WIDTH-1:0] LAST_STAGE =
        STAGE_WIDTH'(LOG_POINTS - 1);
    localparam logic [INDEX_WIDTH-1:0] INITIAL_GROUP_COUNT =
        INDEX_WIDTH'(POINTS / 2);

    typedef enum logic [1:0] {LOAD, COMPUTE, OUTPUT_STREAM} state_t;
    state_t state;
    logic signed [DATA_WIDTH-1:0] real_memory [0:POINTS-1];
    logic signed [DATA_WIDTH-1:0] imag_memory [0:POINTS-1];
    logic [INDEX_WIDTH-1:0] load_index;
    logic [INDEX_WIDTH-1:0] output_index;
    logic [STAGE_WIDTH-1:0] stage_index;
    logic [INDEX_WIDTH-1:0] group_index;
    logic [INDEX_WIDTH-1:0] butterfly_index;
    logic [INDEX_WIDTH-1:0] half_size;
    logic [INDEX_WIDTH-1:0] group_size;
    logic [INDEX_WIDTH-1:0] group_count;
    logic [INDEX_WIDTH-1:0] even_address;
    logic [INDEX_WIDTH-1:0] odd_address;
    /* verilator lint_off UNUSEDSIGNAL */
    logic [(2*INDEX_WIDTH)-1:0] twiddle_product;
    /* verilator lint_on UNUSEDSIGNAL */
    logic signed [DATA_WIDTH-1:0] twiddled_real;
    logic signed [DATA_WIDTH-1:0] twiddled_imag;
    logic signed [DATA_WIDTH:0] upper_real_wide;
    logic signed [DATA_WIDTH:0] upper_imag_wide;
    logic signed [DATA_WIDTH:0] lower_real_wide;
    logic signed [DATA_WIDTH:0] lower_imag_wide;
    /* verilator lint_off UNUSEDSIGNAL */
    logic signed [DATA_WIDTH:0] selected_upper_real;
    logic signed [DATA_WIDTH:0] selected_upper_imag;
    logic signed [DATA_WIDTH:0] selected_lower_real;
    logic signed [DATA_WIDTH:0] selected_lower_imag;
    /* verilator lint_on UNUSEDSIGNAL */

    function automatic [INDEX_WIDTH-1:0] bit_reverse(
        input logic [INDEX_WIDTH-1:0] value);
        integer bit_index;
        begin
            for (bit_index = 0; bit_index < INDEX_WIDTH; bit_index = bit_index + 1)
                bit_reverse[bit_index] = value[INDEX_WIDTH - 1 - bit_index];
        end
    endfunction

    initial begin
        if (POINTS < 4 || (POINTS & (POINTS - 1)) != 0)
            $error("fpt_fft_core POINTS must be a power of two >= 4");
    end

    fpt_gauss_mul #(
        .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
        .TWIDDLE_FRAC(TWIDDLE_FRAC)
    ) twiddle_multiplier (
        .a_real(real_memory[odd_address]),
        .a_imag(imag_memory[odd_address]),
        .twiddle_c,
        .twiddle_c_minus_d,
        .twiddle_c_plus_d,
        .result_real(twiddled_real),
        .result_imag(twiddled_imag)
    );

    always @* begin
        half_size = {{(INDEX_WIDTH-1){1'b0}}, 1'b1} << stage_index;
        group_size = half_size << 1;
        group_count = INITIAL_GROUP_COUNT >> stage_index;
        even_address = group_index * group_size + butterfly_index;
        odd_address = even_address + half_size;
        twiddle_product = butterfly_index * group_count;
        twiddle_index = twiddle_product[LOG_POINTS-2:0];
    end

    always @* begin
        upper_real_wide =
            {real_memory[even_address][DATA_WIDTH-1], real_memory[even_address]} +
            {twiddled_real[DATA_WIDTH-1], twiddled_real};
        upper_imag_wide =
            {imag_memory[even_address][DATA_WIDTH-1], imag_memory[even_address]} +
            {twiddled_imag[DATA_WIDTH-1], twiddled_imag};
        lower_real_wide =
            {real_memory[even_address][DATA_WIDTH-1], real_memory[even_address]} -
            {twiddled_real[DATA_WIDTH-1], twiddled_real};
        lower_imag_wide =
            {imag_memory[even_address][DATA_WIDTH-1], imag_memory[even_address]} -
            {twiddled_imag[DATA_WIDTH-1], twiddled_imag};

        if (SCALE_MASK[stage_index]) begin
            selected_upper_real = upper_real_wide >>> 1;
            selected_upper_imag = upper_imag_wide >>> 1;
            selected_lower_real = lower_real_wide >>> 1;
            selected_lower_imag = lower_imag_wide >>> 1;
        end else begin
            selected_upper_real = upper_real_wide;
            selected_upper_imag = upper_imag_wide;
            selected_lower_real = lower_real_wide;
            selected_lower_imag = lower_imag_wide;
        end
    end

    always @* begin
        input_ready = state == LOAD;
        output_valid = state == OUTPUT_STREAM;
        output_real = real_memory[output_index];
        output_imag = imag_memory[output_index];
        busy = state != LOAD;
    end

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            state <= LOAD;
            load_index <= '0;
            output_index <= '0;
            stage_index <= '0;
            group_index <= '0;
            butterfly_index <= '0;
            done <= 1'b0;
        end else begin
            done <= 1'b0;
            case (state)
                LOAD: begin
                    if (input_valid) begin
                        real_memory[bit_reverse(load_index)] <= input_real;
                        imag_memory[bit_reverse(load_index)] <= input_imag;
                        if (load_index == LAST_INDEX) begin
                            load_index <= '0;
                            stage_index <= '0;
                            group_index <= '0;
                            butterfly_index <= '0;
                            state <= COMPUTE;
                        end else begin
                            load_index <= load_index + 1'b1;
                        end
                    end
                end
                COMPUTE: begin
                    real_memory[even_address] <=
                        selected_upper_real[DATA_WIDTH-1:0];
                    imag_memory[even_address] <=
                        selected_upper_imag[DATA_WIDTH-1:0];
                    real_memory[odd_address] <=
                        selected_lower_real[DATA_WIDTH-1:0];
                    imag_memory[odd_address] <=
                        selected_lower_imag[DATA_WIDTH-1:0];

                    if (butterfly_index == half_size - 1'b1) begin
                        butterfly_index <= '0;
                        if (group_index == group_count - 1'b1) begin
                            group_index <= '0;
                            if (stage_index == LAST_STAGE) begin
                                output_index <= '0;
                                state <= OUTPUT_STREAM;
                            end else begin
                                stage_index <= stage_index + 1'b1;
                            end
                        end else begin
                            group_index <= group_index + 1'b1;
                        end
                    end else begin
                        butterfly_index <= butterfly_index + 1'b1;
                    end
                end
                OUTPUT_STREAM: begin
                    if (output_ready) begin
                        if (output_index == LAST_INDEX) begin
                            output_index <= '0;
                            state <= LOAD;
                            done <= 1'b1;
                        end else begin
                            output_index <= output_index + 1'b1;
                        end
                    end
                end
                default: state <= LOAD;
            endcase
        end
    end
endmodule

`default_nettype wire

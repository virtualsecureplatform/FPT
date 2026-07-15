`default_nettype none

// One registered radix-2 DIT butterfly. The odd input is multiplied by a
// pre-expanded Gauss twiddle (C, C-D, C+D). SCALE=1 divides the widened
// butterfly outputs by two before wrapping.
module fpt_butterfly #(
    parameter int DATA_WIDTH = 30,
    parameter int TWIDDLE_WIDTH = 26,
    parameter int TWIDDLE_FRAC = 24,
    parameter bit SCALE = 1'b0
) (
    input  logic clk,
    input  logic rst_n,
    input  logic valid_in,
    input  logic signed [DATA_WIDTH-1:0] even_real,
    input  logic signed [DATA_WIDTH-1:0] even_imag,
    input  logic signed [DATA_WIDTH-1:0] odd_real,
    input  logic signed [DATA_WIDTH-1:0] odd_imag,
    input  logic signed [TWIDDLE_WIDTH-1:0] twiddle_c,
    input  logic signed [TWIDDLE_WIDTH-1:0] twiddle_c_minus_d,
    input  logic signed [TWIDDLE_WIDTH-1:0] twiddle_c_plus_d,
    output logic valid_out,
    output logic signed [DATA_WIDTH-1:0] upper_real,
    output logic signed [DATA_WIDTH-1:0] upper_imag,
    output logic signed [DATA_WIDTH-1:0] lower_real,
    output logic signed [DATA_WIDTH-1:0] lower_imag
);
    logic signed [DATA_WIDTH-1:0] twiddled_real;
    logic signed [DATA_WIDTH-1:0] twiddled_imag;
    logic signed [DATA_WIDTH:0] sum_real;
    logic signed [DATA_WIDTH:0] sum_imag;
    logic signed [DATA_WIDTH:0] difference_real;
    logic signed [DATA_WIDTH:0] difference_imag;
    /* verilator lint_off UNUSEDSIGNAL */
    logic signed [DATA_WIDTH:0] scaled_sum_real;
    logic signed [DATA_WIDTH:0] scaled_sum_imag;
    logic signed [DATA_WIDTH:0] scaled_difference_real;
    logic signed [DATA_WIDTH:0] scaled_difference_imag;
    /* verilator lint_on UNUSEDSIGNAL */

    fpt_gauss_mul #(
        .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
        .TWIDDLE_FRAC(TWIDDLE_FRAC)
    ) twiddle_multiplier (
        .a_real(odd_real),
        .a_imag(odd_imag),
        .twiddle_c(twiddle_c),
        .twiddle_c_minus_d(twiddle_c_minus_d),
        .twiddle_c_plus_d(twiddle_c_plus_d),
        .result_real(twiddled_real),
        .result_imag(twiddled_imag)
    );

    always @* begin
        sum_real = {even_real[DATA_WIDTH-1], even_real} +
                   {twiddled_real[DATA_WIDTH-1], twiddled_real};
        sum_imag = {even_imag[DATA_WIDTH-1], even_imag} +
                   {twiddled_imag[DATA_WIDTH-1], twiddled_imag};
        difference_real = {even_real[DATA_WIDTH-1], even_real} -
                          {twiddled_real[DATA_WIDTH-1], twiddled_real};
        difference_imag = {even_imag[DATA_WIDTH-1], even_imag} -
                          {twiddled_imag[DATA_WIDTH-1], twiddled_imag};
        scaled_sum_real = sum_real >>> 1;
        scaled_sum_imag = sum_imag >>> 1;
        scaled_difference_real = difference_real >>> 1;
        scaled_difference_imag = difference_imag >>> 1;
    end

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            valid_out <= 1'b0;
            upper_real <= '0;
            upper_imag <= '0;
            lower_real <= '0;
            lower_imag <= '0;
        end else begin
            valid_out <= valid_in;
            if (valid_in) begin
                if (SCALE) begin
                    upper_real <= scaled_sum_real[DATA_WIDTH-1:0];
                    upper_imag <= scaled_sum_imag[DATA_WIDTH-1:0];
                    lower_real <= scaled_difference_real[DATA_WIDTH-1:0];
                    lower_imag <= scaled_difference_imag[DATA_WIDTH-1:0];
                end else begin
                    upper_real <= sum_real[DATA_WIDTH-1:0];
                    upper_imag <= sum_imag[DATA_WIDTH-1:0];
                    lower_real <= difference_real[DATA_WIDTH-1:0];
                    lower_imag <= difference_imag[DATA_WIDTH-1:0];
                end
            end
        end
    end
endmodule

`default_nettype wire

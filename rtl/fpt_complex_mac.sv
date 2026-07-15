`default_nettype none

// Mixed-format complex multiply-accumulate used by the External Product.
// The two products are formed at full precision, requantized once to the
// accumulator format, and then added with two's-complement wrap.
module fpt_complex_mac #(
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
    input  logic signed [A_WIDTH-1:0] a_real,
    input  logic signed [A_WIDTH-1:0] a_imag,
    input  logic signed [B_WIDTH-1:0] b_real,
    input  logic signed [B_WIDTH-1:0] b_imag,
    input  logic signed [ACC_WIDTH-1:0] accumulator_real,
    input  logic signed [ACC_WIDTH-1:0] accumulator_imag,
    output logic valid_out,
    output logic signed [ACC_WIDTH-1:0] result_real,
    output logic signed [ACC_WIDTH-1:0] result_imag
);
    localparam int PRODUCT_WIDTH = A_WIDTH + B_WIDTH;
    localparam int PRODUCT_FRAC = A_FRAC + B_FRAC;
    localparam int SHIFT = PRODUCT_FRAC - ACC_FRAC;

    logic signed [PRODUCT_WIDTH-1:0] a_real_ext;
    logic signed [PRODUCT_WIDTH-1:0] a_imag_ext;
    logic signed [PRODUCT_WIDTH-1:0] b_real_ext;
    logic signed [PRODUCT_WIDTH-1:0] b_imag_ext;
    logic signed [PRODUCT_WIDTH-1:0] ac;
    logic signed [PRODUCT_WIDTH-1:0] bd;
    logic signed [PRODUCT_WIDTH-1:0] ad;
    logic signed [PRODUCT_WIDTH-1:0] bc;
    logic signed [PRODUCT_WIDTH:0] product_real;
    logic signed [PRODUCT_WIDTH:0] product_imag;
    /* verilator lint_off UNUSEDSIGNAL */
    logic signed [PRODUCT_WIDTH:0] shifted_real;
    logic signed [PRODUCT_WIDTH:0] shifted_imag;
    logic signed [ACC_WIDTH-1:0] quantized_real;
    logic signed [ACC_WIDTH-1:0] quantized_imag;
    logic signed [ACC_WIDTH:0] accumulated_real;
    logic signed [ACC_WIDTH:0] accumulated_imag;
    /* verilator lint_on UNUSEDSIGNAL */

    initial begin
        if (SHIFT < 0)
            $error("fpt_complex_mac requires PRODUCT_FRAC >= ACC_FRAC");
    end

    always @* begin
        a_real_ext = {{B_WIDTH{a_real[A_WIDTH-1]}}, a_real};
        a_imag_ext = {{B_WIDTH{a_imag[A_WIDTH-1]}}, a_imag};
        b_real_ext = {{A_WIDTH{b_real[B_WIDTH-1]}}, b_real};
        b_imag_ext = {{A_WIDTH{b_imag[B_WIDTH-1]}}, b_imag};
        ac = a_real_ext * b_real_ext;
        bd = a_imag_ext * b_imag_ext;
        ad = a_real_ext * b_imag_ext;
        bc = a_imag_ext * b_real_ext;
        product_real = {ac[PRODUCT_WIDTH-1], ac} -
                       {bd[PRODUCT_WIDTH-1], bd};
        product_imag = {ad[PRODUCT_WIDTH-1], ad} +
                       {bc[PRODUCT_WIDTH-1], bc};
        shifted_real = product_real >>> SHIFT;
        shifted_imag = product_imag >>> SHIFT;
        quantized_real = shifted_real[ACC_WIDTH-1:0];
        quantized_imag = shifted_imag[ACC_WIDTH-1:0];
        accumulated_real =
            {accumulator_real[ACC_WIDTH-1], accumulator_real} +
            {quantized_real[ACC_WIDTH-1], quantized_real};
        accumulated_imag =
            {accumulator_imag[ACC_WIDTH-1], accumulator_imag} +
            {quantized_imag[ACC_WIDTH-1], quantized_imag};
    end

    always_ff @(posedge clk) begin
        if (!rst_n) begin
            valid_out <= 1'b0;
            result_real <= '0;
            result_imag <= '0;
        end else begin
            valid_out <= valid_in;
            if (valid_in) begin
                result_real <= accumulated_real[ACC_WIDTH-1:0];
                result_imag <= accumulated_imag[ACC_WIDTH-1:0];
            end
        end
    end
endmodule

`default_nettype wire

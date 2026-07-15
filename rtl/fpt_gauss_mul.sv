`default_nettype none

// Equation (6) from the FPT paper. All ports carry signed fixed-point raw
// integers. Products are arithmetically shifted and then wrapped to DATA_WIDTH,
// matching the C++ reference and SGen's fixed-point bit slicing.
module fpt_gauss_mul #(
    parameter int DATA_WIDTH = 30,
    parameter int TWIDDLE_WIDTH = 26,
    parameter int TWIDDLE_FRAC = 24
) (
    input  logic signed [DATA_WIDTH-1:0] a_real,
    input  logic signed [DATA_WIDTH-1:0] a_imag,
    input  logic signed [TWIDDLE_WIDTH-1:0] twiddle_c,
    input  logic signed [TWIDDLE_WIDTH-1:0] twiddle_c_minus_d,
    input  logic signed [TWIDDLE_WIDTH-1:0] twiddle_c_plus_d,
    output logic signed [DATA_WIDTH-1:0] result_real,
    output logic signed [DATA_WIDTH-1:0] result_imag
);
    localparam int PRODUCT_WIDTH = DATA_WIDTH + TWIDDLE_WIDTH;

    logic signed [DATA_WIDTH-1:0] a_minus_b;
    logic signed [PRODUCT_WIDTH-1:0] a_minus_b_ext;
    logic signed [PRODUCT_WIDTH-1:0] a_real_ext;
    logic signed [PRODUCT_WIDTH-1:0] a_imag_ext;
    logic signed [PRODUCT_WIDTH-1:0] c_ext;
    logic signed [PRODUCT_WIDTH-1:0] c_minus_d_ext;
    logic signed [PRODUCT_WIDTH-1:0] c_plus_d_ext;
    logic signed [PRODUCT_WIDTH-1:0] product_z;
    logic signed [PRODUCT_WIDTH-1:0] product_x;
    logic signed [PRODUCT_WIDTH-1:0] product_y;
    /* verilator lint_off UNUSEDSIGNAL */
    logic signed [PRODUCT_WIDTH-1:0] shifted_z;
    logic signed [PRODUCT_WIDTH-1:0] shifted_x;
    logic signed [PRODUCT_WIDTH-1:0] shifted_y;
    /* verilator lint_on UNUSEDSIGNAL */
    logic signed [DATA_WIDTH-1:0] z;
    logic signed [DATA_WIDTH-1:0] x_part;
    logic signed [DATA_WIDTH-1:0] y_part;

    always @* begin
        a_minus_b = a_real - a_imag;
        a_minus_b_ext = {{TWIDDLE_WIDTH{a_minus_b[DATA_WIDTH-1]}},
                         a_minus_b};
        a_real_ext = {{TWIDDLE_WIDTH{a_real[DATA_WIDTH-1]}}, a_real};
        a_imag_ext = {{TWIDDLE_WIDTH{a_imag[DATA_WIDTH-1]}}, a_imag};
        c_ext = {{DATA_WIDTH{twiddle_c[TWIDDLE_WIDTH-1]}}, twiddle_c};
        c_minus_d_ext =
            {{DATA_WIDTH{twiddle_c_minus_d[TWIDDLE_WIDTH-1]}},
             twiddle_c_minus_d};
        c_plus_d_ext =
            {{DATA_WIDTH{twiddle_c_plus_d[TWIDDLE_WIDTH-1]}},
             twiddle_c_plus_d};

        product_z = a_minus_b_ext * c_ext;
        product_x = a_imag_ext * c_minus_d_ext;
        product_y = a_real_ext * c_plus_d_ext;
        shifted_z = product_z >>> TWIDDLE_FRAC;
        shifted_x = product_x >>> TWIDDLE_FRAC;
        shifted_y = product_y >>> TWIDDLE_FRAC;

        z = shifted_z[DATA_WIDTH-1:0];
        x_part = shifted_x[DATA_WIDTH-1:0];
        y_part = shifted_y[DATA_WIDTH-1:0];
        result_real = x_part + z;
        result_imag = y_part - z;
    end
endmodule

`default_nettype wire

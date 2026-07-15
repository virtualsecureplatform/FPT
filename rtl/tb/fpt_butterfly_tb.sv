`timescale 1ns/1ps
`default_nettype none

module fpt_butterfly_tb;
    localparam int DATA_WIDTH = 30;
    localparam int TWIDDLE_WIDTH = 26;
    localparam int TWIDDLE_FRAC = 24;

    logic clk = 1'b0;
    logic rst_n = 1'b0;
    logic valid_in = 1'b0;
    logic signed [DATA_WIDTH-1:0] even_real;
    logic signed [DATA_WIDTH-1:0] even_imag;
    logic signed [DATA_WIDTH-1:0] odd_real;
    logic signed [DATA_WIDTH-1:0] odd_imag;
    logic signed [TWIDDLE_WIDTH-1:0] twiddle_c;
    logic signed [TWIDDLE_WIDTH-1:0] twiddle_c_minus_d;
    logic signed [TWIDDLE_WIDTH-1:0] twiddle_c_plus_d;

    logic valid_unscaled;
    logic signed [DATA_WIDTH-1:0] upper_real_unscaled;
    logic signed [DATA_WIDTH-1:0] upper_imag_unscaled;
    logic signed [DATA_WIDTH-1:0] lower_real_unscaled;
    logic signed [DATA_WIDTH-1:0] lower_imag_unscaled;
    logic valid_scaled;
    logic signed [DATA_WIDTH-1:0] upper_real_scaled;
    logic signed [DATA_WIDTH-1:0] upper_imag_scaled;
    logic signed [DATA_WIDTH-1:0] lower_real_scaled;
    logic signed [DATA_WIDTH-1:0] lower_imag_scaled;

    longint signed v_even_real;
    longint signed v_even_imag;
    longint signed v_odd_real;
    longint signed v_odd_imag;
    longint signed v_c;
    longint signed v_c_minus_d;
    longint signed v_c_plus_d;
    longint signed expected_upper_real;
    longint signed expected_upper_imag;
    longint signed expected_lower_real;
    longint signed expected_lower_imag;
    longint signed expected_scaled_upper_real;
    longint signed expected_scaled_upper_imag;
    longint signed expected_scaled_lower_real;
    longint signed expected_scaled_lower_imag;
    integer vectors;
    integer status;
    integer vector_count = 0;

    always #5 clk = ~clk;

    fpt_butterfly #(
        .DATA_WIDTH(DATA_WIDTH), .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
        .TWIDDLE_FRAC(TWIDDLE_FRAC), .SCALE(1'b0)
    ) unscaled (
        .clk, .rst_n, .valid_in, .even_real, .even_imag, .odd_real,
        .odd_imag, .twiddle_c, .twiddle_c_minus_d, .twiddle_c_plus_d,
        .valid_out(valid_unscaled), .upper_real(upper_real_unscaled),
        .upper_imag(upper_imag_unscaled), .lower_real(lower_real_unscaled),
        .lower_imag(lower_imag_unscaled)
    );

    fpt_butterfly #(
        .DATA_WIDTH(DATA_WIDTH), .TWIDDLE_WIDTH(TWIDDLE_WIDTH),
        .TWIDDLE_FRAC(TWIDDLE_FRAC), .SCALE(1'b1)
    ) scaled (
        .clk, .rst_n, .valid_in, .even_real, .even_imag, .odd_real,
        .odd_imag, .twiddle_c, .twiddle_c_minus_d, .twiddle_c_plus_d,
        .valid_out(valid_scaled), .upper_real(upper_real_scaled),
        .upper_imag(upper_imag_scaled), .lower_real(lower_real_scaled),
        .lower_imag(lower_imag_scaled)
    );

    task automatic fail(input string name, input longint signed actual,
                        input longint signed expected);
        if (actual != expected) begin
            $display("FAIL vector=%0d %s actual=%0d expected=%0d",
                     vector_count, name, actual, expected);
            $fatal(1);
        end
    endtask

    initial begin
        vectors = $fopen("rtl_vectors.txt", "r");
        if (vectors == 0) $fatal(1, "could not open rtl_vectors.txt");

        repeat (2) @(posedge clk);
        rst_n = 1'b1;
        while (!$feof(vectors)) begin
            status = $fscanf(vectors,
                "%d %d %d %d %d %d %d %d %d %d %d %d %d %d %d\n",
                v_even_real, v_even_imag, v_odd_real, v_odd_imag,
                v_c, v_c_minus_d, v_c_plus_d,
                expected_upper_real, expected_upper_imag,
                expected_lower_real, expected_lower_imag,
                expected_scaled_upper_real, expected_scaled_upper_imag,
                expected_scaled_lower_real, expected_scaled_lower_imag);
            if (status == 15) begin
                @(negedge clk);
                even_real = v_even_real;
                even_imag = v_even_imag;
                odd_real = v_odd_real;
                odd_imag = v_odd_imag;
                twiddle_c = v_c;
                twiddle_c_minus_d = v_c_minus_d;
                twiddle_c_plus_d = v_c_plus_d;
                valid_in = 1'b1;
                @(posedge clk);
                #1;
                if (!valid_unscaled || !valid_scaled)
                    $fatal(1, "valid pipeline mismatch");
                fail("upper_real", upper_real_unscaled, expected_upper_real);
                fail("upper_imag", upper_imag_unscaled, expected_upper_imag);
                fail("lower_real", lower_real_unscaled, expected_lower_real);
                fail("lower_imag", lower_imag_unscaled, expected_lower_imag);
                fail("scaled_upper_real", upper_real_scaled,
                     expected_scaled_upper_real);
                fail("scaled_upper_imag", upper_imag_scaled,
                     expected_scaled_upper_imag);
                fail("scaled_lower_real", lower_real_scaled,
                     expected_scaled_lower_real);
                fail("scaled_lower_imag", lower_imag_scaled,
                     expected_scaled_lower_imag);
                vector_count = vector_count + 1;
            end
        end
        @(negedge clk);
        valid_in = 1'b0;
        $fclose(vectors);
        $display("PASS fpt_butterfly vectors=%0d", vector_count);
        $finish;
    end
endmodule

`default_nettype wire

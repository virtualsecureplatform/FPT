`timescale 1ns/1ps
`default_nettype none

module fpt_complex_mac_wide_tb;
    localparam int LANES = 4;
    localparam int A_WIDTH = 38;
    localparam int A_FRAC = 20;
    localparam int B_WIDTH = 32;
    localparam int B_FRAC = 24;
    localparam int ACC_WIDTH = 41;
    localparam int ACC_FRAC = 14;
    localparam int VECTORS = 256;

    logic clk = 1'b0;
    logic rst_n = 1'b0;
    logic valid_in = 1'b0;
    logic signed [LANES-1:0][A_WIDTH-1:0] a_real;
    logic signed [LANES-1:0][A_WIDTH-1:0] a_imag;
    logic signed [LANES-1:0][B_WIDTH-1:0] b_real;
    logic signed [LANES-1:0][B_WIDTH-1:0] b_imag;
    logic signed [LANES-1:0][ACC_WIDTH-1:0] accumulator_real;
    logic signed [LANES-1:0][ACC_WIDTH-1:0] accumulator_imag;
    logic valid_out;
    logic signed [LANES-1:0][ACC_WIDTH-1:0] result_real;
    logic signed [LANES-1:0][ACC_WIDTH-1:0] result_imag;

    longint signed v_a_real;
    longint signed v_a_imag;
    longint signed v_b_real;
    longint signed v_b_imag;
    longint signed v_accumulator_real;
    longint signed v_accumulator_imag;
    longint signed expected_real [0:LANES-1];
    longint signed expected_imag [0:LANES-1];
    integer vectors;
    integer status;
    integer vector_count = 0;
    integer lane;

    always #5 clk = ~clk;

    fpt_complex_mac_wide #(
        .LANES(LANES), .A_WIDTH(A_WIDTH), .A_FRAC(A_FRAC),
        .B_WIDTH(B_WIDTH), .B_FRAC(B_FRAC),
        .ACC_WIDTH(ACC_WIDTH), .ACC_FRAC(ACC_FRAC)
    ) dut (.*);

    initial begin
        vectors = $fopen("rtl_mac_vectors.txt", "r");
        if (vectors == 0) $fatal(1, "could not open rtl_mac_vectors.txt");
        repeat (2) @(posedge clk);
        rst_n = 1'b1;
        while (vector_count < VECTORS) begin
            @(negedge clk);
            for (lane = 0; lane < LANES; lane = lane + 1) begin
                status = $fscanf(vectors, "%d %d %d %d %d %d %d %d\n",
                    v_a_real, v_a_imag, v_b_real, v_b_imag,
                    v_accumulator_real, v_accumulator_imag,
                    expected_real[lane], expected_imag[lane]);
                if (status != 8) $fatal(1, "invalid wide MAC vector");
                a_real[lane] = v_a_real;
                a_imag[lane] = v_a_imag;
                b_real[lane] = v_b_real;
                b_imag[lane] = v_b_imag;
                accumulator_real[lane] = v_accumulator_real;
                accumulator_imag[lane] = v_accumulator_imag;
            end
            valid_in = 1'b1;
            @(posedge clk);
            #1;
            if (!valid_out) $fatal(1, "wide MAC valid pipeline mismatch");
            for (lane = 0; lane < LANES; lane = lane + 1) begin
                if ($signed(result_real[lane]) != expected_real[lane])
                    $fatal(1, "wide MAC real mismatch vector=%0d",
                           vector_count + lane);
                if ($signed(result_imag[lane]) != expected_imag[lane])
                    $fatal(1, "wide MAC imag mismatch vector=%0d",
                           vector_count + lane);
            end
            vector_count = vector_count + LANES;
        end
        @(negedge clk);
        valid_in = 1'b0;
        $fclose(vectors);
        $display("PASS fpt_complex_mac_wide vectors=%0d lanes=%0d",
                 vector_count, LANES);
        $finish;
    end
endmodule

`default_nettype wire

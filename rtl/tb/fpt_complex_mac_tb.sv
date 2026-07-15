`timescale 1ns/1ps
`default_nettype none

module fpt_complex_mac_tb;
    localparam int A_WIDTH = 38;
    localparam int A_FRAC = 20;
    localparam int B_WIDTH = 32;
    localparam int B_FRAC = 24;
    localparam int ACC_WIDTH = 41;
    localparam int ACC_FRAC = 14;

    logic clk = 1'b0;
    logic rst_n = 1'b0;
    logic valid_in = 1'b0;
    logic signed [A_WIDTH-1:0] a_real;
    logic signed [A_WIDTH-1:0] a_imag;
    logic signed [B_WIDTH-1:0] b_real;
    logic signed [B_WIDTH-1:0] b_imag;
    logic signed [ACC_WIDTH-1:0] accumulator_real;
    logic signed [ACC_WIDTH-1:0] accumulator_imag;
    logic valid_out;
    logic signed [ACC_WIDTH-1:0] result_real;
    logic signed [ACC_WIDTH-1:0] result_imag;

    longint signed v_a_real;
    longint signed v_a_imag;
    longint signed v_b_real;
    longint signed v_b_imag;
    longint signed v_accumulator_real;
    longint signed v_accumulator_imag;
    longint signed expected_real;
    longint signed expected_imag;
    integer vectors;
    integer status;
    integer vector_count = 0;

    always #5 clk = ~clk;

    fpt_complex_mac #(
        .A_WIDTH(A_WIDTH), .A_FRAC(A_FRAC),
        .B_WIDTH(B_WIDTH), .B_FRAC(B_FRAC),
        .ACC_WIDTH(ACC_WIDTH), .ACC_FRAC(ACC_FRAC)
    ) dut (
        .clk, .rst_n, .valid_in, .a_real, .a_imag, .b_real, .b_imag,
        .accumulator_real, .accumulator_imag, .valid_out, .result_real,
        .result_imag
    );

    initial begin
        vectors = $fopen("rtl_mac_vectors.txt", "r");
        if (vectors == 0) $fatal(1, "could not open rtl_mac_vectors.txt");
        repeat (2) @(posedge clk);
        rst_n = 1'b1;
        while (!$feof(vectors)) begin
            status = $fscanf(vectors, "%d %d %d %d %d %d %d %d\n",
                v_a_real, v_a_imag, v_b_real, v_b_imag,
                v_accumulator_real, v_accumulator_imag,
                expected_real, expected_imag);
            if (status == 8) begin
                @(negedge clk);
                a_real = v_a_real;
                a_imag = v_a_imag;
                b_real = v_b_real;
                b_imag = v_b_imag;
                accumulator_real = v_accumulator_real;
                accumulator_imag = v_accumulator_imag;
                valid_in = 1'b1;
                @(posedge clk);
                #1;
                if (!valid_out) $fatal(1, "valid pipeline mismatch");
                if ($signed(result_real) != expected_real)
                    $fatal(1,
                        "real mismatch vector=%0d actual=%0d expected=%0d",
                        vector_count, $signed(result_real), expected_real);
                if ($signed(result_imag) != expected_imag)
                    $fatal(1,
                        "imag mismatch vector=%0d actual=%0d expected=%0d",
                        vector_count, $signed(result_imag), expected_imag);
                vector_count = vector_count + 1;
            end
        end
        @(negedge clk);
        valid_in = 1'b0;
        $fclose(vectors);
        $display("PASS fpt_complex_mac vectors=%0d", vector_count);
        $finish;
    end
endmodule

`default_nettype wire


`timescale 1ns/1ps
`default_nettype none

module fpt_tangent_ifft_wide_core_tb;
    localparam int POINTS = 16;
    localparam int LANES = 4;
    localparam int DATA_WIDTH = 30;
    localparam int TWIDDLE_WIDTH = 26;
    localparam int TWIDDLE_FRAC = 24;
    localparam int FRAMES = 8;

    logic clk = 1'b0;
    logic rst_n = 1'b0;
    logic input_valid = 1'b0;
    logic input_ready;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] input_real;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] input_imag;
    logic [LANES-1:0][$clog2(POINTS)-2:0] fft_twiddle_index;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] fft_twiddle_c;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] fft_twiddle_c_minus_d;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] fft_twiddle_c_plus_d;
    logic [LANES-1:0][$clog2(POINTS)-1:0] untwist_index;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] untwist_c;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] untwist_c_minus_d;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] untwist_c_plus_d;
    logic output_valid;
    logic output_ready = 1'b0;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] coefficient_low;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] coefficient_high;
    logic busy;
    logic done;

    logic signed [TWIDDLE_WIDTH-1:0] fft_c_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] fft_cmd_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] fft_cpd_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] untwist_c_rom [0:POINTS-1];
    logic signed [TWIDDLE_WIDTH-1:0] untwist_cmd_rom [0:POINTS-1];
    logic signed [TWIDDLE_WIDTH-1:0] untwist_cpd_rom [0:POINTS-1];
    longint signed frame_input_real [0:POINTS-1];
    longint signed frame_input_imag [0:POINTS-1];
    longint signed expected_low [0:POINTS-1];
    longint signed expected_high [0:POINTS-1];
    longint signed v_c;
    longint signed v_cmd;
    longint signed v_cpd;
    integer vector_file;
    integer fft_twiddle_file;
    integer untwist_file;
    integer status;
    integer frame;
    integer index;
    integer lane;

    always #5 clk = ~clk;
    always @* begin
        for (lane = 0; lane < LANES; lane = lane + 1) begin
            fft_twiddle_c[lane] = fft_c_rom[fft_twiddle_index[lane]];
            fft_twiddle_c_minus_d[lane] = fft_cmd_rom[fft_twiddle_index[lane]];
            fft_twiddle_c_plus_d[lane] = fft_cpd_rom[fft_twiddle_index[lane]];
            untwist_c[lane] = untwist_c_rom[untwist_index[lane]];
            untwist_c_minus_d[lane] = untwist_cmd_rom[untwist_index[lane]];
            untwist_c_plus_d[lane] = untwist_cpd_rom[untwist_index[lane]];
        end
    end

    fpt_tangent_ifft_wide_core #(
        .POINTS(POINTS), .LANES(LANES), .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH), .TWIDDLE_FRAC(TWIDDLE_FRAC),
        .NORMALIZE_SHIFT($clog2(POINTS)), .SCALE_MASK('0)
    ) dut (.*);

    initial begin
        fft_twiddle_file = $fopen("rtl_ifft_twiddles.txt", "r");
        untwist_file = $fopen("rtl_untwist_twiddles.txt", "r");
        if (fft_twiddle_file == 0 || untwist_file == 0)
            $fatal(1, "could not open wide inverse twiddles");
        for (index = 0; index < POINTS / 2; index = index + 1) begin
            status = $fscanf(fft_twiddle_file, "%d %d %d\n",
                             v_c, v_cmd, v_cpd);
            if (status != 3) $fatal(1, "invalid inverse FFT twiddle");
            fft_c_rom[index] = v_c;
            fft_cmd_rom[index] = v_cmd;
            fft_cpd_rom[index] = v_cpd;
        end
        for (index = 0; index < POINTS; index = index + 1) begin
            status = $fscanf(untwist_file, "%d %d %d\n", v_c, v_cmd, v_cpd);
            if (status != 3) $fatal(1, "invalid untwist vector");
            untwist_c_rom[index] = v_c;
            untwist_cmd_rom[index] = v_cmd;
            untwist_cpd_rom[index] = v_cpd;
        end
        $fclose(fft_twiddle_file);
        $fclose(untwist_file);

        vector_file = $fopen("rtl_ifft_vectors.txt", "r");
        if (vector_file == 0) $fatal(1, "could not open inverse vectors");
        repeat (2) @(posedge clk);
        rst_n = 1'b1;

        for (frame = 0; frame < FRAMES; frame = frame + 1) begin
            for (index = 0; index < POINTS; index = index + 1) begin
                status = $fscanf(vector_file, "%d %d %d %d\n",
                    frame_input_real[index], frame_input_imag[index],
                    expected_low[index], expected_high[index]);
                if (status != 4) $fatal(1, "invalid inverse frame");
            end
            for (index = 0; index < POINTS; index = index + LANES) begin
                @(negedge clk);
                if (!input_ready)
                    $fatal(1, "wide inverse input backpressure mismatch");
                for (lane = 0; lane < LANES; lane = lane + 1) begin
                    input_real[lane] = frame_input_real[index + lane];
                    input_imag[lane] = frame_input_imag[index + lane];
                end
                input_valid = 1'b1;
            end
            @(negedge clk);
            input_valid = 1'b0;
            output_ready = 1'b1;
            wait (output_valid);
            for (index = 0; index < POINTS; index = index + LANES) begin
                #1;
                for (lane = 0; lane < LANES; lane = lane + 1) begin
                    if ($signed(coefficient_low[lane]) != expected_low[index + lane])
                        $fatal(1, "wide inverse low mismatch frame=%0d index=%0d",
                               frame, index + lane);
                    if ($signed(coefficient_high[lane]) != expected_high[index + lane])
                        $fatal(1, "wide inverse high mismatch frame=%0d index=%0d",
                               frame, index + lane);
                end
                @(posedge clk);
            end
            #1;
            output_ready = 1'b0;
            if (!done) $fatal(1, "wide inverse done pulse missing");
        end
        $fclose(vector_file);
        $display("PASS fpt_tangent_ifft_wide_core frames=%0d polynomial_points=%0d lanes=%0d",
                 FRAMES, 2 * POINTS, LANES);
        $finish;
    end
endmodule

`default_nettype wire

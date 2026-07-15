`timescale 1ns/1ps
`default_nettype none

module fpt_tangent_fft_wide_core_tb;
    localparam int POINTS = 16;
    localparam int LANES = 4;
    localparam int DATA_WIDTH = 30;
    localparam int TWIDDLE_WIDTH = 26;
    localparam int TWIDDLE_FRAC = 24;
    localparam int FRAMES = 8;

    logic clk = 1'b0;
    logic rst_n = 1'b0;
    logic pair_valid = 1'b0;
    logic pair_ready;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] coefficient_low;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] coefficient_high;
    logic [LANES-1:0][$clog2(POINTS)-1:0] twist_index;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] twist_c;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] twist_c_minus_d;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] twist_c_plus_d;
    logic [LANES-1:0][$clog2(POINTS)-2:0] fft_twiddle_index;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] fft_twiddle_c;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] fft_twiddle_c_minus_d;
    logic signed [LANES-1:0][TWIDDLE_WIDTH-1:0] fft_twiddle_c_plus_d;
    logic output_valid;
    logic output_ready = 1'b0;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] output_real;
    logic signed [LANES-1:0][DATA_WIDTH-1:0] output_imag;
    logic busy;
    logic done;

    logic signed [TWIDDLE_WIDTH-1:0] fft_c_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] fft_cmd_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] fft_cpd_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] twist_c_rom [0:POINTS-1];
    logic signed [TWIDDLE_WIDTH-1:0] twist_cmd_rom [0:POINTS-1];
    logic signed [TWIDDLE_WIDTH-1:0] twist_cpd_rom [0:POINTS-1];
    longint signed input_low [0:POINTS-1];
    longint signed input_high [0:POINTS-1];
    longint signed expected_real [0:POINTS-1];
    longint signed expected_imag [0:POINTS-1];
    longint signed v_c;
    longint signed v_cmd;
    longint signed v_cpd;
    integer vector_file;
    integer fft_twiddle_file;
    integer twist_file;
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
            twist_c[lane] = twist_c_rom[twist_index[lane]];
            twist_c_minus_d[lane] = twist_cmd_rom[twist_index[lane]];
            twist_c_plus_d[lane] = twist_cpd_rom[twist_index[lane]];
        end
    end

    fpt_tangent_fft_wide_core #(
        .POINTS(POINTS), .LANES(LANES), .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH), .TWIDDLE_FRAC(TWIDDLE_FRAC),
        .SCALE_MASK('0)
    ) dut (.*);

    initial begin
        fft_twiddle_file = $fopen("rtl_fft_twiddles.txt", "r");
        twist_file = $fopen("rtl_tangent_twiddles.txt", "r");
        if (fft_twiddle_file == 0 || twist_file == 0)
            $fatal(1, "could not open wide tangent twiddles");
        for (index = 0; index < POINTS / 2; index = index + 1) begin
            status = $fscanf(fft_twiddle_file, "%d %d %d\n",
                             v_c, v_cmd, v_cpd);
            if (status != 3) $fatal(1, "invalid FFT twiddle vector");
            fft_c_rom[index] = v_c;
            fft_cmd_rom[index] = v_cmd;
            fft_cpd_rom[index] = v_cpd;
        end
        for (index = 0; index < POINTS; index = index + 1) begin
            status = $fscanf(twist_file, "%d %d %d\n", v_c, v_cmd, v_cpd);
            if (status != 3) $fatal(1, "invalid twist vector");
            twist_c_rom[index] = v_c;
            twist_cmd_rom[index] = v_cmd;
            twist_cpd_rom[index] = v_cpd;
        end
        $fclose(fft_twiddle_file);
        $fclose(twist_file);

        vector_file = $fopen("rtl_tangent_vectors.txt", "r");
        if (vector_file == 0) $fatal(1, "could not open tangent vectors");
        repeat (2) @(posedge clk);
        rst_n = 1'b1;

        for (frame = 0; frame < FRAMES; frame = frame + 1) begin
            for (index = 0; index < POINTS; index = index + 1) begin
                status = $fscanf(vector_file, "%d %d %d %d\n",
                    input_low[index], input_high[index], expected_real[index],
                    expected_imag[index]);
                if (status != 4) $fatal(1, "invalid tangent FFT frame");
            end
            for (index = 0; index < POINTS; index = index + LANES) begin
                @(negedge clk);
                if (!pair_ready)
                    $fatal(1, "wide tangent FFT input backpressure mismatch");
                for (lane = 0; lane < LANES; lane = lane + 1) begin
                    coefficient_low[lane] = input_low[index + lane];
                    coefficient_high[lane] = input_high[index + lane];
                end
                pair_valid = 1'b1;
            end
            @(negedge clk);
            pair_valid = 1'b0;
            output_ready = 1'b1;
            wait (output_valid);
            for (index = 0; index < POINTS; index = index + LANES) begin
                #1;
                for (lane = 0; lane < LANES; lane = lane + 1) begin
                    if ($signed(output_real[lane]) != expected_real[index + lane])
                        $fatal(1, "wide tangent real mismatch frame=%0d index=%0d",
                               frame, index + lane);
                    if ($signed(output_imag[lane]) != expected_imag[index + lane])
                        $fatal(1, "wide tangent imag mismatch frame=%0d index=%0d",
                               frame, index + lane);
                end
                @(posedge clk);
            end
            #1;
            output_ready = 1'b0;
            if (!done) $fatal(1, "wide tangent FFT done pulse missing");
        end
        $fclose(vector_file);
        $display("PASS fpt_tangent_fft_wide_core frames=%0d polynomial_points=%0d lanes=%0d",
                 FRAMES, 2 * POINTS, LANES);
        $finish;
    end
endmodule

`default_nettype wire

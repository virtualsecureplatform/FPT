`timescale 1ns/1ps
`default_nettype none

module fpt_fft_core_tb;
    localparam int POINTS = 16;
    localparam int DATA_WIDTH = 30;
    localparam int TWIDDLE_WIDTH = 26;
    localparam int TWIDDLE_FRAC = 24;
    localparam int FRAMES = 16;

    logic clk = 1'b0;
    logic rst_n = 1'b0;
    logic input_valid = 1'b0;
    logic input_ready;
    logic signed [DATA_WIDTH-1:0] input_real;
    logic signed [DATA_WIDTH-1:0] input_imag;
    logic [$clog2(POINTS)-2:0] twiddle_index;
    logic signed [TWIDDLE_WIDTH-1:0] twiddle_c;
    logic signed [TWIDDLE_WIDTH-1:0] twiddle_c_minus_d;
    logic signed [TWIDDLE_WIDTH-1:0] twiddle_c_plus_d;
    logic output_valid;
    logic output_ready = 1'b0;
    logic signed [DATA_WIDTH-1:0] output_real;
    logic signed [DATA_WIDTH-1:0] output_imag;
    logic busy;
    logic done;

    logic signed [TWIDDLE_WIDTH-1:0] c_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] c_minus_d_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] c_plus_d_rom [0:POINTS/2-1];
    longint signed frame_input_real [0:POINTS-1];
    longint signed frame_input_imag [0:POINTS-1];
    longint signed frame_expected_real [0:POINTS-1];
    longint signed frame_expected_imag [0:POINTS-1];
    longint signed v_c;
    longint signed v_c_minus_d;
    longint signed v_c_plus_d;
    integer vector_file;
    integer twiddle_file;
    integer status;
    integer frame;
    integer index;

    always #5 clk = ~clk;
    always @* begin
        twiddle_c = c_rom[twiddle_index];
        twiddle_c_minus_d = c_minus_d_rom[twiddle_index];
        twiddle_c_plus_d = c_plus_d_rom[twiddle_index];
    end

    fpt_fft_core #(
        .POINTS(POINTS), .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH), .TWIDDLE_FRAC(TWIDDLE_FRAC),
        .SCALE_MASK('0)
    ) dut (
        .clk, .rst_n, .input_valid, .input_ready, .input_real, .input_imag,
        .twiddle_index, .twiddle_c, .twiddle_c_minus_d, .twiddle_c_plus_d,
        .output_valid, .output_ready, .output_real, .output_imag, .busy, .done
    );

    initial begin
        twiddle_file = $fopen("rtl_fft_twiddles.txt", "r");
        if (twiddle_file == 0) $fatal(1, "could not open FFT twiddles");
        for (index = 0; index < POINTS / 2; index = index + 1) begin
            status = $fscanf(twiddle_file, "%d %d %d\n",
                             v_c, v_c_minus_d, v_c_plus_d);
            if (status != 3) $fatal(1, "invalid FFT twiddle vector");
            c_rom[index] = v_c;
            c_minus_d_rom[index] = v_c_minus_d;
            c_plus_d_rom[index] = v_c_plus_d;
        end
        $fclose(twiddle_file);

        vector_file = $fopen("rtl_fft_vectors.txt", "r");
        if (vector_file == 0) $fatal(1, "could not open FFT vectors");
        repeat (2) @(posedge clk);
        rst_n = 1'b1;

        for (frame = 0; frame < FRAMES; frame = frame + 1) begin
            for (index = 0; index < POINTS; index = index + 1) begin
                status = $fscanf(vector_file, "%d %d %d %d\n",
                    frame_input_real[index], frame_input_imag[index],
                    frame_expected_real[index], frame_expected_imag[index]);
                if (status != 4) $fatal(1, "invalid FFT frame vector");
            end

            for (index = 0; index < POINTS; index = index + 1) begin
                @(negedge clk);
                if (!input_ready) $fatal(1, "FFT input backpressure mismatch");
                input_real = frame_input_real[index];
                input_imag = frame_input_imag[index];
                input_valid = 1'b1;
            end
            @(negedge clk);
            input_valid = 1'b0;
            output_ready = 1'b1;
            wait (output_valid);
            for (index = 0; index < POINTS; index = index + 1) begin
                #1;
                if ($signed(output_real) != frame_expected_real[index])
                    $fatal(1,
                        "FFT real mismatch frame=%0d index=%0d actual=%0d expected=%0d",
                        frame, index, $signed(output_real),
                        frame_expected_real[index]);
                if ($signed(output_imag) != frame_expected_imag[index])
                    $fatal(1,
                        "FFT imag mismatch frame=%0d index=%0d actual=%0d expected=%0d",
                        frame, index, $signed(output_imag),
                        frame_expected_imag[index]);
                @(posedge clk);
            end
            #1;
            output_ready = 1'b0;
            if (!done) $fatal(1, "FFT done pulse missing");
        end
        $fclose(vector_file);
        $display("PASS fpt_fft_core frames=%0d points=%0d", FRAMES, POINTS);
        $finish;
    end
endmodule

`default_nettype wire

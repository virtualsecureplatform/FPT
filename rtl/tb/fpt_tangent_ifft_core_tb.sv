`timescale 1ns/1ps
`default_nettype none

module fpt_tangent_ifft_core_tb;
    localparam int POINTS = 16;
    localparam int DATA_WIDTH = 30;
    localparam int TWIDDLE_WIDTH = 26;
    localparam int TWIDDLE_FRAC = 24;
    localparam int FRAMES = 8;

    logic clk = 1'b0;
    logic rst_n = 1'b0;
    logic input_valid = 1'b0;
    logic input_ready;
    logic signed [DATA_WIDTH-1:0] input_real;
    logic signed [DATA_WIDTH-1:0] input_imag;
    logic [$clog2(POINTS)-2:0] fft_twiddle_index;
    logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c;
    logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c_minus_d;
    logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c_plus_d;
    logic [$clog2(POINTS)-1:0] untwist_index;
    logic signed [TWIDDLE_WIDTH-1:0] untwist_c;
    logic signed [TWIDDLE_WIDTH-1:0] untwist_c_minus_d;
    logic signed [TWIDDLE_WIDTH-1:0] untwist_c_plus_d;
    logic output_valid;
    logic output_ready = 1'b0;
    logic signed [DATA_WIDTH-1:0] coefficient_low;
    logic signed [DATA_WIDTH-1:0] coefficient_high;
    logic busy;
    logic done;

    logic signed [TWIDDLE_WIDTH-1:0] fft_c_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] fft_c_minus_d_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] fft_c_plus_d_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] untwist_c_rom [0:POINTS-1];
    logic signed [TWIDDLE_WIDTH-1:0] untwist_c_minus_d_rom [0:POINTS-1];
    logic signed [TWIDDLE_WIDTH-1:0] untwist_c_plus_d_rom [0:POINTS-1];
    longint signed frame_input_real [0:POINTS-1];
    longint signed frame_input_imag [0:POINTS-1];
    longint signed frame_expected_low [0:POINTS-1];
    longint signed frame_expected_high [0:POINTS-1];
    longint signed v_c;
    longint signed v_c_minus_d;
    longint signed v_c_plus_d;
    integer vector_file;
    integer fft_twiddle_file;
    integer untwist_file;
    integer status;
    integer frame;
    integer index;

    always #5 clk = ~clk;
    always @* begin
        fft_twiddle_c = fft_c_rom[fft_twiddle_index];
        fft_twiddle_c_minus_d = fft_c_minus_d_rom[fft_twiddle_index];
        fft_twiddle_c_plus_d = fft_c_plus_d_rom[fft_twiddle_index];
        untwist_c = untwist_c_rom[untwist_index];
        untwist_c_minus_d = untwist_c_minus_d_rom[untwist_index];
        untwist_c_plus_d = untwist_c_plus_d_rom[untwist_index];
    end

    fpt_tangent_ifft_core #(
        .POINTS(POINTS), .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH), .TWIDDLE_FRAC(TWIDDLE_FRAC),
        .NORMALIZE_SHIFT($clog2(POINTS)), .SCALE_MASK('0)
    ) dut (
        .clk, .rst_n, .input_valid, .input_ready, .input_real, .input_imag,
        .fft_twiddle_index, .fft_twiddle_c, .fft_twiddle_c_minus_d,
        .fft_twiddle_c_plus_d, .untwist_index, .untwist_c,
        .untwist_c_minus_d, .untwist_c_plus_d, .output_valid, .output_ready,
        .coefficient_low, .coefficient_high, .busy, .done
    );

    task automatic read_twiddle(
        input integer file_handle, input integer destination_index,
        input bit untwist_destination);
        begin
            status = $fscanf(file_handle, "%d %d %d\n",
                             v_c, v_c_minus_d, v_c_plus_d);
            if (status != 3) $fatal(1, "invalid inverse FFT twiddle");
            if (untwist_destination) begin
                untwist_c_rom[destination_index] = v_c;
                untwist_c_minus_d_rom[destination_index] = v_c_minus_d;
                untwist_c_plus_d_rom[destination_index] = v_c_plus_d;
            end else begin
                fft_c_rom[destination_index] = v_c;
                fft_c_minus_d_rom[destination_index] = v_c_minus_d;
                fft_c_plus_d_rom[destination_index] = v_c_plus_d;
            end
        end
    endtask

    initial begin
        fft_twiddle_file = $fopen("rtl_ifft_twiddles.txt", "r");
        untwist_file = $fopen("rtl_untwist_twiddles.txt", "r");
        if (fft_twiddle_file == 0 || untwist_file == 0)
            $fatal(1, "could not open inverse tangent FFT twiddles");
        for (index = 0; index < POINTS / 2; index = index + 1)
            read_twiddle(fft_twiddle_file, index, 1'b0);
        for (index = 0; index < POINTS; index = index + 1)
            read_twiddle(untwist_file, index, 1'b1);
        $fclose(fft_twiddle_file);
        $fclose(untwist_file);

        vector_file = $fopen("rtl_ifft_vectors.txt", "r");
        if (vector_file == 0) $fatal(1, "could not open inverse FFT vectors");
        repeat (2) @(posedge clk);
        rst_n = 1'b1;

        for (frame = 0; frame < FRAMES; frame = frame + 1) begin
            for (index = 0; index < POINTS; index = index + 1) begin
                status = $fscanf(vector_file, "%d %d %d %d\n",
                    frame_input_real[index], frame_input_imag[index],
                    frame_expected_low[index], frame_expected_high[index]);
                if (status != 4) $fatal(1, "invalid inverse FFT vector");
            end
            for (index = 0; index < POINTS; index = index + 1) begin
                @(negedge clk);
                if (!input_ready) $fatal(1, "inverse input backpressure mismatch");
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
                if ($signed(coefficient_low) != frame_expected_low[index])
                    $fatal(1,
                        "inverse low mismatch frame=%0d index=%0d actual=%0d expected=%0d",
                        frame, index, $signed(coefficient_low),
                        frame_expected_low[index]);
                if ($signed(coefficient_high) != frame_expected_high[index])
                    $fatal(1,
                        "inverse high mismatch frame=%0d index=%0d actual=%0d expected=%0d",
                        frame, index, $signed(coefficient_high),
                        frame_expected_high[index]);
                @(posedge clk);
            end
            #1;
            output_ready = 1'b0;
            if (!done) $fatal(1, "inverse FFT done pulse missing");
        end
        $fclose(vector_file);
        $display("PASS fpt_tangent_ifft_core frames=%0d polynomial_points=%0d",
                 FRAMES, 2 * POINTS);
        $finish;
    end
endmodule

`default_nettype wire


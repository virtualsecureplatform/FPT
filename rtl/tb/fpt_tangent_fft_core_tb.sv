`timescale 1ns/1ps
`default_nettype none

module fpt_tangent_fft_core_tb;
    localparam int POINTS = 16;
    localparam int DATA_WIDTH = 30;
    localparam int TWIDDLE_WIDTH = 26;
    localparam int TWIDDLE_FRAC = 24;
    localparam int FRAMES = 8;

    logic clk = 1'b0;
    logic rst_n = 1'b0;
    logic pair_valid = 1'b0;
    logic pair_ready;
    logic signed [DATA_WIDTH-1:0] coefficient_low;
    logic signed [DATA_WIDTH-1:0] coefficient_high;
    logic [$clog2(POINTS)-1:0] twist_index;
    logic signed [TWIDDLE_WIDTH-1:0] twist_c;
    logic signed [TWIDDLE_WIDTH-1:0] twist_c_minus_d;
    logic signed [TWIDDLE_WIDTH-1:0] twist_c_plus_d;
    logic [$clog2(POINTS)-2:0] fft_twiddle_index;
    logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c;
    logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c_minus_d;
    logic signed [TWIDDLE_WIDTH-1:0] fft_twiddle_c_plus_d;
    logic output_valid;
    logic output_ready = 1'b0;
    logic signed [DATA_WIDTH-1:0] output_real;
    logic signed [DATA_WIDTH-1:0] output_imag;
    logic busy;
    logic done;

    logic signed [TWIDDLE_WIDTH-1:0] twist_c_rom [0:POINTS-1];
    logic signed [TWIDDLE_WIDTH-1:0] twist_c_minus_d_rom [0:POINTS-1];
    logic signed [TWIDDLE_WIDTH-1:0] twist_c_plus_d_rom [0:POINTS-1];
    logic signed [TWIDDLE_WIDTH-1:0] fft_c_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] fft_c_minus_d_rom [0:POINTS/2-1];
    logic signed [TWIDDLE_WIDTH-1:0] fft_c_plus_d_rom [0:POINTS/2-1];
    longint signed frame_low [0:POINTS-1];
    longint signed frame_high [0:POINTS-1];
    longint signed frame_expected_real [0:POINTS-1];
    longint signed frame_expected_imag [0:POINTS-1];
    longint signed v_c;
    longint signed v_c_minus_d;
    longint signed v_c_plus_d;
    integer vector_file;
    integer twist_file;
    integer fft_twiddle_file;
    integer status;
    integer frame;
    integer index;

    always #5 clk = ~clk;
    always @* begin
        twist_c = twist_c_rom[twist_index];
        twist_c_minus_d = twist_c_minus_d_rom[twist_index];
        twist_c_plus_d = twist_c_plus_d_rom[twist_index];
        fft_twiddle_c = fft_c_rom[fft_twiddle_index];
        fft_twiddle_c_minus_d = fft_c_minus_d_rom[fft_twiddle_index];
        fft_twiddle_c_plus_d = fft_c_plus_d_rom[fft_twiddle_index];
    end

    fpt_tangent_fft_core #(
        .POINTS(POINTS), .DATA_WIDTH(DATA_WIDTH),
        .TWIDDLE_WIDTH(TWIDDLE_WIDTH), .TWIDDLE_FRAC(TWIDDLE_FRAC),
        .SCALE_MASK('0)
    ) dut (
        .clk, .rst_n, .pair_valid, .pair_ready, .coefficient_low,
        .coefficient_high, .twist_index, .twist_c, .twist_c_minus_d,
        .twist_c_plus_d, .fft_twiddle_index, .fft_twiddle_c,
        .fft_twiddle_c_minus_d, .fft_twiddle_c_plus_d, .output_valid,
        .output_ready, .output_real, .output_imag, .busy, .done
    );

    task automatic read_twiddle(
        input integer file_handle, input integer destination_index,
        input bit twist_destination);
        begin
            status = $fscanf(file_handle, "%d %d %d\n",
                             v_c, v_c_minus_d, v_c_plus_d);
            if (status != 3) $fatal(1, "invalid tangent FFT twiddle");
            if (twist_destination) begin
                twist_c_rom[destination_index] = v_c;
                twist_c_minus_d_rom[destination_index] = v_c_minus_d;
                twist_c_plus_d_rom[destination_index] = v_c_plus_d;
            end else begin
                fft_c_rom[destination_index] = v_c;
                fft_c_minus_d_rom[destination_index] = v_c_minus_d;
                fft_c_plus_d_rom[destination_index] = v_c_plus_d;
            end
        end
    endtask

    initial begin
        twist_file = $fopen("rtl_tangent_twiddles.txt", "r");
        fft_twiddle_file = $fopen("rtl_fft_twiddles.txt", "r");
        if (twist_file == 0 || fft_twiddle_file == 0)
            $fatal(1, "could not open tangent FFT twiddles");
        for (index = 0; index < POINTS; index = index + 1)
            read_twiddle(twist_file, index, 1'b1);
        for (index = 0; index < POINTS / 2; index = index + 1)
            read_twiddle(fft_twiddle_file, index, 1'b0);
        $fclose(twist_file);
        $fclose(fft_twiddle_file);

        vector_file = $fopen("rtl_tangent_vectors.txt", "r");
        if (vector_file == 0) $fatal(1, "could not open tangent vectors");
        repeat (2) @(posedge clk);
        rst_n = 1'b1;

        for (frame = 0; frame < FRAMES; frame = frame + 1) begin
            for (index = 0; index < POINTS; index = index + 1) begin
                status = $fscanf(vector_file, "%d %d %d %d\n",
                    frame_low[index], frame_high[index],
                    frame_expected_real[index], frame_expected_imag[index]);
                if (status != 4) $fatal(1, "invalid tangent FFT vector");
            end
            for (index = 0; index < POINTS; index = index + 1) begin
                @(negedge clk);
                if (!pair_ready) $fatal(1, "tangent input backpressure mismatch");
                coefficient_low = frame_low[index];
                coefficient_high = frame_high[index];
                pair_valid = 1'b1;
            end
            @(negedge clk);
            pair_valid = 1'b0;
            output_ready = 1'b1;
            wait (output_valid);
            for (index = 0; index < POINTS; index = index + 1) begin
                #1;
                if ($signed(output_real) != frame_expected_real[index])
                    $fatal(1,
                        "tangent real mismatch frame=%0d index=%0d actual=%0d expected=%0d",
                        frame, index, $signed(output_real),
                        frame_expected_real[index]);
                if ($signed(output_imag) != frame_expected_imag[index])
                    $fatal(1,
                        "tangent imag mismatch frame=%0d index=%0d actual=%0d expected=%0d",
                        frame, index, $signed(output_imag),
                        frame_expected_imag[index]);
                @(posedge clk);
            end
            #1;
            output_ready = 1'b0;
            if (!done) $fatal(1, "tangent FFT done pulse missing");
        end
        $fclose(vector_file);
        $display("PASS fpt_tangent_fft_core frames=%0d polynomial_points=%0d",
                 FRAMES, 2 * POINTS);
        $finish;
    end
endmodule

`default_nettype wire


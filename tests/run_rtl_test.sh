set -euo pipefail

iverilog_bin=$1
source_dir=$2
build_dir=$3

cd "$build_dir"
"$iverilog_bin" -g2012 -s fpt_butterfly_tb -o fpt_butterfly_tb.vvp \
    "$source_dir/rtl/fpt_gauss_mul.sv" \
    "$source_dir/rtl/fpt_butterfly.sv" \
    "$source_dir/rtl/tb/fpt_butterfly_tb.sv"
vvp fpt_butterfly_tb.vvp

"$iverilog_bin" -g2012 -s fpt_complex_mac_tb -o fpt_complex_mac_tb.vvp \
    "$source_dir/rtl/fpt_complex_mac.sv" \
    "$source_dir/rtl/tb/fpt_complex_mac_tb.sv"
vvp fpt_complex_mac_tb.vvp

"$iverilog_bin" -g2012 -s fpt_complex_mac_wide_tb \
    -o fpt_complex_mac_wide_tb.vvp \
    "$source_dir/rtl/fpt_complex_mac.sv" \
    "$source_dir/rtl/fpt_complex_mac_wide.sv" \
    "$source_dir/rtl/tb/fpt_complex_mac_wide_tb.sv"
vvp fpt_complex_mac_wide_tb.vvp

"$iverilog_bin" -g2012 -s fpt_fft_core_tb -o fpt_fft_core_tb.vvp \
    "$source_dir/rtl/fpt_gauss_mul.sv" \
    "$source_dir/rtl/fpt_fft_core.sv" \
    "$source_dir/rtl/tb/fpt_fft_core_tb.sv"
vvp fpt_fft_core_tb.vvp

"$iverilog_bin" -g2012 -s fpt_fft_wide_core_tb -o fpt_fft_wide_core_tb.vvp \
    "$source_dir/rtl/fpt_gauss_mul.sv" \
    "$source_dir/rtl/fpt_fft_wide_core.sv" \
    "$source_dir/rtl/tb/fpt_fft_wide_core_tb.sv"
vvp fpt_fft_wide_core_tb.vvp

"$iverilog_bin" -g2012 -s fpt_tangent_fft_core_tb \
    -o fpt_tangent_fft_core_tb.vvp \
    "$source_dir/rtl/fpt_gauss_mul.sv" \
    "$source_dir/rtl/fpt_fft_core.sv" \
    "$source_dir/rtl/fpt_tangent_fft_core.sv" \
    "$source_dir/rtl/tb/fpt_tangent_fft_core_tb.sv"
vvp fpt_tangent_fft_core_tb.vvp

"$iverilog_bin" -g2012 -s fpt_tangent_fft_wide_core_tb \
    -o fpt_tangent_fft_wide_core_tb.vvp \
    "$source_dir/rtl/fpt_gauss_mul.sv" \
    "$source_dir/rtl/fpt_fft_wide_core.sv" \
    "$source_dir/rtl/fpt_tangent_fft_wide_core.sv" \
    "$source_dir/rtl/tb/fpt_tangent_fft_wide_core_tb.sv"
vvp fpt_tangent_fft_wide_core_tb.vvp

"$iverilog_bin" -g2012 -s fpt_tangent_ifft_core_tb \
    -o fpt_tangent_ifft_core_tb.vvp \
    "$source_dir/rtl/fpt_gauss_mul.sv" \
    "$source_dir/rtl/fpt_fft_core.sv" \
    "$source_dir/rtl/fpt_tangent_ifft_core.sv" \
    "$source_dir/rtl/tb/fpt_tangent_ifft_core_tb.sv"
vvp fpt_tangent_ifft_core_tb.vvp

"$iverilog_bin" -g2012 -s fpt_tangent_ifft_wide_core_tb \
    -o fpt_tangent_ifft_wide_core_tb.vvp \
    "$source_dir/rtl/fpt_gauss_mul.sv" \
    "$source_dir/rtl/fpt_fft_wide_core.sv" \
    "$source_dir/rtl/fpt_tangent_ifft_wide_core.sv" \
    "$source_dir/rtl/tb/fpt_tangent_ifft_wide_core_tb.sv"
vvp fpt_tangent_ifft_wide_core_tb.vvp

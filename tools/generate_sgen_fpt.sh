#!/usr/bin/env bash
set -euo pipefail

sgen_dir=${1:-../SGen}
output_dir=${2:-build/sgen-fpt}

fft_log_points=${FFT_LOG_POINTS:-9}
fft_log_lanes=${FFT_LOG_LANES:-7}
ifft_log_lanes=${IFFT_LOG_LANES:-6}
radix_log=${RADIX_LOG:-3}
fft_integer_bits=${FFT_INTEGER_BITS:-18}
fft_fractional_bits=${FFT_FRACTIONAL_BITS:-12}
ifft_integer_bits=${IFFT_INTEGER_BITS:-27}
ifft_fractional_bits=${IFFT_FRACTIONAL_BITS:-3}
ifft_stage_scale=${IFFT_STAGE_SCALE:-0.5}

if [[ ! -x "$sgen_dir/sgen.bat" ]]; then
    echo "SGen executable not found; run '(cd $sgen_dir && sbt assembly)'" >&2
    exit 1
fi
if ! rg -q 'val z = \(Re\(lhs\) - Im\(lhs\)\) \* Re\(rhs\)' \
    "$sgen_dir/src/main/scala/ir/rtl/hardwaretype/ComplexHW.scala"; then
    echo "Apply sgen/fpt-sgen.patch to $sgen_dir before generation" >&2
    exit 1
fi

mkdir -p "$output_dir"
"$sgen_dir/sgen.bat" -nologo \
    -n "$fft_log_points" -k "$fft_log_lanes" -r "$radix_log" \
    -hw complex fixedpoint "$fft_integer_bits" "$fft_fractional_bits" \
    -o "$output_dir/forward.v" dft
"$sgen_dir/sgen.bat" -nologo \
    -n "$fft_log_points" -k "$ifft_log_lanes" -r "$radix_log" \
    -sf "$ifft_stage_scale" \
    -hw complex fixedpoint "$ifft_integer_bits" "$ifft_fractional_bits" \
    -o "$output_dir/inverse.v" idft

echo "Generated FPT-adapted SGen transforms in $output_dir"

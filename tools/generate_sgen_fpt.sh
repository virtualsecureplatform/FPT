#!/usr/bin/env bash
set -euo pipefail

sgen_dir=${1:-../SGen}
output_dir=${2:-build/sgen-fpt}
forward_module=${FORWARD_MODULE:-FptSGenForward}
inverse_module=${INVERSE_MODULE:-FptSGenInverse}

if [[ ! "$forward_module" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] ||
   [[ ! "$inverse_module" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "Generated module names must be Verilog identifiers" >&2
    exit 1
fi

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
    echo "Use the virtualsecureplatform/SGen fpt branch for generation" >&2
    exit 1
fi

mkdir -p "$output_dir"
"$sgen_dir/sgen.bat" -nologo \
    -n "$fft_log_points" -k "$fft_log_lanes" -r "$radix_log" \
    -hw complex fixedpoint "$fft_integer_bits" "$fft_fractional_bits" \
    -o "$output_dir/forward.raw.v" dft
"$sgen_dir/sgen.bat" -nologo \
    -n "$fft_log_points" -k "$ifft_log_lanes" -r "$radix_log" \
    -sf "$ifft_stage_scale" \
    -hw complex fixedpoint "$ifft_integer_bits" "$ifft_fractional_bits" \
    -o "$output_dir/inverse.raw.v" idft

sed "0,/module main(/s//module $forward_module(/" \
    "$output_dir/forward.raw.v" > "$output_dir/forward.v"
sed "0,/module main(/s//module $inverse_module(/" \
    "$output_dir/inverse.raw.v" > "$output_dir/inverse.v"
rm -f "$output_dir/forward.raw.v" "$output_dir/inverse.raw.v"

echo "Generated FPT-adapted SGen transforms in $output_dir"

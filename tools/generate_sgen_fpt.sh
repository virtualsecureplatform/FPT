#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
sgen_dir=${1:-$repo_root/third_party/SGen}
output_dir=${2:-$repo_root/build/sgen-fpt}
# shellcheck source=tools/fpt_arithmetic_profile_contract.sh
source "$repo_root/tools/fpt_arithmetic_profile_contract.sh"
forward_module=${FORWARD_MODULE:-FptSGenForward}
inverse_module=${INVERSE_MODULE:-FptSGenInverse}
arithmetic_profile=${FPT_ARITHMETIC_PROFILE:-custom}
sgen_ram_style=${FPT_SGEN_RAM_STYLE:-block}

case "$sgen_ram_style" in
    auto|block|distributed) ;;
    *)
        echo "FPT_SGEN_RAM_STYLE must be auto, block, or distributed" >&2
        exit 1
        ;;
esac

if [[ ! "$forward_module" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] ||
   [[ ! "$inverse_module" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "Generated module names must be Verilog identifiers" >&2
    exit 1
fi

fft_log_points=${FFT_LOG_POINTS:-9}
fft_log_lanes=${FFT_LOG_LANES:-7}
ifft_log_lanes=${IFFT_LOG_LANES:-6}
radix_log=${RADIX_LOG:-3}
ifft_radix_log=${IFFT_RADIX_LOG:-1}
case "$arithmetic_profile" in
    custom)
        default_fft_integer_bits=18
        default_fft_fractional_bits=12
        default_ifft_integer_bits=27
        default_ifft_fractional_bits=3
        ;;
    paper-set-ii|tfhepp-hardware)
        fpt_resolve_arithmetic_profile "$arithmetic_profile"
        default_fft_integer_bits=$FPT_PROFILE_FFT_INTEGER_BITS
        default_fft_fractional_bits=$FPT_PROFILE_FFT_FRACTIONAL_BITS
        default_ifft_integer_bits=$FPT_PROFILE_IFFT_INTEGER_BITS
        default_ifft_fractional_bits=$FPT_PROFILE_IFFT_FRACTIONAL_BITS
        ;;
    *)
        echo "Unknown FPT_ARITHMETIC_PROFILE: $arithmetic_profile" >&2
        exit 1
        ;;
esac
fft_integer_bits=${FFT_INTEGER_BITS:-$default_fft_integer_bits}
fft_fractional_bits=${FFT_FRACTIONAL_BITS:-$default_fft_fractional_bits}
ifft_integer_bits=${IFFT_INTEGER_BITS:-$default_ifft_integer_bits}
ifft_fractional_bits=${IFFT_FRACTIONAL_BITS:-$default_ifft_fractional_bits}
if [[ $arithmetic_profile != custom ]] &&
   [[ $fft_integer_bits != "$default_fft_integer_bits" ||
      $fft_fractional_bits != "$default_fft_fractional_bits" ||
      $ifft_integer_bits != "$default_ifft_integer_bits" ||
      $ifft_fractional_bits != "$default_ifft_fractional_bits" ]]; then
    echo "Explicit FFT widths conflict with $arithmetic_profile" >&2
    exit 1
fi
ifft_stage_scale=${IFFT_STAGE_SCALE:-0.5}
integrated_tangent=${INTEGRATED_TANGENT:-1}
switch_transpose=${FPT_SGEN_SWITCH_TRANSPOSE:-0}
forward_switch_transpose=${FPT_SGEN_FORWARD_SWITCH_TRANSPOSE:-$switch_transpose}
inverse_switch_transpose=${FPT_SGEN_INVERSE_SWITCH_TRANSPOSE:-$switch_transpose}
transform_flags=()

case "$integrated_tangent:$forward_switch_transpose" in
    1:0)
        forward_transform=fptdft
        ;;
    1:1)
        forward_transform=fptdftswitch
        ;;
    0:0)
        forward_transform=dft
        ;;
    0:1)
        echo "FPT_SGEN_FORWARD_SWITCH_TRANSPOSE requires INTEGRATED_TANGENT=1" >&2
        exit 1
        ;;
    *)
        echo "INTEGRATED_TANGENT and FPT_SGEN_FORWARD_SWITCH_TRANSPOSE must be 0 or 1" >&2
        exit 1
        ;;
esac
case "$integrated_tangent:$inverse_switch_transpose" in
    1:0)
        inverse_transform=fptidft
        ;;
    1:1)
        inverse_transform=fptidftswitch
        ;;
    0:0)
        inverse_transform=idft
        ;;
    0:1)
        echo "FPT_SGEN_INVERSE_SWITCH_TRANSPOSE requires INTEGRATED_TANGENT=1" >&2
        exit 1
        ;;
    *)
        echo "INTEGRATED_TANGENT and FPT_SGEN_INVERSE_SWITCH_TRANSPOSE must be 0 or 1" >&2
        exit 1
        ;;
esac

if [[ ! -x "$sgen_dir/sgen.bat" ]]; then
    echo "SGen executable not found; run '(cd $sgen_dir && sbt assembly)'" >&2
    exit 1
fi
if ! rg -q 'val z = \(Re\(lhs\) - Im\(lhs\)\) \* Re\(rhs\)' \
    "$sgen_dir/src/main/scala/ir/rtl/hardwaretype/ComplexHW.scala"; then
    echo "Use the virtualsecureplatform/SGen fpt branch for generation" >&2
    exit 1
fi
if ! rg -q 'Seq\.fill\(rightShift\)\(sign\)' \
    "$sgen_dir/src/main/scala/ir/rtl/hardwaretype/FixedPoint.scala"; then
    echo "SGen is missing signed fractional power-of-two stage scaling" >&2
    exit 1
fi
mkdir -p "$output_dir"
SGEN_RAM_STYLE="$sgen_ram_style" \
    "$sgen_dir/sgen.bat" -nologo \
    -n "$fft_log_points" -k "$fft_log_lanes" -r "$radix_log" \
    "${transform_flags[@]}" \
    -hw complex fixedpoint "$fft_integer_bits" "$fft_fractional_bits" \
    -o "$output_dir/forward.raw.v" "$forward_transform"
SGEN_RAM_STYLE="$sgen_ram_style" \
    "$sgen_dir/sgen.bat" -nologo \
    -n "$fft_log_points" -k "$ifft_log_lanes" -r "$ifft_radix_log" \
    "${transform_flags[@]}" \
    -sf "$ifft_stage_scale" \
    -hw complex fixedpoint "$ifft_integer_bits" "$ifft_fractional_bits" \
    -o "$output_dir/inverse.raw.v" "$inverse_transform"

sed \
    -e "s/SGenSwitchTranspose/${forward_module}SwitchTranspose/g" \
    -e "s/mainSquareSwitch/${forward_module}SquareSwitch/g" \
    -e "s/mainTwist/${forward_module}Twist/g" \
    -e "s/mainForwardCore/${forward_module}ForwardCore/g" \
    -e "0,/module main(/s//module $forward_module(/" \
    "$output_dir/forward.raw.v" > "$output_dir/forward.v"
sed \
    -e "s/SGenSwitchTranspose/${inverse_module}SwitchTranspose/g" \
    -e "s/mainSquareSwitch/${inverse_module}SquareSwitch/g" \
    -e "s/mainTwist/${inverse_module}Twist/g" \
    -e "s/mainInverseCore/${inverse_module}InverseCore/g" \
    -e "0,/module main(/s//module $inverse_module(/" \
    "$output_dir/inverse.raw.v" > "$output_dir/inverse.v"
rm -f "$output_dir/forward.raw.v" "$output_dir/inverse.raw.v"

printf 'Generated FPT-adapted SGen transforms in %s (integrated tangent: %s, forward switch transpose: %s, inverse switch transpose: %s, profile: %s, RAM style: %s)\n' \
    "$output_dir" "$integrated_tangent" "$forward_switch_transpose" \
    "$inverse_switch_transpose" "$arithmetic_profile" "$sgen_ram_style"

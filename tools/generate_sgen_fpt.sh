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
forward_partition=${FPT_SGEN_FORWARD_PARTITION:-none}
forward_boundary_registers=${FPT_SGEN_FORWARD_BOUNDARY_REGISTERS:-2}
preserve_partition_registers=${FPT_SGEN_PRESERVE_PARTITION_REGISTERS:-0}
if [[ $preserve_partition_registers != 0 && $preserve_partition_registers != 1 ]]; then
    echo "FPT_SGEN_PRESERVE_PARTITION_REGISTERS must be 0 or 1" >&2
    exit 1
fi
if [[ $preserve_partition_registers == 1 && $forward_partition == none ]]; then
    echo "preserved partition registers require a forward partition" >&2
    exit 1
fi
forward_permutation_arch=${FPT_SGEN_FORWARD_PERMUTATION_ARCH:-legacy}
frame_control=${FPT_SGEN_FRAME_CONTROL:-legacy}
case "$frame_control" in
    legacy|token) ;;
    *) echo "FPT_SGEN_FRAME_CONTROL must be legacy or token" >&2; exit 1 ;;
esac
case "$forward_permutation_arch" in
    legacy) ;;
    banked_tiles|banked_local_control|commutator_tiles)
        if [[ $forward_partition != stage2spill ]]; then
            echo "$forward_permutation_arch requires FPT_SGEN_FORWARD_PARTITION=stage2spill" >&2
            exit 1
        fi ;;
    *) echo "FPT_SGEN_FORWARD_PERMUTATION_ARCH must be legacy or banked_tiles or banked_local_control or commutator_tiles" >&2; exit 1 ;;
esac
forward_precompute_rom=${FPT_SGEN_FORWARD_PRECOMPUTE_ROM_ADD_SUB:-0}
forward_twiddle_consumers=${FPT_SGEN_FORWARD_TWIDDLE_ISLAND_CONSUMERS:-0}
case "$forward_twiddle_consumers" in
    0|4) ;;
    *) echo "FPT_SGEN_FORWARD_TWIDDLE_ISLAND_CONSUMERS must be 0 or 4" >&2; exit 1 ;;
esac
if [[ $forward_twiddle_consumers != 0 && $forward_partition != stage2spill ]]; then
    echo "local forward twiddles require stage2spill" >&2; exit 1
fi
forward_mux_control_max_bits=${FPT_SGEN_FORWARD_MUX_CONTROL_MAX_BITS:-0}
inverse_mux_control_max_bits=${FPT_SGEN_INVERSE_MUX_CONTROL_MAX_BITS:-0}
inverse_mux_island_bits=${FPT_SGEN_INVERSE_MUX_ISLAND_BITS:-0}
case "$inverse_mux_island_bits" in
    0|120) ;;
    *) echo "FPT_SGEN_INVERSE_MUX_ISLAND_BITS must be 0 or 120" >&2; exit 1 ;;
esac
if [[ ! $inverse_mux_control_max_bits =~ ^[0-9]+$ ]] || (( inverse_mux_control_max_bits > 2147483647 )); then
    echo "FPT_SGEN_INVERSE_MUX_CONTROL_MAX_BITS must be a nonnegative 32-bit integer" >&2
    exit 1
fi
if [[ ! $forward_mux_control_max_bits =~ ^[0-9]+$ ]] || (( forward_mux_control_max_bits > 2147483647 )); then
    echo "FPT_SGEN_FORWARD_MUX_CONTROL_MAX_BITS must be a nonnegative 32-bit integer" >&2
    exit 1
fi
if [[ $forward_precompute_rom != 0 && $forward_precompute_rom != 1 ]]; then
    echo "FPT_SGEN_FORWARD_PRECOMPUTE_ROM_ADD_SUB must be 0 or 1" >&2
    exit 1
fi

case "$sgen_ram_style" in
    auto|hybrid|block|distributed) ;;
    *)
        echo "FPT_SGEN_RAM_STYLE must be auto, hybrid, block, or distributed" >&2
        exit 1
        ;;
esac
case "$forward_partition" in
    none|stage1|stage2|stage2spill) ;;
    *)
        echo "FPT_SGEN_FORWARD_PARTITION must be none, stage1, stage2, or stage2spill" >&2
        exit 1
        ;;
esac
if [[ $forward_boundary_registers != 2 ]]; then
    echo "FPT_SGEN_FORWARD_BOUNDARY_REGISTERS must be 2" >&2
    exit 1
fi

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
# In the FPT integration, the historical SWITCH_TRANSPOSE knobs mean the
# radix-2^k feedforward MDC architecture. Keep them as compatibility aliases,
# but do not map them to SGen's unrelated lane/time transpose backend.
forward_radix2k_mdc=${FPT_SGEN_FORWARD_RADIX2K_MDC:-$forward_switch_transpose}
inverse_radix2k_mdc=${FPT_SGEN_INVERSE_RADIX2K_MDC:-$inverse_switch_transpose}
transform_flags=()

case "$integrated_tangent:$forward_radix2k_mdc" in
    1:0)
        forward_transform=fptdft
        ;;
    1:1)
        forward_transform=fptradix2kdft
        ;;
    0:0)
        forward_transform=dft
        ;;
    0:1)
        echo "FPT_SGEN_FORWARD_RADIX2K_MDC requires INTEGRATED_TANGENT=1" >&2
        exit 1
        ;;
    *)
        echo "INTEGRATED_TANGENT and FPT_SGEN_FORWARD_RADIX2K_MDC must be 0 or 1" >&2
        exit 1
        ;;
esac
if [[ $forward_permutation_arch != legacy ]] &&
   [[ $forward_transform != fptradix2kdft || $fft_log_points != 9 || $fft_log_lanes != 7 || $radix_log != 3 ]]; then
    echo "$forward_permutation_arch requires the 512-point, 128-lane radix-8 integrated forward transform" >&2
    exit 1
fi
case "$integrated_tangent:$inverse_radix2k_mdc" in
    1:0)
        inverse_transform=fptidft
        ;;
    1:1)
        inverse_transform=fptradix2kidft
        ;;
    0:0)
        inverse_transform=idft
        ;;
    0:1)
        echo "FPT_SGEN_INVERSE_RADIX2K_MDC requires INTEGRATED_TANGENT=1" >&2
        exit 1
        ;;
    *)
        echo "INTEGRATED_TANGENT and FPT_SGEN_INVERSE_RADIX2K_MDC must be 0 or 1" >&2
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
if [[ $frame_control == token ]] && [[ $forward_permutation_arch != commutator_tiles || $forward_partition != stage2spill || $fft_log_points != 9 || $fft_log_lanes != 7 ]]; then
    echo "token frame control requires the U280 512-point, 128-lane stage2spill commutator profile" >&2
    exit 1
fi
SGEN_RAM_STYLE="$sgen_ram_style" \
    SGEN_FPT_FORWARD_TWIDDLE_ISLAND_CONSUMERS="$forward_twiddle_consumers" \
    SGEN_FPT_FRAME_CONTROL="$frame_control" \
    SGEN_PRECOMPUTE_ROM_ADD_SUB="$forward_precompute_rom" \
    SGEN_MUX_CONTROL_MAX_BITS="$forward_mux_control_max_bits" \
    SGEN_MUX_ISLAND_BITS=0 \
    SGEN_FPT_FORWARD_PERMUTATION_ARCH="$forward_permutation_arch" \
    SGEN_FPT_FORWARD_PARTITION="$forward_partition" \
    SGEN_FPT_BOUNDARY_REGISTERS="$forward_boundary_registers" \
    SGEN_FPT_PRESERVE_PARTITION_REGISTERS="$preserve_partition_registers" \
    "$sgen_dir/sgen.bat" -nologo \
    -n "$fft_log_points" -k "$fft_log_lanes" -r "$radix_log" \
    "${transform_flags[@]}" \
    -hw complex fixedpoint "$fft_integer_bits" "$fft_fractional_bits" \
    -o "$output_dir/forward.raw.v" "$forward_transform"
SGEN_RAM_STYLE="$sgen_ram_style" \
    SGEN_FPT_FORWARD_TWIDDLE_ISLAND_CONSUMERS=0 \
    SGEN_FPT_FRAME_CONTROL="$frame_control" \
    SGEN_PRECOMPUTE_ROM_ADD_SUB=0 \
    SGEN_MUX_CONTROL_MAX_BITS="$inverse_mux_control_max_bits" \
    SGEN_MUX_ISLAND_BITS="$inverse_mux_island_bits" \
    SGEN_FPT_FORWARD_PERMUTATION_ARCH=legacy \
    "$sgen_dir/sgen.bat" -nologo \
    -n "$fft_log_points" -k "$ifft_log_lanes" -r "$ifft_radix_log" \
    "${transform_flags[@]}" \
    -sf "$ifft_stage_scale" \
    -hw complex fixedpoint "$ifft_integer_bits" "$ifft_fractional_bits" \
    -o "$output_dir/inverse.raw.v" "$inverse_transform"

sed \
    -e "s/mainFront/${forward_module}Front/g" \
    -e "s/mainBoundary/${forward_module}Boundary/g" \
    -e "s/mainBack/${forward_module}Back/g" \
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
sed -n 's@^[[:space:]]*// BANKED_@@p' "$output_dir/forward.v" > "$output_dir/banked_permutation_manifest.txt"
sed -n 's@^[[:space:]]*// COMMUTATOR_@@p' "$output_dir/forward.v" > "$output_dir/commutator_permutation_manifest.txt"

{
    printf 'frame_control\t%s\n' "$frame_control"
    printf 'forward_precompute_rom_add_sub\t%s\n' "$forward_precompute_rom"
    printf 'forward_twiddle_island_consumers\t%s\n' "$forward_twiddle_consumers"
    printf 'forward_mux_control_max_bits\t%s\n' "$forward_mux_control_max_bits"
    printf 'inverse_mux_control_max_bits\t%s\n' "$inverse_mux_control_max_bits"
    printf 'inverse_mux_island_bits\t%s\n' "$inverse_mux_island_bits"
    printf 'forward_permutation_arch\t%s\n' "$forward_permutation_arch"
    printf 'preserve_partition_registers\t%s\n' "$preserve_partition_registers"
    printf 'sgen_commit\t%s\n' "$(git -C "$sgen_dir" rev-parse HEAD)"
    printf 'sgen_tracked_diff_sha256\t'
    git -C "$sgen_dir" diff HEAD -- src/main | sha256sum
    (cd "$sgen_dir"; rg --files src/main | LC_ALL=C sort | xargs -d '\n' sha256sum)
    sha256sum "$sgen_dir/sgen.bat" "${BASH_SOURCE[0]}" \
        "$output_dir/forward.v" "$output_dir/inverse.v"
} > "$output_dir/generation_manifest.txt"

printf 'Generated FPT-adapted SGen transforms in %s (integrated tangent: %s, forward radix-2^k MDC: %s, inverse radix-2^k MDC: %s, forward partition: %s, profile: %s, RAM style: %s, precomputed ROM arithmetic: %s)\n' \
    "$output_dir" "$integrated_tangent" "$forward_radix2k_mdc" \
    "$inverse_radix2k_mdc" "$forward_partition" "$arithmetic_profile" "$sgen_ram_style" "$forward_precompute_rom"

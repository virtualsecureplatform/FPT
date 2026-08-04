#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=tools/fpt_arithmetic_profile_contract.sh
source "$repo_root/tools/fpt_arithmetic_profile_contract.sh"
forward=${1:-$repo_root/build/sgen-fpt/forward.v}
inverse=${2:-$repo_root/build/sgen-fpt/inverse.v}
output_dir=${3:-$repo_root/build/chisel-paper-buffered-barrel-blind-rotate}
domain_dimension=${FPT_BLIND_ROTATE_DIMENSION:-630}
arithmetic_profile=${FPT_ARITHMETIC_PROFILE:-paper-set-ii}
heap_size=${FPT_CHISEL_HEAP:-12G}
top=BufferedBlindRotateAccelerator

if [[ $arithmetic_profile != paper-set-ii ]]; then
    echo "The U280 windowed-barrel accelerator currently supports paper-set-ii only" >&2
    exit 1
fi
if [[ ! $domain_dimension =~ ^[1-9][0-9]*$ ]]; then
    echo "FPT_BLIND_ROTATE_DIMENSION must be a positive integer" >&2
    exit 1
fi

forward=$(realpath "$forward")
inverse=$(realpath "$inverse")
output_dir=$(realpath -m "$output_dir")
output_file=$output_dir/BufferedBlindRotateAccelerator.sv
if ! rg -q '^module FptSGenForward\(' "$forward"; then
    echo "FptSGenForward not found in $forward" >&2
    exit 1
fi
if ! rg -q '^module FptSGenInverse\(' "$inverse"; then
    echo "FptSGenInverse not found in $inverse" >&2
    exit 1
fi
fpt_check_sgen_arithmetic_profile \
    "$arithmetic_profile" "$forward" "$inverse"

rm -f "$output_file"
(cd "$repo_root/chisel" &&
    sbt -J-Xmx"$heap_size" \
        "runMain fpt.EmitPaperBufferedBarrelBlindRotateAccelerator $output_dir $forward $inverse $domain_dimension")

if [[ ! -s $output_file ]]; then
    echo "Chisel did not emit $output_file" >&2
    exit 1
fi
for module in "$top" PipelinedWindowedNegacyclicRotatorSpan \
    BootstrappingKeyPingPongBuffer; do
    if ! rg -q "^module $module\\(" "$output_file"; then
        echo "Emitted source is missing $module" >&2
        exit 1
    fi
done
if ! rg -q \
    '^module FptPipelinedExactSchoolbookComplexMultiply[[:space:]]*#\(' \
    "$output_file"; then
    echo "Emitted source is missing FptPipelinedExactSchoolbookComplexMultiply" >&2
    exit 1
fi
if ! rg -q '\(\* DONT_TOUCH = "yes" \*\) reg' "$output_file"; then
    echo "Emitted source is missing the preserved physical-cut register" >&2
    exit 1
fi
if rg -q '^module NegacyclicBarrelRotator\(' "$output_file"; then
    echo "Emitted source unexpectedly contains the full-width barrel rotator" >&2
    exit 1
fi
if [[ $(rg -c 'ram_style = "block"' "$output_file") != 2 ]]; then
    echo "Emitted source does not tag exactly the key and digit memories as block RAM" >&2
    exit 1
fi
if [[ $(rg -c 'ram_style = "ultra"' "$output_file") != 1 ]]; then
    echo "Emitted source does not tag exactly one External Product memory as UltraRAM" >&2
    exit 1
fi
if rg -q 'firrtl_black_box_resource_files[.]f' "$output_file"; then
    echo "Emitted source retains CIRCT's non-Verilog resource trailer" >&2
    exit 1
fi

if command -v verilator >/dev/null && [[ ${FPT_SKIP_LINT:-0} != 1 ]]; then
    verilator --lint-only -Wno-fatal \
        --top-module "$top" \
        "$output_file" "$forward" "$inverse"
fi

echo "Emitted U280 windowed-barrel Blind Rotate accelerator in $output_file"

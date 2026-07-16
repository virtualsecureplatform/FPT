#!/usr/bin/env bash
set -euo pipefail

script_path=$(realpath "${BASH_SOURCE[0]}")
repo_root=$(cd "$(dirname "$script_path")/.." && pwd)
accelerator=${FPT_BUFFERED_ACCELERATOR:-0}
case "$accelerator" in
    0)
        default_source_dir=$repo_root/build/chisel-paper-buffered-blind-rotate-sample-extract
        default_build_dir=$repo_root/build/fpt-buffered-blind-rotate-sim
        source_name=BufferedBatchedBlindRotateSampleExtractEngine.sv
        top=BufferedBatchedBlindRotateSampleExtractEngine
        ;;
    1)
        default_source_dir=$repo_root/build/chisel-paper-buffered-blind-rotate-accelerator
        default_build_dir=$repo_root/build/fpt-buffered-blind-rotate-accelerator-sim
        source_name=BufferedBlindRotateAccelerator.sv
        top=BufferedBlindRotateAccelerator
        ;;
    *)
        echo "FPT_BUFFERED_ACCELERATOR must be 0 or 1" >&2
        exit 1
        ;;
esac

source_dir=${1:-$default_source_dir}
sgen_dir=${2:-$repo_root/build/sgen-fpt}
build_dir=${3:-$default_build_dir}
source_file=$source_dir/$source_name
forward=$sgen_dir/forward.v
inverse=$sgen_dir/inverse.v
jobs=${FPT_VERILATOR_JOBS:-2}
split=${FPT_VERILATOR_SPLIT:-20000}

if ! command -v verilator >/dev/null; then
    echo "verilator is required for the buffered FPT schedule measurement" >&2
    exit 1
fi
if [[ ! $jobs =~ ^[1-9][0-9]*$ ]]; then
    echo "FPT_VERILATOR_JOBS must be a positive integer" >&2
    exit 1
fi
if [[ ! $split =~ ^[1-9][0-9]*$ ]]; then
    echo "FPT_VERILATOR_SPLIT must be a positive integer" >&2
    exit 1
fi
for input_file in "$source_file" "$forward" "$inverse"; do
    if [[ ! -s $input_file ]]; then
        echo "Missing buffered FPT schedule source: $input_file" >&2
        exit 1
    fi
done

source_file=$(realpath "$source_file")
forward=$(realpath "$forward")
inverse=$(realpath "$inverse")
build_dir=$(realpath -m "$build_dir")
object_dir=$build_dir/obj
mkdir -p "$object_dir"

signature=$(
    {
        for signature_input in \
            "$source_file" "$forward" "$inverse" \
            "$repo_root/tests/fpt_buffered_blind_rotate_schedule.cpp" \
            "$script_path"; do
            sha256sum "$signature_input" | awk '{ print $1 }'
        done
        printf '%s\n' "$(verilator --version)" "$top" "$jobs" "$split"
    } | sha256sum | awk '{ print $1 }'
)
signature_file=$build_dir/input.sha256
executable=$object_dir/V$top
if [[ -x $executable && -s $signature_file && \
      $(<"$signature_file") == "$signature" ]]; then
    echo "Reusing the matching buffered FPT Verilator model in $build_dir" >&2
    "$executable" | tee "$build_dir/schedule.txt"
    exit 0
fi

verilator --cc --exe -O3 -Wno-fatal \
    --output-split "$split" --output-split-cfuncs "$split" \
    --top-module "$top" --Mdir "$object_dir" \
    "$source_file" "$forward" "$inverse" \
    "$repo_root/tests/fpt_buffered_blind_rotate_schedule.cpp"

header=$object_dir/V$top.h
zero_include=$object_dir/fpt_buffered_zero_inputs.inc
sed -n \
    's/.*VL_IN[^&]*&\([A-Za-z0-9_]*\).*/    dut.\1 = 0;/p' \
    "$header" > "$zero_include"
input_ports=$(wc -l < "$zero_include")
if (( input_ports < 30 )); then
    echo "Parsed only $input_ports buffered FPT input ports from $header" >&2
    exit 1
fi

make -C "$object_dir" -f "V$top.mk" -j "$jobs"
printf '%s\n' "$signature" > "$signature_file"
"$executable" | tee "$build_dir/schedule.txt"

#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
forward=${1:-$repo_root/build/sgen-fpt/forward.v}
inverse=${2:-$repo_root/build/sgen-fpt/inverse.v}
output_dir=${3:-$repo_root/build/chisel-paper-blind-rotate}
domain_dimension=${FPT_BLIND_ROTATE_DIMENSION:-630}

forward=$(realpath "$forward")
inverse=$(realpath "$inverse")
output_dir=$(realpath -m "$output_dir")
output_file=$output_dir/BatchedBlindRotateEngine.sv
heap_size=${FPT_CHISEL_HEAP:-12G}

if [[ ! $domain_dimension =~ ^[1-9][0-9]*$ ]]; then
    echo "FPT_BLIND_ROTATE_DIMENSION must be a positive integer" >&2
    exit 1
fi
if ! rg -q '^module FptSGenForward\(' "$forward"; then
    echo "FptSGenForward not found in $forward" >&2
    exit 1
fi
if ! rg -q '^module FptSGenInverse\(' "$inverse"; then
    echo "FptSGenInverse not found in $inverse" >&2
    exit 1
fi

rm -f "$output_file"
(cd "$repo_root/chisel" &&
    sbt -J-Xmx"$heap_size" \
        "runMain fpt.EmitPaperBatchedBlindRotate $output_dir $forward $inverse $domain_dimension")

if [[ ! -s $output_file ]]; then
    echo "Chisel did not emit $output_file" >&2
    exit 1
fi

if command -v verilator >/dev/null && [[ ${FPT_SKIP_LINT:-0} != 1 ]]; then
    verilator --lint-only -Wno-fatal \
        --top-module BatchedBlindRotateEngine \
        "$output_file" "$forward" "$inverse"
fi

echo "Emitted paper-shaped batched Blind Rotate in $output_file"

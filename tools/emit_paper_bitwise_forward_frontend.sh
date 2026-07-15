#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_dir=${1:-$repo_root/build/chisel-paper-bitwise-forward}
output_dir=$(realpath -m "$output_dir")
output_file=$output_dir/BitwiseCmuxForwardFrontend.sv
heap_size=${FPT_CHISEL_HEAP:-8G}

# Chisel can report a background-thread heap failure without making runMain
# fail. Removing the previous artifact makes the postcondition authoritative.
rm -f "$output_file"

(cd "$repo_root/chisel" &&
    sbt -J-Xmx"$heap_size" \
        "runMain fpt.EmitPaperBitwiseForwardFrontend $output_dir")

if [[ ! -s $output_file ]]; then
    echo "Chisel did not emit $output_file" >&2
    exit 1
fi

if command -v verilator >/dev/null && [[ ${FPT_SKIP_LINT:-0} != 1 ]]; then
    verilator --lint-only -Wno-fatal \
        --top-module BitwiseCmuxForwardFrontend \
        "$output_file"
fi

echo "Emitted folded paper bitwise frontend in $output_file"

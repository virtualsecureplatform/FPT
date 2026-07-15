#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_dir=${1:-$repo_root/build/chisel-paper-bitwise}
output_dir=$(realpath -m "$output_dir")

(cd "$repo_root/chisel" &&
    sbt "runMain fpt.EmitPaperBitwiseReorder $output_dir")

if command -v verilator >/dev/null && [[ ${FPT_SKIP_LINT:-0} != 1 ]]; then
    verilator --lint-only -Wno-fatal --top-module BitwiseNegacyclicReorder \
        "$output_dir/BitwiseNegacyclicReorder.sv"
fi

echo "Emitted paper-shaped bitwise reorder in $output_dir/BitwiseNegacyclicReorder.sv"

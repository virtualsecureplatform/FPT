#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_dir=${1:-$repo_root/build/chisel-paper-bitwise-frontend}
output_dir=$(realpath -m "$output_dir")

(cd "$repo_root/chisel" &&
    sbt "runMain fpt.EmitPaperBitwiseDecomposition $output_dir")

if command -v verilator >/dev/null && [[ ${FPT_SKIP_LINT:-0} != 1 ]]; then
    verilator --lint-only -Wno-fatal \
        --top-module BitwiseCmuxDecompositionFrontend \
        "$output_dir/BitwiseCmuxDecompositionFrontend.sv"
fi

echo "Emitted paper-shaped bitwise CMUX frontend in $output_dir/BitwiseCmuxDecompositionFrontend.sv"

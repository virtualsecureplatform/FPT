#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_dir=${1:-$repo_root/build/chisel-paper-key-buffer}
output_dir=$(realpath -m "$output_dir")
output_file=$output_dir/BootstrappingKeyPingPongBuffer.sv
heap_size=${FPT_CHISEL_HEAP:-8G}

rm -f "$output_file"
(cd "$repo_root/chisel" &&
    sbt -J-Xmx"$heap_size" \
        "runMain fpt.EmitPaperBootstrappingKeyBuffer $output_dir")

if [[ ! -s $output_file ]]; then
    echo "Chisel did not emit $output_file" >&2
    exit 1
fi
for module in BootstrappingKeyPingPongBuffer memory_32x13824; do
    if ! rg -q "^module $module\\(" "$output_file"; then
        echo "Emitted source is missing $module" >&2
        exit 1
    fi
done
if [[ $(rg -c 'ram_style = "block"' "$output_file") != 1 ]]; then
    echo "Emitted source does not tag exactly one key memory as block RAM" >&2
    exit 1
fi

if command -v verilator >/dev/null && [[ ${FPT_SKIP_LINT:-0} != 1 ]]; then
    verilator --lint-only -Wno-fatal \
        --top-module BootstrappingKeyPingPongBuffer \
        "$output_file"
fi

echo "Emitted paper-shaped bootstrapping-key buffer in $output_file"

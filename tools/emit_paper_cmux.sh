#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
forward=${1:-$repo_root/build/sgen-fpt/forward.v}
inverse=${2:-$repo_root/build/sgen-fpt/inverse.v}
output_dir=${3:-$repo_root/build/chisel-paper}

forward=$(realpath "$forward")
inverse=$(realpath "$inverse")
output_dir=$(realpath -m "$output_dir")

if ! rg -q '^module FptSGenForward\(' "$forward"; then
    echo "FptSGenForward not found in $forward" >&2
    exit 1
fi
if ! rg -q '^module FptSGenInverse\(' "$inverse"; then
    echo "FptSGenInverse not found in $inverse" >&2
    exit 1
fi

(cd "$repo_root/chisel" &&
    sbt "runMain fpt.EmitPaperCmux $output_dir $forward $inverse")

if command -v verilator >/dev/null; then
    verilator --lint-only -Wno-fatal --top-module CmuxEngine \
        "$output_dir/CmuxEngine.sv" "$forward" "$inverse"
fi

echo "Emitted paper-shaped CMUX in $output_dir/CmuxEngine.sv"

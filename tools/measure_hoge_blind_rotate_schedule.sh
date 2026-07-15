#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
hoge_dir=${1:-$repo_root/../HOGE}
source_dir=${2:-$repo_root/build/hoge-baselines}
build_dir=${3:-$repo_root/build/hoge-blind-rotate-sim}
source_file=$source_dir/HOGEBlindRotateBaseline.v
top=HOGEBlindRotateBaseline

if ! command -v verilator >/dev/null; then
    echo "verilator is required for the HOGE schedule measurement" >&2
    exit 1
fi
if [[ ! -s $source_file ]]; then
    "$repo_root/tools/generate_hoge_baselines.sh" "$hoge_dir" "$source_dir"
fi

source_file=$(realpath "$source_file")
build_dir=$(realpath -m "$build_dir")
mkdir -p "$build_dir"

verilator --cc --exe --build -O3 -Wno-fatal \
    --top-module "$top" \
    --Mdir "$build_dir/obj" \
    "$source_file" "$repo_root/tests/hoge_blind_rotate_schedule.cpp"

"$build_dir/obj/V$top" | tee "$build_dir/schedule.txt"

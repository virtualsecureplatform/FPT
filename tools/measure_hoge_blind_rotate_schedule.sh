#!/usr/bin/env bash
set -euo pipefail

script_path=$(realpath "${BASH_SOURCE[0]}")
repo_root=$(cd "$(dirname "$script_path")/.." && pwd)
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

signature=$(
    {
        for signature_input in \
            "$source_file" "$repo_root/tests/hoge_blind_rotate_schedule.cpp" \
            "$script_path"; do
            sha256sum "$signature_input" | awk '{ print $1 }'
        done
        printf '%s\n' "$(verilator --version)" "$top"
    } | sha256sum | awk '{ print $1 }'
)
signature_file=$build_dir/input.sha256
executable=$build_dir/obj/V$top
if [[ -x $executable && -s $signature_file && \
      $(<"$signature_file") == "$signature" ]]; then
    echo "Reusing the matching HOGE Verilator model in $build_dir" >&2
    "$executable" | tee "$build_dir/schedule.txt"
    exit 0
fi

verilator --cc --exe --build -O3 -Wno-fatal \
    --top-module "$top" \
    --Mdir "$build_dir/obj" \
    "$source_file" "$repo_root/tests/hoge_blind_rotate_schedule.cpp"

printf '%s\n' "$signature" > "$signature_file"
"$executable" | tee "$build_dir/schedule.txt"

#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
hoge_dir=${1:-$repo_root/third_party/HOGE}
output_dir=${2:-$repo_root/build/hoge-baselines}

if ! git -C "$hoge_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "HOGE checkout not found: $hoge_dir" >&2
    exit 1
fi
if [[ ! -f $hoge_dir/chisel/HomGate/build.sbt ]]; then
    echo "HOGE HomGate Chisel project not found in $hoge_dir" >&2
    exit 1
fi

hoge_dir=$(realpath "$hoge_dir")
output_dir=$(realpath -m "$output_dir")
stage_dir=$output_dir/.hoge-stage
mkdir -p "$output_dir"
rm -rf "$stage_dir"
mkdir -p "$stage_dir"

cleanup() {
    # sbt can finish writing its global log just after the run command exits.
    # Retry once so that a harmless log-creation race cannot fail the flow.
    rm -rf "$stage_dir" 2>/dev/null || {
        sleep 0.1
        rm -rf "$stage_dir"
    }
}
trap cleanup EXIT

cp -a "$hoge_dir/chisel/HomGate/." "$stage_dir/"
cp "$repo_root/hoge/BaselineEmit.scala" "$stage_dir/src/main/scala/"

(cd "$stage_dir" &&
    sbt "runMain HOGEBaselineEmit $output_dir")

tops=(
    HOGEForwardINTTBaseline
    HOGEInverseNTTBaseline
    HOGEBlindRotateBaseline
)
for top in "${tops[@]}"; do
    source_file=$output_dir/$top.v
    if [[ ! -s $source_file ]]; then
        echo "HOGE Chisel did not emit $source_file" >&2
        exit 1
    fi
    if command -v verilator >/dev/null && [[ ${FPT_SKIP_LINT:-0} != 1 ]]; then
        verilator --lint-only -Wno-fatal --top-module "$top" "$source_file"
    fi
done

echo "Generated HOGE transform and Blind Rotate baselines in $output_dir"

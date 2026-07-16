#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if ! command -v jq >/dev/null; then
    echo "jq not installed; skipping Yosys Blind Rotate report test"
    exit 0
fi

work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
manifest=$work_dir/manifest.tsv
{
    printf '%s\n' $'key\tvalue'
    printf 'fpt_blind_rotate_contexts\t2\n'
    printf 'fpt_blind_rotate_cycles_per_result\t10\n'
    printf 'hoge_blind_rotate_contexts\t1\n'
    printf 'hoge_blind_rotate_cycles_per_result\t100\n'
} > "$manifest"

build_root=$work_dir/results
mkdir -p "$build_root/fpt-blind-rotate" \
    "$build_root/hoge-blind-rotate"
printf '%s\n' \
    '{"design":{"estimated_num_lc":100,"num_cells":160,' \
    '"num_cells_by_type":{"LUT6":40,"FDRE":80,"CARRY4":5,' \
    '"MUXF7":6,"RAMB36E2":4,"RAM32M16":2,"SRL16E":3,' \
    '"DSP48E2":20}}}' \
    > "$build_root/fpt-blind-rotate/stat.json"
printf '%s\n' \
    '{"design":{"estimated_num_lc":50,"num_cells":80,' \
    '"num_cells_by_type":{"LUT6":20,"FDRE":40,"CARRY4":2,' \
    '"MUXF7":3,"RAMB36E2":2,"RAM32M16":1,"SRL16E":2,' \
    '"DSP48E2":10}}}' \
    > "$build_root/hoge-blind-rotate/stat.json"
printf 'elapsed_seconds=12.5\nmax_rss_kib=1000\n' \
    > "$build_root/fpt-blind-rotate/timing.txt"
printf 'elapsed_seconds=7.5\nmax_rss_kib=500\n' \
    > "$build_root/hoge-blind-rotate/timing.txt"

"$repo_root/tools/report_yosys_fpt_hoge_blind_rotate.sh" \
    "$build_root" "$manifest" >/dev/null

if [[ $(wc -l < "$build_root/summary.tsv") != 3 ]]; then
    echo "Blind Rotate summary does not contain both fixture designs" >&2
    exit 1
fi
expected=$'blind-rotate\t10\t100\t10.000\t5.000\t5.000\t5.000\t5.000'
actual=$(sed -n '2p' "$build_root/comparison.tsv")
if [[ $actual != "$expected" ]]; then
    echo "Unexpected Blind Rotate efficiency comparison: $actual" >&2
    exit 1
fi

rm "$build_root/hoge-blind-rotate/stat.json"
if "$repo_root/tools/report_yosys_fpt_hoge_blind_rotate.sh" \
    "$build_root" "$manifest" >/dev/null 2>&1; then
    echo "Blind Rotate reporter accepted a missing statistics file" >&2
    exit 1
fi

echo "Yosys Blind Rotate report tests passed"

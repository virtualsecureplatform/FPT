#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

valid_metrics=$work_dir/valid.tsv
tclsh "$repo_root/tests/u280_post_route_metrics_test.tcl" "$valid_metrics"
"$repo_root/tools/check_u280_route_metrics.sh" "$valid_metrics"

invalid_route=$work_dir/invalid-route.tsv
awk -F '\t' 'BEGIN { OFS = FS }
    $1 == "route_fully_routed" { $2 = 0 }
    { print }
' "$valid_metrics" > "$invalid_route"
if "$repo_root/tools/check_u280_route_metrics.sh" "$invalid_route" \
    >/dev/null 2>&1; then
    echo "Route-contract checker accepted an incomplete route" >&2
    exit 1
fi

invalid_clock=$work_dir/invalid-clock.tsv
awk -F '\t' 'BEGIN { OFS = FS }
    $1 == "implemented_clock_period_ns" { $2 = 4.000 }
    { print }
' "$valid_metrics" > "$invalid_clock"
if "$repo_root/tools/check_u280_route_metrics.sh" "$invalid_clock" \
    >/dev/null 2>&1; then
    echo "Route-contract checker accepted a mismatched clock" >&2
    exit 1
fi

invalid_drc=$work_dir/invalid-drc.tsv
awk -F '\t' 'BEGIN { OFS = FS }
    $1 == "drc_violations" { $2 = 4 }
    $1 == "drc_critical_warning" { $2 = 1 }
    { print }
' "$valid_metrics" > "$invalid_drc"
if "$repo_root/tools/check_u280_route_metrics.sh" "$invalid_drc" \
    >/dev/null 2>&1; then
    echo "Route-contract checker accepted a critical DRC" >&2
    exit 1
fi

fpt_output=$work_dir/fpt-report
mkdir -p "$fpt_output/runs/period-5p0/fpt-forward"
cp "$valid_metrics" \
    "$fpt_output/runs/period-5p0/fpt-forward/metrics.tsv"
"$repo_root/tools/report_u280_fpt_hoge_comparison.sh" "$fpt_output" \
    >/dev/null
awk -F '\t' '
    NR == 1 && NF != 27 { exit 1 }
    NR == 2 && (NF != 27 || $5 != 1 || $10 != 0) { exit 1 }
' "$fpt_output/summary.tsv"

cmux_output=$work_dir/cmux-report
mkdir -p "$cmux_output/runs/period-5p0/barrel-batched" \
    "$cmux_output/runs/period-5p0/bitwise-batched"
cp "$valid_metrics" \
    "$cmux_output/runs/period-5p0/barrel-batched/metrics.tsv"
cp "$valid_metrics" \
    "$cmux_output/runs/period-5p0/bitwise-batched/metrics.tsv"
"$repo_root/tools/report_vivado_u280_comparison.sh" "$cmux_output" \
    >/dev/null
awk -F '\t' '
    NR == 1 && NF != 23 { exit 1 }
    NR > 1 && (NF != 23 || $5 != 1 || $10 != 0) { exit 1 }
' "$cmux_output/summary.tsv"

echo "U280 route-contract tests passed"

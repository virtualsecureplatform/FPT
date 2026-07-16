#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

prepared=$work_dir/prepared
mkdir -p "$prepared/sources/sgen" \
    "$prepared/sources/hoge" \
    "$prepared/sources/fpt-blind-rotate" \
    "$prepared/sources/fpt-buffered-blind-rotate"
printf 'module FptSGenForward(input clk); endmodule\n' \
    > "$prepared/sources/sgen/forward.v"
printf 'module FptSGenInverse(input clk); endmodule\n' \
    > "$prepared/sources/sgen/inverse.v"
printf 'module HOGEForwardINTTBaseline(input clock); endmodule\n' \
    > "$prepared/sources/hoge/HOGEForwardINTTBaseline.v"
printf 'module HOGEInverseNTTBaseline(input clock); endmodule\n' \
    > "$prepared/sources/hoge/HOGEInverseNTTBaseline.v"
printf 'module BatchedBlindRotateSampleExtractEngine(input clock); endmodule\n' \
    > "$prepared/sources/fpt-blind-rotate/BatchedBlindRotateSampleExtractEngine.sv"
printf 'module BufferedBlindRotateAccelerator(input clock); endmodule\n' \
    > "$prepared/sources/fpt-buffered-blind-rotate/BufferedBlindRotateAccelerator.sv"
printf 'module HOGEBlindRotateBaseline(input clock); endmodule\n' \
    > "$prepared/sources/hoge/HOGEBlindRotateBaseline.v"

sha256() {
    sha256sum "$1" | awk '{ print $1 }'
}

hash_lines() {
    printf '%s\n' "$@" | sha256sum | awk '{ print $1 }'
}

single_tcl=$repo_root/chisel/scripts/synth_sgen_u280.tcl
composed_tcl=$repo_root/chisel/scripts/synth_paper_cmux_u280.tcl
metrics_tcl=$repo_root/chisel/scripts/u280_post_route_metrics.tcl
single_tcl_sha=$(sha256 "$single_tcl")
composed_tcl_sha=$(sha256 "$composed_tcl")
metrics_tcl_sha=$(sha256 "$metrics_tcl")
single_flow_sha=$(hash_lines "$single_tcl_sha" "$metrics_tcl_sha")
composed_flow_sha=$(hash_lines "$composed_tcl_sha" "$metrics_tcl_sha")

{
    printf '%s\n' $'key\tvalue'
    printf 'fpt_commit\tfixture-fpt\n'
    printf 'fpt_tracked_state\tclean\n'
    printf 'hoge_commit\tfixture-hoge\n'
    printf 'hoge_tracked_state\tclean\n'
    printf 'sgen_commit\tfixture-sgen\n'
    printf 'sgen_tracked_state\tclean\n'
    printf 'part\txcu280-fsvh2892-2L-e\n'
    printf 'clock_periods_ns\t5.0\n'
    printf 'vivado_jobs\t2\n'
    printf 'selected_designs\tfpt-forward hoge-forward fpt-blind-rotate fpt-buffered-blind-rotate hoge-blind-rotate\n'
    printf 'fpt_blind_rotate_cycles_per_result\t12.5\n'
    printf 'fpt_buffered_blind_rotate_cycles_per_result\t12.6\n'
    printf 'hoge_blind_rotate_cycles_per_result\t100.0\n'
    printf 'fpt_forward_sha256\t%s\n' \
        "$(sha256 "$prepared/sources/sgen/forward.v")"
    printf 'fpt_inverse_sha256\t%s\n' \
        "$(sha256 "$prepared/sources/sgen/inverse.v")"
    printf 'hoge_forward_sha256\t%s\n' \
        "$(sha256 "$prepared/sources/hoge/HOGEForwardINTTBaseline.v")"
    printf 'hoge_inverse_sha256\t%s\n' \
        "$(sha256 "$prepared/sources/hoge/HOGEInverseNTTBaseline.v")"
    printf 'fpt_blind_rotate_sha256\t%s\n' \
        "$(sha256 "$prepared/sources/fpt-blind-rotate/BatchedBlindRotateSampleExtractEngine.sv")"
    printf 'fpt_buffered_blind_rotate_sha256\t%s\n' \
        "$(sha256 "$prepared/sources/fpt-buffered-blind-rotate/BufferedBlindRotateAccelerator.sv")"
    printf 'hoge_blind_rotate_sha256\t%s\n' \
        "$(sha256 "$prepared/sources/hoge/HOGEBlindRotateBaseline.v")"
    printf 'single_source_top_flow_sha256\t%s\n' "$single_tcl_sha"
    printf 'composed_top_flow_sha256\t%s\n' "$composed_tcl_sha"
    printf 'post_route_metrics_flow_sha256\t%s\n' "$metrics_tcl_sha"
    printf 'single_source_flow_sha256\t%s\n' "$single_flow_sha"
    printf 'composed_flow_sha256\t%s\n' "$composed_flow_sha"
} > "$prepared/manifest.tsv"

bundle_verify=$work_dir/bundle-verify
"$repo_root/tools/package_u280_fpt_hoge_handoff.sh" \
    "$prepared" "$bundle_verify" >/dev/null
if [[ $(awk -F '\t' '$1 == "bundle_format_version" { print $2 }' \
        "$bundle_verify/manifest.tsv") != 2 ]]; then
    echo "Prepared handoff did not use the cache-inclusive bundle format" >&2
    exit 1
fi
FPT_PREPARED_VERIFY_ONLY=1 \
    "$bundle_verify/tools/run_prepared_u280_fpt_hoge_comparison.sh" \
    "$bundle_verify" >/dev/null

printf '// corrupted\n' >> "$bundle_verify/sources/sgen/forward.v"
if FPT_PREPARED_VERIFY_ONLY=1 \
    "$bundle_verify/tools/run_prepared_u280_fpt_hoge_comparison.sh" \
    "$bundle_verify" >/dev/null 2>&1; then
    echo "Prepared handoff runner accepted a corrupted source" >&2
    exit 1
fi

bundle_route=$work_dir/bundle-route
"$repo_root/tools/package_u280_fpt_hoge_handoff.sh" \
    "$prepared" "$bundle_route" >/dev/null

valid_metrics=$work_dir/valid-metrics.tsv
tclsh "$repo_root/tests/u280_post_route_metrics_test.tcl" "$valid_metrics"
fake_bin=$work_dir/bin
mkdir -p "$fake_bin"
fake_vivado=$fake_bin/vivado
cat > "$fake_vivado" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ ${1:-} == -version ]]; then
    echo 'Vivado vFixture.1'
    exit 0
fi

args=("$@")
source_tcl=
tclargs_index=-1
for ((index = 0; index < ${#args[@]}; ++index)); do
    case "${args[index]}" in
        -source) source_tcl=${args[index + 1]} ;;
        -tclargs) tclargs_index=$index ;;
    esac
done
if [[ -z $source_tcl || $tclargs_index == -1 ]]; then
    echo 'Malformed mocked Vivado invocation' >&2
    exit 1
fi

case "$(basename "$source_tcl")" in
    synth_sgen_u280.tcl)
        run_dir=${args[tclargs_index + 3]}
        period=${args[tclargs_index + 4]}
        ;;
    synth_paper_cmux_u280.tcl)
        run_dir=${args[tclargs_index + 4]}
        period=${args[tclargs_index + 5]}
        ;;
    *)
        echo "Unexpected mocked Vivado flow: $source_tcl" >&2
        exit 1
        ;;
esac

mkdir -p "$run_dir"
awk -F '\t' -v period="$period" 'BEGIN { OFS = FS }
    $1 == "clock_period_ns" { $2 = period }
    $1 == "implemented_clock_period_ns" { $2 = period }
    { print }
' "$VALID_METRICS" > "$run_dir/metrics.tsv"
printf '| Total On-Chip Power (W) | 1.25 |\n' > "$run_dir/power.rpt"
printf 'route\n' >> "$VIVADO_CALL_LOG"
EOF
chmod +x "$fake_vivado"

call_log=$work_dir/vivado-calls.txt
: > "$call_log"
export VALID_METRICS="$valid_metrics"
export VIVADO_CALL_LOG="$call_log"
PATH="$fake_bin:$PATH" \
FPT_VIVADO_CLOCK_PERIODS=5.0 \
FPT_HOGE_DESIGNS='fpt-forward hoge-forward fpt-blind-rotate fpt-buffered-blind-rotate hoge-blind-rotate' \
    "$bundle_route/tools/run_prepared_u280_fpt_hoge_comparison.sh" \
    "$bundle_route" >/dev/null

if [[ $(find "$bundle_route/runs" -name metrics.tsv -type f | wc -l) != 5 || \
      $(wc -l < "$call_log") != 5 ]]; then
    echo "Prepared handoff runner did not execute all mocked routes" >&2
    exit 1
fi
if [[ $(wc -l < "$bundle_route/summary.tsv") != 6 ]]; then
    echo "Prepared handoff report does not contain all mocked routes" >&2
    exit 1
fi
if ! awk -F '\t' \
    '$1 == "buffered-blind-rotate" { found = 1 }
     END { exit !found }' "$bundle_route/comparison.tsv"; then
    echo "Prepared handoff report omitted the buffered FPT/HOGE comparison" >&2
    exit 1
fi
if ! awk -F '\t' \
    '$1 == "fpt-buffered-blind-rotate" && $14 == 12.6 { found = 1 }
     END { exit !found }' "$bundle_route/summary.tsv"; then
    echo "Prepared handoff report lost the buffered FPT schedule" >&2
    exit 1
fi

PATH="$fake_bin:$PATH" \
FPT_VIVADO_CLOCK_PERIODS=5.0 \
FPT_HOGE_DESIGNS='fpt-forward hoge-forward fpt-blind-rotate fpt-buffered-blind-rotate hoge-blind-rotate' \
FPT_VIVADO_REUSE=1 \
    "$bundle_route/tools/run_prepared_u280_fpt_hoge_comparison.sh" \
    "$bundle_route" >/dev/null
if [[ $(wc -l < "$call_log") != 5 ]]; then
    echo "Prepared handoff runner did not reuse signature-matched routes" >&2
    exit 1
fi

echo "Prepared U280 handoff tests passed"

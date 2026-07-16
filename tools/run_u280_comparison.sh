#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
sgen_dir=${1:-$repo_root/third_party/SGen}
output_root=${2:-$repo_root/build/vivado-u280-comparison}
part=${FPT_U280_PART:-xcu280-fsvh2892-2L-e}
period_list=${FPT_VIVADO_CLOCK_PERIODS:-5.0 3.425}
jobs=${FPT_VIVADO_JOBS:-8}
reuse=${FPT_VIVADO_REUSE:-0}
prepare_only=${FPT_VIVADO_PREPARE_ONLY:-0}
scope=${FPT_VIVADO_SCOPE:-cmux}
blind_rotate_dimension=${FPT_BLIND_ROTATE_DIMENSION:-630}

case "$reuse" in 0|1) ;; *) echo "FPT_VIVADO_REUSE must be 0 or 1" >&2; exit 1 ;; esac
case "$prepare_only" in 0|1) ;; *) echo "FPT_VIVADO_PREPARE_ONLY must be 0 or 1" >&2; exit 1 ;; esac
case "$scope" in
    cmux|blind-rotate) ;;
    *) echo "FPT_VIVADO_SCOPE must be cmux or blind-rotate" >&2; exit 1 ;;
esac
if [[ ! $blind_rotate_dimension =~ ^[1-9][0-9]*$ ]]; then
    echo "FPT_BLIND_ROTATE_DIMENSION must be a positive integer" >&2
    exit 1
fi
if [[ ! $jobs =~ ^[1-9][0-9]*$ ]]; then
    echo "FPT_VIVADO_JOBS must be a positive integer" >&2
    exit 1
fi
if ! git -C "$sgen_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "SGen checkout not found: $sgen_dir" >&2
    exit 1
fi

read -r -a periods <<< "$period_list"
if [[ ${#periods[@]} == 0 ]]; then
    echo "FPT_VIVADO_CLOCK_PERIODS must contain at least one period" >&2
    exit 1
fi
for period in "${periods[@]}"; do
    if ! awk -v period="$period" \
        'BEGIN { exit !(period ~ /^[0-9]+([.][0-9]+)?$/ && period > 0) }'; then
        echo "Invalid positive clock period: $period" >&2
        exit 1
    fi
done

sgen_dir=$(realpath "$sgen_dir")
output_root=$(realpath -m "$output_root")
sources_dir=$output_root/sources
sgen_sources=$sources_dir/sgen
barrel_sources=$sources_dir/barrel-batched
bitwise_sources=$sources_dir/bitwise-batched
runs_dir=$output_root/runs
manifest=$output_root/manifest.tsv
route_metrics_checker=$repo_root/tools/check_u280_route_metrics.sh
mkdir -p "$sources_dir" "$runs_dir"

remove_sgen_binary=0
if [[ ! -e $sgen_dir/sgen.bat ]]; then
    remove_sgen_binary=1
fi
cleanup() {
    if [[ $remove_sgen_binary == 1 ]]; then
        rm -f "$sgen_dir/sgen.bat"
    fi
}
trap cleanup EXIT

if ! command -v sbt >/dev/null; then
    echo "sbt is required to assemble SGen and emit the Chisel tops" >&2
    exit 1
fi
echo "Assembling the checked-out SGen generator"
(cd "$sgen_dir" && sbt assembly)
if [[ ! -x $sgen_dir/sgen.bat ]]; then
    echo "SGen assembly did not produce $sgen_dir/sgen.bat" >&2
    exit 1
fi

"$repo_root/tools/generate_sgen_fpt.sh" "$sgen_dir" "$sgen_sources"
if [[ $scope == blind-rotate ]]; then
    FPT_BLIND_ROTATE_DIMENSION=$blind_rotate_dimension \
        "$repo_root/tools/emit_paper_batched_blind_rotate.sh" \
        "$sgen_sources/forward.v" "$sgen_sources/inverse.v" \
        "$barrel_sources"
    FPT_BLIND_ROTATE_DIMENSION=$blind_rotate_dimension \
        "$repo_root/tools/emit_paper_bitwise_batched_blind_rotate.sh" \
        "$sgen_sources/forward.v" "$sgen_sources/inverse.v" \
        "$bitwise_sources"
    top_module=BatchedBlindRotateEngine
else
    "$repo_root/tools/emit_paper_batched_cmux.sh" \
        "$sgen_sources/forward.v" "$sgen_sources/inverse.v" \
        "$barrel_sources"
    "$repo_root/tools/emit_paper_bitwise_batched_cmux.sh" \
        "$sgen_sources/forward.v" "$sgen_sources/inverse.v" \
        "$bitwise_sources"
    top_module=BatchedCmuxEngine
fi

barrel_sv=$barrel_sources/$top_module.sv
bitwise_sv=$bitwise_sources/$top_module.sv
forward_v=$sgen_sources/forward.v
inverse_v=$sgen_sources/inverse.v
for source_file in "$barrel_sv" "$bitwise_sv" "$forward_v" "$inverse_v"; do
    if [[ ! -s $source_file ]]; then
        echo "Expected generated RTL is missing: $source_file" >&2
        exit 1
    fi
done

vivado_version=unavailable
if command -v vivado >/dev/null; then
    vivado_output=$(vivado -version 2>&1)
    vivado_version=${vivado_output%%$'\n'*}
elif [[ $prepare_only == 0 ]]; then
    echo "vivado is unavailable; set FPT_VIVADO_PREPARE_ONLY=1 to emit sources only" >&2
    exit 1
fi

tracked_state() {
    local checkout=$1
    if git -C "$checkout" diff --quiet && git -C "$checkout" diff --cached --quiet; then
        printf '%s\n' clean
    else
        printf '%s\n' tracked-dirty
    fi
}

sha256() {
    sha256sum "$1" | awk '{ print $1 }'
}

hash_lines() {
    printf '%s\n' "$@" | sha256sum | awk '{ print $1 }'
}

forward_sha=$(sha256 "$forward_v")
inverse_sha=$(sha256 "$inverse_v")
barrel_sha=$(sha256 "$barrel_sv")
bitwise_sha=$(sha256 "$bitwise_sv")
top_flow_sha=$(sha256 \
    "$repo_root/chisel/scripts/synth_paper_cmux_u280.tcl")
post_route_metrics_flow_sha=$(sha256 \
    "$repo_root/chisel/scripts/u280_post_route_metrics.tcl")
flow_sha=$(hash_lines "$top_flow_sha" "$post_route_metrics_flow_sha")

{
    printf '%s\n' $'key\tvalue'
    printf 'generated_utc\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'fpt_commit\t%s\n' "$(git -C "$repo_root" rev-parse HEAD)"
    printf 'fpt_tracked_state\t%s\n' "$(tracked_state "$repo_root")"
    printf 'sgen_commit\t%s\n' "$(git -C "$sgen_dir" rev-parse HEAD)"
    printf 'sgen_tracked_state\t%s\n' "$(tracked_state "$sgen_dir")"
    printf 'vivado_version\t%s\n' "$vivado_version"
    printf 'part\t%s\n' "$part"
    printf 'clock_periods_ns\t%s\n' "$period_list"
    printf 'vivado_jobs\t%s\n' "$jobs"
    printf 'rtl_scope\t%s\n' "$scope"
    printf 'top_module\t%s\n' "$top_module"
    if [[ $scope == blind-rotate ]]; then
        printf 'blind_rotate_dimension\t%s\n' "$blind_rotate_dimension"
        printf 'cmuxes_per_blind_rotate\t%s\n' "$blind_rotate_dimension"
        printf 'cmux_schedule_cycles_per_blind_rotate\t%s\n' \
            "$((blind_rotate_dimension * 16))"
        printf 'barrel_batch_commands\t%s\n' \
            "$((blind_rotate_dimension * 14))"
        printf 'barrel_batch_span_cycles\t%s\n' \
            "$(((blind_rotate_dimension * 14 - 1) * 16 + 212))"
        printf 'bitwise_batch_commands\t%s\n' \
            "$((blind_rotate_dimension * 15))"
        printf 'bitwise_batch_span_cycles\t%s\n' \
            "$(((blind_rotate_dimension * 15 - 1) * 16 + 229))"
    fi
    printf 'barrel_contexts\t14\n'
    printf 'barrel_latency_cycles\t212\n'
    printf 'bitwise_contexts\t15\n'
    printf 'bitwise_latency_cycles\t229\n'
    printf 'initiation_interval_cycles\t16\n'
    printf 'forward_sha256\t%s\n' "$forward_sha"
    printf 'inverse_sha256\t%s\n' "$inverse_sha"
    printf 'barrel_batched_sha256\t%s\n' "$barrel_sha"
    printf 'bitwise_batched_sha256\t%s\n' "$bitwise_sha"
    printf 'vivado_top_flow_sha256\t%s\n' "$top_flow_sha"
    printf 'post_route_metrics_flow_sha256\t%s\n' \
        "$post_route_metrics_flow_sha"
    printf 'vivado_flow_sha256\t%s\n' "$flow_sha"
} > "$manifest"

echo "Prepared comparison sources and manifest in $output_root"
if [[ $prepare_only == 1 ]]; then
    echo "Preparation-only mode: Vivado runs were not started"
    exit 0
fi

run_design() {
    local design=$1
    local source_file=$2
    local period=$3
    local period_tag=${period//./p}
    local run_dir=$runs_dir/period-$period_tag/$design
    local source_sha
    local signature
    local previous_signature

    source_sha=$(sha256 "$source_file")
    signature=$(printf '%s\n' \
        "$design" "$source_sha" "$forward_sha" "$inverse_sha" "$flow_sha" \
        "$part" "$period" "$jobs" "$vivado_version" "$top_module" | \
        sha256sum | awk '{ print $1 }')

    mkdir -p "$run_dir"
    if [[ $reuse == 1 && -s $run_dir/metrics.tsv && \
          -s $run_dir/input.sha256 ]]; then
        previous_signature=$(<"$run_dir/input.sha256")
        if [[ $previous_signature == "$signature" ]] && \
            "$route_metrics_checker" "$run_dir/metrics.tsv" \
                >/dev/null 2>&1; then
            echo "Reusing completed $design run at ${period} ns"
            return
        fi
        echo "Inputs changed; rerouting $design at ${period} ns"
    fi
    rm -f "$run_dir/metrics.tsv"
    printf '%s\n' "$signature" > "$run_dir/input.sha256"

    echo "Routing $design at ${period} ns on $part"
    vivado -mode batch \
        -log "$run_dir/vivado.log" \
        -journal "$run_dir/vivado.jou" \
        -source "$repo_root/chisel/scripts/synth_paper_cmux_u280.tcl" \
        -tclargs "$source_file" "$forward_v" "$inverse_v" \
            "$run_dir" "$period" "$top_module" "$part" "$jobs"
}

for period in "${periods[@]}"; do
    # Run sequentially: either paper-sized design can consume tens of GiB.
    run_design barrel-batched "$barrel_sv" "$period"
    run_design bitwise-batched "$bitwise_sv" "$period"
done

"$repo_root/tools/report_vivado_u280_comparison.sh" "$output_root"

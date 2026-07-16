#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
handoff_root=${1:-$repo_root}
handoff_root=$(realpath "$handoff_root")
manifest=$handoff_root/manifest.tsv
sources=$handoff_root/sources
runs_dir=$handoff_root/runs
part=${FPT_U280_PART:-}
period_list=${FPT_VIVADO_CLOCK_PERIODS:-}
jobs=${FPT_VIVADO_JOBS:-}
reuse=${FPT_VIVADO_REUSE:-0}
verify_only=${FPT_PREPARED_VERIFY_ONLY:-0}
design_list=${FPT_HOGE_DESIGNS:-}

case "$reuse" in 0|1) ;; *) echo "FPT_VIVADO_REUSE must be 0 or 1" >&2; exit 1 ;; esac
case "$verify_only" in 0|1) ;; *) echo "FPT_PREPARED_VERIFY_ONLY must be 0 or 1" >&2; exit 1 ;; esac
for tool in awk realpath sha256sum; do
    if ! command -v "$tool" >/dev/null; then
        echo "Required tool not found: $tool" >&2
        exit 1
    fi
done
if [[ ! -s $manifest ]]; then
    echo "Prepared manifest not found: $manifest" >&2
    exit 1
fi

manifest_value() {
    local key=$1
    awk -F '\t' -v key="$key" '
        $1 == key { print $2; found = 1; exit }
        END { if (!found) exit 1 }
    ' "$manifest"
}

sha256() {
    sha256sum "$1" | awk '{ print $1 }'
}

hash_lines() {
    printf '%s\n' "$@" | sha256sum | awk '{ print $1 }'
}

verify_hash() {
    local key=$1
    local source_file=$2
    local expected
    local actual

    if [[ ! -s $source_file ]]; then
        echo "Prepared handoff input is missing: $source_file" >&2
        exit 1
    fi
    expected=$(manifest_value "$key")
    actual=$(sha256 "$source_file")
    if [[ $actual != "$expected" ]]; then
        echo "Prepared handoff hash mismatch for $key: expected=$expected actual=$actual" >&2
        exit 1
    fi
}

if [[ -s $handoff_root/bundle.sha256 ]]; then
    if ! (cd "$handoff_root" && sha256sum --quiet -c bundle.sha256); then
        echo "Portable handoff bundle checksum verification failed" >&2
        exit 1
    fi
fi

for checkout in fpt hoge sgen; do
    state=$(manifest_value "${checkout}_tracked_state")
    if [[ $state != clean ]]; then
        echo "Refusing tracked-dirty $checkout source provenance: $state" >&2
        exit 1
    fi
done

fpt_forward=$sources/sgen/forward.v
fpt_inverse=$sources/sgen/inverse.v
hoge_forward=$sources/hoge/HOGEForwardINTTBaseline.v
hoge_inverse=$sources/hoge/HOGEInverseNTTBaseline.v
fpt_br=$sources/fpt-blind-rotate/BatchedBlindRotateSampleExtractEngine.sv
fpt_buffered_br=$sources/fpt-buffered-blind-rotate/BufferedBlindRotateAccelerator.sv
hoge_br=$sources/hoge/HOGEBlindRotateBaseline.v
verify_hash fpt_forward_sha256 "$fpt_forward"
verify_hash fpt_inverse_sha256 "$fpt_inverse"
verify_hash hoge_forward_sha256 "$hoge_forward"
verify_hash hoge_inverse_sha256 "$hoge_inverse"
verify_hash fpt_blind_rotate_sha256 "$fpt_br"
verify_hash fpt_buffered_blind_rotate_sha256 "$fpt_buffered_br"
verify_hash hoge_blind_rotate_sha256 "$hoge_br"

single_tcl=$repo_root/chisel/scripts/synth_sgen_u280.tcl
composed_tcl=$repo_root/chisel/scripts/synth_paper_cmux_u280.tcl
metrics_tcl=$repo_root/chisel/scripts/u280_post_route_metrics.tcl
verify_hash single_source_top_flow_sha256 "$single_tcl"
verify_hash composed_top_flow_sha256 "$composed_tcl"
verify_hash post_route_metrics_flow_sha256 "$metrics_tcl"
single_flow_sha=$(hash_lines "$(sha256 "$single_tcl")" \
    "$(sha256 "$metrics_tcl")")
composed_flow_sha=$(hash_lines "$(sha256 "$composed_tcl")" \
    "$(sha256 "$metrics_tcl")")
if [[ $single_flow_sha != "$(manifest_value single_source_flow_sha256)" || \
      $composed_flow_sha != "$(manifest_value composed_flow_sha256)" ]]; then
    echo "Prepared handoff combined flow hash mismatch" >&2
    exit 1
fi

if [[ -z $part ]]; then part=$(manifest_value part); fi
if [[ -z $period_list ]]; then period_list=$(manifest_value clock_periods_ns); fi
if [[ -z $jobs ]]; then jobs=$(manifest_value vivado_jobs); fi
if [[ -z $design_list ]]; then design_list=$(manifest_value selected_designs); fi
if [[ ! $jobs =~ ^[1-9][0-9]*$ ]]; then
    echo "FPT_VIVADO_JOBS must be a positive integer" >&2
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

known_designs=' fpt-forward hoge-forward fpt-inverse hoge-inverse fpt-blind-rotate fpt-buffered-blind-rotate hoge-blind-rotate '
read -r -a designs <<< "$design_list"
if [[ ${#designs[@]} == 0 ]]; then
    echo "FPT_HOGE_DESIGNS must select at least one design" >&2
    exit 1
fi
for design in "${designs[@]}"; do
    if [[ $known_designs != *" $design "* ]]; then
        echo "Unknown FPT_HOGE_DESIGNS entry: $design" >&2
        exit 1
    fi
done

echo "Verified prepared FPT/HOGE handoff at $handoff_root"
if [[ $verify_only == 1 ]]; then
    echo "Verification-only mode: Vivado runs were not started"
    exit 0
fi

route_metrics_checker=$repo_root/tools/check_u280_route_metrics.sh
reporter=$repo_root/tools/report_u280_fpt_hoge_comparison.sh
for executable in "$route_metrics_checker" "$reporter"; do
    if [[ ! -x $executable ]]; then
        echo "Prepared handoff tool is missing or not executable: $executable" >&2
        exit 1
    fi
done
if ! command -v vivado >/dev/null; then
    echo "vivado is required; use FPT_PREPARED_VERIFY_ONLY=1 to verify only" >&2
    exit 1
fi
vivado_output=$(vivado -version 2>&1)
vivado_version=${vivado_output%%$'\n'*}
mkdir -p "$runs_dir"
{
    printf '%s\n' $'key\tvalue'
    printf 'generated_utc\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'handoff_manifest_sha256\t%s\n' "$(sha256 "$manifest")"
    printf 'vivado_version\t%s\n' "$vivado_version"
    printf 'part\t%s\n' "$part"
    printf 'clock_periods_ns\t%s\n' "$period_list"
    printf 'vivado_jobs\t%s\n' "$jobs"
    printf 'selected_designs\t%s\n' "$design_list"
} > "$handoff_root/route-manifest.tsv"

run_single() {
    local design=$1
    local source_file=$2
    local top=$3
    local clock_port=$4
    local period=$5
    local period_tag=${period//./p}
    local run_dir=$runs_dir/period-$period_tag/$design
    local signature

    signature=$(printf '%s\n' "$design" "$(sha256 "$source_file")" \
        "$single_flow_sha" "$part" "$period" "$jobs" "$vivado_version" \
        "$top" "$clock_port" | sha256sum | awk '{ print $1 }')
    mkdir -p "$run_dir"
    if [[ $reuse == 1 && -s $run_dir/metrics.tsv && \
          -s $run_dir/input.sha256 && \
          $(<"$run_dir/input.sha256") == "$signature" ]] && \
          "$route_metrics_checker" "$run_dir/metrics.tsv" \
              >/dev/null 2>&1; then
        echo "Reusing completed $design run at ${period} ns"
        return
    fi
    rm -f "$run_dir/metrics.tsv"
    printf '%s\n' "$signature" > "$run_dir/input.sha256"
    echo "Routing $design at ${period} ns"
    vivado -mode batch -log "$run_dir/vivado.log" \
        -journal "$run_dir/vivado.jou" \
        -source "$single_tcl" \
        -tclargs "$source_file" "$top" "$run_dir" "$period" "$part" \
            "$jobs" "$clock_port"
    "$route_metrics_checker" "$run_dir/metrics.tsv"
}

run_fpt_blind_rotate() {
    local design=$1
    local source_file=$2
    local top=$3
    local period=$4
    local period_tag=${period//./p}
    local run_dir=$runs_dir/period-$period_tag/$design
    local signature

    signature=$(printf '%s\n' "$design" "$(sha256 "$source_file")" \
        "$(sha256 "$fpt_forward")" "$(sha256 "$fpt_inverse")" \
        "$composed_flow_sha" "$part" "$period" "$jobs" \
        "$vivado_version" "$top" | sha256sum | awk '{ print $1 }')
    mkdir -p "$run_dir"
    if [[ $reuse == 1 && -s $run_dir/metrics.tsv && \
          -s $run_dir/input.sha256 && \
          $(<"$run_dir/input.sha256") == "$signature" ]] && \
          "$route_metrics_checker" "$run_dir/metrics.tsv" \
              >/dev/null 2>&1; then
        echo "Reusing completed $design run at ${period} ns"
        return
    fi
    rm -f "$run_dir/metrics.tsv"
    printf '%s\n' "$signature" > "$run_dir/input.sha256"
    echo "Routing $design at ${period} ns"
    vivado -mode batch -log "$run_dir/vivado.log" \
        -journal "$run_dir/vivado.jou" \
        -source "$composed_tcl" \
        -tclargs "$source_file" "$fpt_forward" "$fpt_inverse" "$run_dir" \
            "$period" "$top" "$part" "$jobs"
    "$route_metrics_checker" "$run_dir/metrics.tsv"
}

for period in "${periods[@]}"; do
    for design in "${designs[@]}"; do
        case "$design" in
            fpt-forward)
                run_single "$design" "$fpt_forward" FptSGenForward clk "$period" ;;
            hoge-forward)
                run_single "$design" "$hoge_forward" HOGEForwardINTTBaseline clock "$period" ;;
            fpt-inverse)
                run_single "$design" "$fpt_inverse" FptSGenInverse clk "$period" ;;
            hoge-inverse)
                run_single "$design" "$hoge_inverse" HOGEInverseNTTBaseline clock "$period" ;;
            fpt-blind-rotate)
                run_fpt_blind_rotate "$design" "$fpt_br" \
                    BatchedBlindRotateSampleExtractEngine "$period" ;;
            fpt-buffered-blind-rotate)
                run_fpt_blind_rotate "$design" "$fpt_buffered_br" \
                    BufferedBlindRotateAccelerator "$period" ;;
            hoge-blind-rotate)
                run_single "$design" "$hoge_br" HOGEBlindRotateBaseline clock "$period" ;;
        esac
    done
done

"$reporter" "$handoff_root"

#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
hoge_dir=${1:-$repo_root/../HOGE}
sgen_dir=${2:-$repo_root/../SGen}
output_root=${3:-$repo_root/build/vivado-u280-fpt-hoge-comparison}
part=${FPT_U280_PART:-xcu280-fsvh2892-2L-e}
period_list=${FPT_VIVADO_CLOCK_PERIODS:-5.0 3.425}
jobs=${FPT_VIVADO_JOBS:-8}
reuse=${FPT_VIVADO_REUSE:-0}
prepare_only=${FPT_VIVADO_PREPARE_ONLY:-0}
design_list=${FPT_HOGE_DESIGNS:-fpt-forward hoge-forward fpt-inverse hoge-inverse fpt-blind-rotate hoge-blind-rotate}

case "$reuse" in 0|1) ;; *) echo "FPT_VIVADO_REUSE must be 0 or 1" >&2; exit 1 ;; esac
case "$prepare_only" in 0|1) ;; *) echo "FPT_VIVADO_PREPARE_ONLY must be 0 or 1" >&2; exit 1 ;; esac
if [[ ! $jobs =~ ^[1-9][0-9]*$ ]]; then
    echo "FPT_VIVADO_JOBS must be a positive integer" >&2
    exit 1
fi
for checkout in "$hoge_dir" "$sgen_dir"; do
    if ! git -C "$checkout" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        echo "Required checkout not found: $checkout" >&2
        exit 1
    fi
done

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

known_designs=' fpt-forward hoge-forward fpt-inverse hoge-inverse fpt-blind-rotate hoge-blind-rotate '
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

hoge_dir=$(realpath "$hoge_dir")
sgen_dir=$(realpath "$sgen_dir")
output_root=$(realpath -m "$output_root")
sources_dir=$output_root/sources
sgen_sources=$sources_dir/sgen
hoge_sources=$sources_dir/hoge
fpt_br_sources=$sources_dir/fpt-blind-rotate
runs_dir=$output_root/runs
manifest=$output_root/manifest.tsv
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
    echo "sbt is required to generate SGen and HOGE RTL" >&2
    exit 1
fi
(cd "$sgen_dir" && sbt assembly)
if [[ ! -x $sgen_dir/sgen.bat ]]; then
    echo "SGen assembly did not produce $sgen_dir/sgen.bat" >&2
    exit 1
fi

"$repo_root/tools/generate_sgen_fpt.sh" "$sgen_dir" "$sgen_sources"
"$repo_root/tools/generate_hoge_baselines.sh" "$hoge_dir" "$hoge_sources"
"$repo_root/tools/emit_paper_bitwise_batched_blind_rotate.sh" \
    "$sgen_sources/forward.v" "$sgen_sources/inverse.v" "$fpt_br_sources"

fpt_forward=$sgen_sources/forward.v
fpt_inverse=$sgen_sources/inverse.v
hoge_forward=$hoge_sources/HOGEForwardINTTBaseline.v
hoge_inverse=$hoge_sources/HOGEInverseNTTBaseline.v
hoge_br=$hoge_sources/HOGEBlindRotateBaseline.v
fpt_br=$fpt_br_sources/BatchedBlindRotateEngine.sv
for source_file in "$fpt_forward" "$fpt_inverse" "$hoge_forward" \
    "$hoge_inverse" "$hoge_br" "$fpt_br"; do
    if [[ ! -s $source_file ]]; then
        echo "Expected comparison RTL is missing: $source_file" >&2
        exit 1
    fi
done

instance_count() {
    local source_file=$1
    rg -c '^  INTorusMUL ' "$source_file"
}
hoge_forward_multipliers=$(instance_count "$hoge_forward")
hoge_inverse_multipliers=$(instance_count "$hoge_inverse")
hoge_br_multipliers=$(instance_count "$hoge_br")
if [[ $hoge_forward_multipliers != 31 || \
      $hoge_inverse_multipliers != 32 || \
      $hoge_br_multipliers != 127 ]]; then
    echo "Unexpected HOGE multiplier structure: forward=$hoge_forward_multipliers inverse=$hoge_inverse_multipliers br=$hoge_br_multipliers" >&2
    exit 1
fi

vivado_version=unavailable
if command -v vivado >/dev/null; then
    vivado_output=$(vivado -version 2>&1)
    vivado_version=${vivado_output%%$'\n'*}
elif [[ $prepare_only == 0 ]]; then
    echo "vivado is unavailable; set FPT_VIVADO_PREPARE_ONLY=1 for sources only" >&2
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

fpt_forward_sha=$(sha256 "$fpt_forward")
fpt_inverse_sha=$(sha256 "$fpt_inverse")
hoge_forward_sha=$(sha256 "$hoge_forward")
hoge_inverse_sha=$(sha256 "$hoge_inverse")
hoge_br_sha=$(sha256 "$hoge_br")
fpt_br_sha=$(sha256 "$fpt_br")
single_flow_sha=$(sha256 "$repo_root/chisel/scripts/synth_sgen_u280.tcl")
composed_flow_sha=$(sha256 "$repo_root/chisel/scripts/synth_paper_cmux_u280.tcl")

{
    printf '%s\n' $'key\tvalue'
    printf 'generated_utc\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'fpt_commit\t%s\n' "$(git -C "$repo_root" rev-parse HEAD)"
    printf 'fpt_tracked_state\t%s\n' "$(tracked_state "$repo_root")"
    printf 'hoge_commit\t%s\n' "$(git -C "$hoge_dir" rev-parse HEAD)"
    printf 'hoge_tracked_state\t%s\n' "$(tracked_state "$hoge_dir")"
    printf 'sgen_commit\t%s\n' "$(git -C "$sgen_dir" rev-parse HEAD)"
    printf 'sgen_tracked_state\t%s\n' "$(tracked_state "$sgen_dir")"
    printf 'vivado_version\t%s\n' "$vivado_version"
    printf 'part\t%s\n' "$part"
    printf 'clock_periods_ns\t%s\n' "$period_list"
    printf 'vivado_jobs\t%s\n' "$jobs"
    printf 'selected_designs\t%s\n' "$design_list"
    printf 'polynomial_size\t1024\n'
    printf 'fpt_forward_frame_ii_cycles\t4\n'
    printf 'fpt_inverse_frame_ii_cycles\t8\n'
    printf 'hoge_forward_frame_ii_cycles\t32\n'
    printf 'hoge_inverse_frame_ii_cycles\t32\n'
    printf 'fpt_blind_rotate_dimension\t630\n'
    printf 'fpt_blind_rotate_contexts\t15\n'
    printf 'fpt_blind_rotate_schedule_cycles\t10080\n'
    printf 'hoge_blind_rotate_dimension\t636\n'
    printf 'hoge_blind_rotate_contexts\t2\n'
    printf 'hoge_forward_intorus_mul_instances\t%s\n' "$hoge_forward_multipliers"
    printf 'hoge_inverse_intorus_mul_instances\t%s\n' "$hoge_inverse_multipliers"
    printf 'hoge_blind_rotate_intorus_mul_instances\t%s\n' "$hoge_br_multipliers"
    printf 'fpt_forward_sha256\t%s\n' "$fpt_forward_sha"
    printf 'fpt_inverse_sha256\t%s\n' "$fpt_inverse_sha"
    printf 'hoge_forward_sha256\t%s\n' "$hoge_forward_sha"
    printf 'hoge_inverse_sha256\t%s\n' "$hoge_inverse_sha"
    printf 'fpt_blind_rotate_sha256\t%s\n' "$fpt_br_sha"
    printf 'hoge_blind_rotate_sha256\t%s\n' "$hoge_br_sha"
    printf 'single_source_flow_sha256\t%s\n' "$single_flow_sha"
    printf 'composed_flow_sha256\t%s\n' "$composed_flow_sha"
} > "$manifest"

echo "Prepared FPT/HOGE sources and manifest in $output_root"
if [[ $prepare_only == 1 ]]; then
    echo "Preparation-only mode: Vivado runs were not started"
    exit 0
fi

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
    if [[ $reuse == 1 && -s $run_dir/metrics.tsv && -s $run_dir/input.sha256 && \
          $(<"$run_dir/input.sha256") == "$signature" ]]; then
        echo "Reusing completed $design run at ${period} ns"
        return
    fi
    rm -f "$run_dir/metrics.tsv"
    printf '%s\n' "$signature" > "$run_dir/input.sha256"
    echo "Routing $design at ${period} ns"
    vivado -mode batch -log "$run_dir/vivado.log" \
        -journal "$run_dir/vivado.jou" \
        -source "$repo_root/chisel/scripts/synth_sgen_u280.tcl" \
        -tclargs "$source_file" "$top" "$run_dir" "$period" "$part" \
            "$jobs" "$clock_port"
}

run_fpt_blind_rotate() {
    local period=$1
    local design=fpt-blind-rotate
    local period_tag=${period//./p}
    local run_dir=$runs_dir/period-$period_tag/$design
    local signature

    signature=$(printf '%s\n' "$design" "$fpt_br_sha" "$fpt_forward_sha" \
        "$fpt_inverse_sha" "$composed_flow_sha" "$part" "$period" "$jobs" \
        "$vivado_version" | sha256sum | awk '{ print $1 }')
    mkdir -p "$run_dir"
    if [[ $reuse == 1 && -s $run_dir/metrics.tsv && -s $run_dir/input.sha256 && \
          $(<"$run_dir/input.sha256") == "$signature" ]]; then
        echo "Reusing completed $design run at ${period} ns"
        return
    fi
    rm -f "$run_dir/metrics.tsv"
    printf '%s\n' "$signature" > "$run_dir/input.sha256"
    echo "Routing $design at ${period} ns"
    vivado -mode batch -log "$run_dir/vivado.log" \
        -journal "$run_dir/vivado.jou" \
        -source "$repo_root/chisel/scripts/synth_paper_cmux_u280.tcl" \
        -tclargs "$fpt_br" "$fpt_forward" "$fpt_inverse" "$run_dir" \
            "$period" BatchedBlindRotateEngine "$part" "$jobs"
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
                run_fpt_blind_rotate "$period" ;;
            hoge-blind-rotate)
                run_single "$design" "$hoge_br" HOGEBlindRotateBaseline clock "$period" ;;
        esac
    done
done

"$repo_root/tools/report_u280_fpt_hoge_comparison.sh" "$output_root"

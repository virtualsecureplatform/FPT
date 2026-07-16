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
design_list=${FPT_HOGE_DESIGNS:-fpt-forward hoge-forward fpt-inverse hoge-inverse fpt-buffered-blind-rotate hoge-blind-rotate}
skip_yosys_boundary=${FPT_SKIP_YOSYS_BOUNDARY:-0}
skip_fpt_schedule=${FPT_SKIP_FPT_SCHEDULE:-0}
skip_hoge_schedule=${FPT_SKIP_HOGE_SCHEDULE:-0}

case "$reuse" in 0|1) ;; *) echo "FPT_VIVADO_REUSE must be 0 or 1" >&2; exit 1 ;; esac
case "$prepare_only" in 0|1) ;; *) echo "FPT_VIVADO_PREPARE_ONLY must be 0 or 1" >&2; exit 1 ;; esac
case "$skip_yosys_boundary" in 0|1) ;; *) echo "FPT_SKIP_YOSYS_BOUNDARY must be 0 or 1" >&2; exit 1 ;; esac
case "$skip_fpt_schedule" in 0|1) ;; *) echo "FPT_SKIP_FPT_SCHEDULE must be 0 or 1" >&2; exit 1 ;; esac
case "$skip_hoge_schedule" in 0|1) ;; *) echo "FPT_SKIP_HOGE_SCHEDULE must be 0 or 1" >&2; exit 1 ;; esac
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

hoge_dir=$(realpath "$hoge_dir")
sgen_dir=$(realpath "$sgen_dir")
output_root=$(realpath -m "$output_root")
fpt_schedule_dir=$(realpath -m \
    "${FPT_SCHEDULE_BUILD_DIR:-$output_root/fpt-schedule}")
fpt_buffered_schedule_dir=$(realpath -m \
    "${FPT_BUFFERED_SCHEDULE_BUILD_DIR:-$output_root/fpt-buffered-schedule}")
hoge_schedule_dir=$(realpath -m \
    "${HOGE_SCHEDULE_BUILD_DIR:-$output_root/hoge-schedule}")
fpt_yosys_boundary_dir=$(realpath -m \
    "${FPT_YOSYS_BOUNDARY_DIR:-$output_root/fpt-yosys-boundary}")
sources_dir=$output_root/sources
sgen_sources=$sources_dir/sgen
hoge_sources=$sources_dir/hoge
fpt_br_sources=$sources_dir/fpt-blind-rotate
fpt_buffered_br_sources=$sources_dir/fpt-buffered-blind-rotate
runs_dir=$output_root/runs
manifest=$output_root/manifest.tsv
fpt_buffered_boundary_metrics=$output_root/fpt-buffered-blind-rotate-boundary.tsv
hoge_boundary_metrics=$output_root/hoge-blind-rotate-boundary.tsv
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
"$repo_root/tools/emit_paper_bitwise_batched_blind_rotate_sample_extract.sh" \
    "$sgen_sources/forward.v" "$sgen_sources/inverse.v" "$fpt_br_sources"
"$repo_root/tools/emit_paper_buffered_blind_rotate_accelerator.sh" \
    "$sgen_sources/forward.v" "$sgen_sources/inverse.v" \
    "$fpt_buffered_br_sources"

fpt_forward=$sgen_sources/forward.v
fpt_inverse=$sgen_sources/inverse.v
hoge_forward=$hoge_sources/HOGEForwardINTTBaseline.v
hoge_inverse=$hoge_sources/HOGEInverseNTTBaseline.v
hoge_br=$hoge_sources/HOGEBlindRotateBaseline.v
fpt_br=$fpt_br_sources/BatchedBlindRotateSampleExtractEngine.sv
fpt_buffered_br=$fpt_buffered_br_sources/BufferedBlindRotateAccelerator.sv
for source_file in "$fpt_forward" "$fpt_inverse" "$hoge_forward" \
    "$hoge_inverse" "$hoge_br" "$fpt_br" "$fpt_buffered_br"; do
    if [[ ! -s $source_file ]]; then
        echo "Expected comparison RTL is missing: $source_file" >&2
        exit 1
    fi
done

fpt_gauss_modules=$(rg -c '^module FptExactGaussComplexMultiply ' \
    "$fpt_br" || true)
fpt_split_modules=$(rg -c '^module FptSignedSplitMultiply ' \
    "$fpt_br" || true)
fpt_split_instances=$(rg -c '^  FptSignedSplitMultiply #' \
    "$fpt_br" || true)
fpt_inverse_instances=$(rg -c '^  FptSGenInverse generated ' \
    "$fpt_br" || true)
if [[ $fpt_gauss_modules != 1 || $fpt_split_modules != 1 || \
      $fpt_split_instances != 3 || $fpt_inverse_instances != 1 ]]; then
    echo "Unexpected FPT datapath structure: gauss_modules=$fpt_gauss_modules split_modules=$fpt_split_modules split_instances=$fpt_split_instances inverse_instances=$fpt_inverse_instances" \
        >&2
    exit 1
fi

fpt_buffered_gauss_modules=$(rg -c \
    '^module FptExactGaussComplexMultiply ' "$fpt_buffered_br" || true)
fpt_buffered_split_instances=$(rg -c '^  FptSignedSplitMultiply #' \
    "$fpt_buffered_br" || true)
fpt_buffered_inverse_instances=$(rg -c '^  FptSGenInverse generated ' \
    "$fpt_buffered_br" || true)
fpt_buffered_block_memories=$(rg -c 'ram_style = "block"' \
    "$fpt_buffered_br" || true)
if [[ $fpt_buffered_gauss_modules != 1 || \
      $fpt_buffered_split_instances != 3 || \
      $fpt_buffered_inverse_instances != 1 || \
      $fpt_buffered_block_memories != 1 ]]; then
    echo "Unexpected buffered FPT structure: gauss_modules=$fpt_buffered_gauss_modules split_instances=$fpt_buffered_split_instances inverse_instances=$fpt_buffered_inverse_instances block_memories=$fpt_buffered_block_memories" >&2
    exit 1
fi

boundary_value() {
    local metrics_file=$1
    local metric=$2
    awk -F '\t' -v metric="$metric" '
        $1 == metric { print $2; found = 1; exit }
        END { if (!found) exit 1 }
    ' "$metrics_file"
}
"$repo_root/tools/check_fpt_buffered_blind_rotate_boundary.sh" \
    "$fpt_buffered_br" "$fpt_buffered_boundary_metrics"
fpt_buffered_br_top_ports=$(boundary_value \
    "$fpt_buffered_boundary_metrics" top_ports)
fpt_buffered_br_top_port_bits=$(boundary_value \
    "$fpt_buffered_boundary_metrics" top_port_bits)
fpt_buffered_br_key_data_ports=$(boundary_value \
    "$fpt_buffered_boundary_metrics" key_data_ports)
fpt_buffered_br_key_data_bits=$(boundary_value \
    "$fpt_buffered_boundary_metrics" key_data_bits)
fpt_buffered_br_key_interface_bits=$(boundary_value \
    "$fpt_buffered_boundary_metrics" key_interface_bits)
fpt_buffered_br_input_interface_bits=$(boundary_value \
    "$fpt_buffered_boundary_metrics" input_interface_bits)
fpt_buffered_br_cache_instances=$(boundary_value \
    "$fpt_buffered_boundary_metrics" cache_instances)
fpt_buffered_br_cache_banks=$(boundary_value \
    "$fpt_buffered_boundary_metrics" cache_banks)
fpt_buffered_br_cache_depth=$(boundary_value \
    "$fpt_buffered_boundary_metrics" cache_depth_per_bank)
fpt_buffered_br_cache_word_bits=$(boundary_value \
    "$fpt_buffered_boundary_metrics" cache_word_bits)
fpt_buffered_br_cache_logical_bits=$(boundary_value \
    "$fpt_buffered_boundary_metrics" cache_logical_bits)

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

"$repo_root/tools/check_hoge_blind_rotate_boundary.sh" \
    "$hoge_br" "$hoge_boundary_metrics"
hoge_br_top_ports=$(boundary_value "$hoge_boundary_metrics" top_ports)
hoge_br_top_port_bits=$(boundary_value "$hoge_boundary_metrics" top_port_bits)
hoge_br_key_streams=$(boundary_value "$hoge_boundary_metrics" key_streams)
hoge_br_key_data_bits=$(boundary_value "$hoge_boundary_metrics" key_data_bits)
hoge_br_key_interface_bits=$(boundary_value \
    "$hoge_boundary_metrics" key_interface_bits)
hoge_br_tlwe_interface_bits=$(boundary_value \
    "$hoge_boundary_metrics" tlwe_interface_bits)
hoge_br_result_interface_bits=$(boundary_value \
    "$hoge_boundary_metrics" result_interface_bits)
hoge_br_cache_instances=$(boundary_value \
    "$hoge_boundary_metrics" trgsw_cache_instances)
hoge_br_cache_banks=$(boundary_value \
    "$hoge_boundary_metrics" cache_banks_per_instance)
hoge_br_cache_depth=$(boundary_value \
    "$hoge_boundary_metrics" cache_depth_per_bank)
hoge_br_cache_word_bits=$(boundary_value \
    "$hoge_boundary_metrics" cache_word_bits)
hoge_br_cache_logical_bits=$(boundary_value \
    "$hoge_boundary_metrics" cache_logical_bits)
hoge_br_expected_key_beats=$(boundary_value \
    "$hoge_boundary_metrics" key_beats_per_bus)
hoge_br_key_transfer_bits=$(boundary_value \
    "$hoge_boundary_metrics" key_transfer_bits)

fpt_yosys_boundary_status=unavailable
fpt_yosys_version=unavailable
fpt_yosys_accumulator_memories=unmeasured
fpt_yosys_external_product_memories=unmeasured
fpt_yosys_hierarchy_port_bits=unmeasured
fpt_yosys_hierarchy_memory_bits=unmeasured
fpt_yosys_hierarchy_cells=unmeasured
fpt_yosys_ultrascale_mapping_status=unmeasured
fpt_yosys_ultrascale_accumulator_ramb36e2=unmeasured
fpt_yosys_ultrascale_exponent_ramb18e2=unmeasured
fpt_yosys_ultrascale_sample_extract_distributed_ram=unmeasured
if [[ $skip_yosys_boundary == 1 ]]; then
    fpt_yosys_boundary_status=skipped
elif command -v yosys >/dev/null; then
    "$repo_root/tools/check_fpt_synthesis_boundary.sh" \
        "$fpt_br_sources" "$sgen_sources" "$fpt_yosys_boundary_dir"
    fpt_yosys_metrics=$fpt_yosys_boundary_dir/metrics.tsv
    fpt_yosys_boundary_status=$(awk -F '\t' \
        '$1 == "status" { print $2 }' "$fpt_yosys_metrics")
    fpt_yosys_version=$(awk -F '\t' \
        '$1 == "yosys_version" { print $2 }' "$fpt_yosys_metrics")
    fpt_yosys_accumulator_memories=$(awk -F '\t' \
        '$1 == "accumulator_memories_128x128" { print $2 }' \
        "$fpt_yosys_metrics")
    fpt_yosys_external_product_memories=$(awk -F '\t' \
        '$1 == "external_product_memories_4x15360" { print $2 }' \
        "$fpt_yosys_metrics")
    fpt_yosys_hierarchy_port_bits=$(awk -F '\t' \
        '$1 == "hierarchy_port_bits" { print $2 }' "$fpt_yosys_metrics")
    fpt_yosys_hierarchy_memory_bits=$(awk -F '\t' \
        '$1 == "hierarchy_memory_bits" { print $2 }' "$fpt_yosys_metrics")
    fpt_yosys_hierarchy_cells=$(awk -F '\t' \
        '$1 == "hierarchy_cells" { print $2 }' "$fpt_yosys_metrics")
    fpt_yosys_ultrascale_mapping_status=$(awk -F '\t' \
        '$1 == "ultrascale_mapping_status" { print $2 }' \
        "$fpt_yosys_metrics")
    fpt_yosys_ultrascale_accumulator_ramb36e2=$(awk -F '\t' \
        '$1 == "ultrascale_accumulator_ramb36e2" { print $2 }' \
        "$fpt_yosys_metrics")
    fpt_yosys_ultrascale_exponent_ramb18e2=$(awk -F '\t' \
        '$1 == "ultrascale_exponent_ramb18e2" { print $2 }' \
        "$fpt_yosys_metrics")
    fpt_yosys_ultrascale_sample_extract_distributed_ram=$(awk -F '\t' \
        '$1 == "ultrascale_sample_extract_distributed_ram" { print $2 }' \
        "$fpt_yosys_metrics")
    if [[ $fpt_yosys_boundary_status != passed || \
          $fpt_yosys_ultrascale_mapping_status != passed || \
          $fpt_yosys_accumulator_memories != 128 || \
          $fpt_yosys_external_product_memories != 2 || \
          $fpt_yosys_ultrascale_accumulator_ramb36e2 != 256 || \
          $fpt_yosys_ultrascale_exponent_ramb18e2 != 9 || \
          $fpt_yosys_ultrascale_sample_extract_distributed_ram != 150 || \
          ! $fpt_yosys_hierarchy_port_bits =~ ^[0-9]+$ || \
          ! $fpt_yosys_hierarchy_memory_bits =~ ^[0-9]+$ || \
          ! $fpt_yosys_hierarchy_cells =~ ^[0-9]+$ ]]; then
        echo "Could not validate the FPT Yosys synthesis boundary" >&2
        exit 1
    fi
fi

fpt_br_batch_cycles=unmeasured
fpt_br_cycles_per_result=unmeasured
fpt_br_input_phase_cycles=unmeasured
fpt_br_compute_phase_cycles=unmeasured
fpt_br_drain_tail_cycles=unmeasured
fpt_br_input_coefficients=unmeasured
fpt_br_key_transactions=unmeasured
fpt_br_schedule_output_beats=unmeasured
verilator_version=unavailable
if command -v verilator >/dev/null; then
    verilator_version=$(verilator --version)
fi
if [[ $skip_fpt_schedule == 0 ]] && command -v verilator >/dev/null; then
    "$repo_root/tools/measure_fpt_blind_rotate_schedule.sh" \
        "$fpt_br_sources" "$sgen_sources" "$fpt_schedule_dir"
    fpt_schedule_file=$fpt_schedule_dir/schedule.txt
    fpt_br_batch_cycles=$(awk -F= \
        '$1 == "fpt_blind_rotate_batch_cycles" { print $2 }' \
        "$fpt_schedule_file")
    fpt_br_cycles_per_result=$(awk -F= \
        '$1 == "fpt_blind_rotate_cycles_per_result" { print $2 }' \
        "$fpt_schedule_file")
    fpt_br_input_phase_cycles=$(awk -F= \
        '$1 == "fpt_blind_rotate_input_phase_cycles" { print $2 }' \
        "$fpt_schedule_file")
    fpt_br_compute_phase_cycles=$(awk -F= \
        '$1 == "fpt_blind_rotate_compute_phase_cycles" { print $2 }' \
        "$fpt_schedule_file")
    fpt_br_drain_tail_cycles=$(awk -F= \
        '$1 == "fpt_blind_rotate_drain_tail_cycles" { print $2 }' \
        "$fpt_schedule_file")
    fpt_br_input_coefficients=$(awk -F= \
        '$1 == "fpt_input_coefficients" { print $2 }' \
        "$fpt_schedule_file")
    fpt_br_key_transactions=$(awk -F= \
        '$1 == "fpt_key_transactions" { print $2 }' \
        "$fpt_schedule_file")
    fpt_br_schedule_output_beats=$(awk -F= \
        '$1 == "fpt_output_beats" { print $2 }' \
        "$fpt_schedule_file")
    if [[ ! $fpt_br_batch_cycles =~ ^[0-9]+$ || \
          ! $fpt_br_cycles_per_result =~ ^[0-9]+([.][0-9]+)?$ || \
          ! $fpt_br_input_phase_cycles =~ ^[0-9]+$ || \
          ! $fpt_br_compute_phase_cycles =~ ^[0-9]+$ || \
          ! $fpt_br_drain_tail_cycles =~ ^[0-9]+$ || \
          ! $fpt_br_input_coefficients =~ ^[0-9]+$ || \
          ! $fpt_br_key_transactions =~ ^[0-9]+$ || \
          ! $fpt_br_schedule_output_beats =~ ^[0-9]+$ ]]; then
        echo "Could not parse the FPT Blind Rotate schedule" >&2
        exit 1
    fi
fi

fpt_buffered_br_batch_cycles=unmeasured
fpt_buffered_br_cycles_per_result=unmeasured
fpt_buffered_br_input_phase_cycles=unmeasured
fpt_buffered_br_compute_phase_cycles=unmeasured
fpt_buffered_br_drain_tail_cycles=unmeasured
fpt_buffered_br_input_coefficients=unmeasured
fpt_buffered_br_key_load_coefficients=unmeasured
fpt_buffered_br_key_load_beats=unmeasured
fpt_buffered_br_key_transfer_bits=unmeasured
fpt_buffered_br_schedule_output_beats=unmeasured
if [[ $skip_fpt_schedule == 0 ]] && command -v verilator >/dev/null; then
    "$repo_root/tools/measure_fpt_buffered_blind_rotate_accelerator_schedule.sh" \
        "$fpt_buffered_br_sources" "$sgen_sources" \
        "$fpt_buffered_schedule_dir"
    fpt_buffered_schedule_file=$fpt_buffered_schedule_dir/schedule.txt
    fpt_buffered_br_batch_cycles=$(awk -F= \
        '$1 == "fpt_buffered_blind_rotate_batch_cycles" { print $2 }' \
        "$fpt_buffered_schedule_file")
    fpt_buffered_br_cycles_per_result=$(awk -F= \
        '$1 == "fpt_buffered_blind_rotate_cycles_per_result" { print $2 }' \
        "$fpt_buffered_schedule_file")
    fpt_buffered_br_input_phase_cycles=$(awk -F= \
        '$1 == "fpt_buffered_blind_rotate_input_phase_cycles" { print $2 }' \
        "$fpt_buffered_schedule_file")
    fpt_buffered_br_compute_phase_cycles=$(awk -F= \
        '$1 == "fpt_buffered_blind_rotate_compute_phase_cycles" { print $2 }' \
        "$fpt_buffered_schedule_file")
    fpt_buffered_br_drain_tail_cycles=$(awk -F= \
        '$1 == "fpt_buffered_blind_rotate_drain_tail_cycles" { print $2 }' \
        "$fpt_buffered_schedule_file")
    fpt_buffered_br_input_coefficients=$(awk -F= \
        '$1 == "fpt_input_coefficients" { print $2 }' \
        "$fpt_buffered_schedule_file")
    fpt_buffered_br_key_load_coefficients=$(awk -F= \
        '$1 == "fpt_key_load_coefficients" { print $2 }' \
        "$fpt_buffered_schedule_file")
    fpt_buffered_br_key_load_beats=$(awk -F= \
        '$1 == "fpt_key_load_beats" { print $2 }' \
        "$fpt_buffered_schedule_file")
    fpt_buffered_br_key_transfer_bits=$((
        fpt_buffered_br_key_load_beats * fpt_buffered_br_key_data_bits
    ))
    fpt_buffered_br_schedule_output_beats=$(awk -F= \
        '$1 == "fpt_output_beats" { print $2 }' \
        "$fpt_buffered_schedule_file")
    if [[ ! $fpt_buffered_br_batch_cycles =~ ^[0-9]+$ || \
          ! $fpt_buffered_br_cycles_per_result =~ ^[0-9]+([.][0-9]+)?$ || \
          ! $fpt_buffered_br_input_phase_cycles =~ ^[0-9]+$ || \
          ! $fpt_buffered_br_compute_phase_cycles =~ ^[0-9]+$ || \
          ! $fpt_buffered_br_drain_tail_cycles =~ ^[0-9]+$ || \
          ! $fpt_buffered_br_input_coefficients =~ ^[0-9]+$ || \
          ! $fpt_buffered_br_key_load_coefficients =~ ^[0-9]+$ || \
          ! $fpt_buffered_br_key_load_beats =~ ^[0-9]+$ || \
          ! $fpt_buffered_br_schedule_output_beats =~ ^[0-9]+$ ]]; then
        echo "Could not parse the buffered FPT Blind Rotate schedule" >&2
        exit 1
    fi
fi

hoge_br_batch_cycles=unmeasured
hoge_br_cycles_per_result=unmeasured
hoge_br_input_beats=unmeasured
hoge_br_key_beats_per_bus=unmeasured
hoge_br_maximum_key_beat_skew=unmeasured
hoge_br_output_beats=unmeasured
if [[ $skip_hoge_schedule == 0 ]] && command -v verilator >/dev/null; then
    "$repo_root/tools/measure_hoge_blind_rotate_schedule.sh" "$hoge_dir" \
        "$hoge_sources" "$hoge_schedule_dir"
    hoge_schedule_file=$hoge_schedule_dir/schedule.txt
    hoge_br_batch_cycles=$(awk -F= \
        '$1 == "hoge_blind_rotate_batch_cycles" { print $2 }' \
        "$hoge_schedule_file")
    hoge_br_cycles_per_result=$(awk -F= \
        '$1 == "hoge_blind_rotate_cycles_per_result" { print $2 }' \
        "$hoge_schedule_file")
    hoge_br_input_beats=$(awk -F= \
        '$1 == "hoge_input_beats" { print $2 }' \
        "$hoge_schedule_file")
    hoge_br_key_beats_per_bus=$(awk -F= \
        '$1 == "hoge_key_beats_per_bus" { print $2 }' \
        "$hoge_schedule_file")
    hoge_br_maximum_key_beat_skew=$(awk -F= \
        '$1 == "hoge_maximum_key_beat_skew" { print $2 }' \
        "$hoge_schedule_file")
    hoge_br_output_beats=$(awk -F= \
        '$1 == "hoge_output_beats" { print $2 }' \
        "$hoge_schedule_file")
    if [[ ! $hoge_br_batch_cycles =~ ^[0-9]+$ || \
          ! $hoge_br_cycles_per_result =~ ^[0-9]+([.][0-9]+)?$ || \
          ! $hoge_br_input_beats =~ ^[0-9]+$ || \
          ! $hoge_br_key_beats_per_bus =~ ^[0-9]+$ || \
          ! $hoge_br_maximum_key_beat_skew =~ ^[0-9]+$ || \
          ! $hoge_br_output_beats =~ ^[0-9]+$ ]]; then
        echo "Could not parse the HOGE Blind Rotate schedule" >&2
        exit 1
    fi
    if [[ $hoge_br_key_beats_per_bus != "$hoge_br_expected_key_beats" ]]; then
        echo "HOGE schedule consumed $hoge_br_key_beats_per_bus key beats per bus; expected $hoge_br_expected_key_beats" >&2
        exit 1
    fi
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

hash_lines() {
    printf '%s\n' "$@" | sha256sum | awk '{ print $1 }'
}

fpt_forward_sha=$(sha256 "$fpt_forward")
fpt_inverse_sha=$(sha256 "$fpt_inverse")
hoge_forward_sha=$(sha256 "$hoge_forward")
hoge_inverse_sha=$(sha256 "$hoge_inverse")
hoge_br_sha=$(sha256 "$hoge_br")
fpt_br_sha=$(sha256 "$fpt_br")
fpt_buffered_br_sha=$(sha256 "$fpt_buffered_br")
fpt_schedule_harness_sha=$(sha256 \
    "$repo_root/tests/fpt_blind_rotate_schedule.cpp")
fpt_buffered_schedule_harness_sha=$(sha256 \
    "$repo_root/tests/fpt_buffered_blind_rotate_schedule.cpp")
hoge_schedule_harness_sha=$(sha256 \
    "$repo_root/tests/hoge_blind_rotate_schedule.cpp")
fpt_schedule_flow_sha=$(sha256 \
    "$repo_root/tools/measure_fpt_blind_rotate_schedule.sh")
fpt_buffered_schedule_flow_sha=$(hash_lines \
    "$(sha256 "$repo_root/tools/measure_fpt_buffered_blind_rotate_schedule.sh")" \
    "$(sha256 "$repo_root/tools/measure_fpt_buffered_blind_rotate_accelerator_schedule.sh")")
fpt_buffered_boundary_flow_sha=$(sha256 \
    "$repo_root/tools/check_fpt_buffered_blind_rotate_boundary.sh")
hoge_schedule_flow_sha=$(sha256 \
    "$repo_root/tools/measure_hoge_blind_rotate_schedule.sh")
hoge_boundary_flow_sha=$(sha256 \
    "$repo_root/tools/check_hoge_blind_rotate_boundary.sh")
fpt_yosys_boundary_flow_sha=$(sha256 \
    "$repo_root/tools/check_fpt_synthesis_boundary.sh")
post_route_metrics_flow_sha=$(sha256 \
    "$repo_root/chisel/scripts/u280_post_route_metrics.tcl")
single_top_flow_sha=$(sha256 \
    "$repo_root/chisel/scripts/synth_sgen_u280.tcl")
composed_top_flow_sha=$(sha256 \
    "$repo_root/chisel/scripts/synth_paper_cmux_u280.tcl")
single_flow_sha=$(hash_lines \
    "$single_top_flow_sha" "$post_route_metrics_flow_sha")
composed_flow_sha=$(hash_lines \
    "$composed_top_flow_sha" "$post_route_metrics_flow_sha")

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
    printf 'verilator_version\t%s\n' "$verilator_version"
    printf 'fpt_yosys_boundary_status\t%s\n' \
        "$fpt_yosys_boundary_status"
    printf 'fpt_yosys_version\t%s\n' "$fpt_yosys_version"
    printf 'fpt_yosys_accumulator_memories_128x128\t%s\n' \
        "$fpt_yosys_accumulator_memories"
    printf 'fpt_yosys_external_product_memories_4x15360\t%s\n' \
        "$fpt_yosys_external_product_memories"
    printf 'fpt_yosys_hierarchy_port_bits\t%s\n' \
        "$fpt_yosys_hierarchy_port_bits"
    printf 'fpt_yosys_hierarchy_memory_bits\t%s\n' \
        "$fpt_yosys_hierarchy_memory_bits"
    printf 'fpt_yosys_hierarchy_cells\t%s\n' \
        "$fpt_yosys_hierarchy_cells"
    printf 'fpt_yosys_ultrascale_mapping_status\t%s\n' \
        "$fpt_yosys_ultrascale_mapping_status"
    printf 'fpt_yosys_ultrascale_accumulator_ramb36e2\t%s\n' \
        "$fpt_yosys_ultrascale_accumulator_ramb36e2"
    printf 'fpt_yosys_ultrascale_exponent_ramb18e2\t%s\n' \
        "$fpt_yosys_ultrascale_exponent_ramb18e2"
    printf 'fpt_yosys_ultrascale_sample_extract_distributed_ram\t%s\n' \
        "$fpt_yosys_ultrascale_sample_extract_distributed_ram"
    printf 'part\t%s\n' "$part"
    printf 'clock_periods_ns\t%s\n' "$period_list"
    printf 'vivado_jobs\t%s\n' "$jobs"
    printf 'selected_designs\t%s\n' "$design_list"
    printf 'polynomial_size\t1024\n'
    printf 'fpt_forward_frame_ii_cycles\t4\n'
    printf 'fpt_inverse_frame_ii_cycles\t8\n'
    printf 'fpt_external_product_complex_lanes\t256\n'
    printf 'fpt_external_product_real_products_per_complex\t3\n'
    printf 'fpt_external_product_dsps_per_real_product\t2\n'
    printf 'fpt_external_product_expected_dsps\t1536\n'
    printf 'fpt_external_product_multiplier\texact-gauss-split\n'
    printf 'hoge_forward_frame_ii_cycles\t32\n'
    printf 'hoge_inverse_frame_ii_cycles\t32\n'
    printf 'fpt_blind_rotate_dimension\t630\n'
    printf 'fpt_blind_rotate_contexts\t16\n'
    printf 'fpt_blind_rotate_top\tBatchedBlindRotateSampleExtractEngine\n'
    printf 'fpt_blind_rotate_cmux_schedule_cycles\t10080\n'
    printf 'fpt_blind_rotate_batch_cycles\t%s\n' "$fpt_br_batch_cycles"
    printf 'fpt_blind_rotate_cycles_per_result\t%s\n' \
        "$fpt_br_cycles_per_result"
    printf 'fpt_blind_rotate_input_phase_cycles\t%s\n' \
        "$fpt_br_input_phase_cycles"
    printf 'fpt_blind_rotate_compute_phase_cycles\t%s\n' \
        "$fpt_br_compute_phase_cycles"
    printf 'fpt_blind_rotate_drain_tail_cycles\t%s\n' \
        "$fpt_br_drain_tail_cycles"
    printf 'fpt_blind_rotate_input_coefficients\t%s\n' \
        "$fpt_br_input_coefficients"
    printf 'fpt_blind_rotate_key_transactions\t%s\n' \
        "$fpt_br_key_transactions"
    printf 'fpt_blind_rotate_schedule_output_beats\t%s\n' \
        "$fpt_br_schedule_output_beats"
    printf 'fpt_blind_rotate_output\tsample-extracted-tlwe\n'
    printf 'fpt_blind_rotate_output_beats\t16400\n'
    printf 'fpt_buffered_blind_rotate_dimension\t630\n'
    printf 'fpt_buffered_blind_rotate_contexts\t16\n'
    printf 'fpt_buffered_blind_rotate_top\tBufferedBlindRotateAccelerator\n'
    printf 'fpt_buffered_blind_rotate_top_ports\t%s\n' \
        "$fpt_buffered_br_top_ports"
    printf 'fpt_buffered_blind_rotate_top_port_bits\t%s\n' \
        "$fpt_buffered_br_top_port_bits"
    printf 'fpt_buffered_blind_rotate_key_load_ports\t%s\n' \
        "$fpt_buffered_br_key_data_ports"
    printf 'fpt_buffered_blind_rotate_key_load_bits\t%s\n' \
        "$fpt_buffered_br_key_data_bits"
    printf 'fpt_buffered_blind_rotate_key_interface_bits\t%s\n' \
        "$fpt_buffered_br_key_interface_bits"
    printf 'fpt_buffered_blind_rotate_input_interface_bits\t%s\n' \
        "$fpt_buffered_br_input_interface_bits"
    printf 'fpt_buffered_blind_rotate_cache_instances\t%s\n' \
        "$fpt_buffered_br_cache_instances"
    printf 'fpt_buffered_blind_rotate_cache_coefficients\t%s\n' \
        "$fpt_buffered_br_cache_banks"
    printf 'fpt_buffered_blind_rotate_cache_depth_per_coefficient\t%s\n' \
        "$fpt_buffered_br_cache_depth"
    printf 'fpt_buffered_blind_rotate_cache_word_bits\t%s\n' \
        "$fpt_buffered_br_cache_word_bits"
    printf 'fpt_buffered_blind_rotate_cache_logical_bits\t%s\n' \
        "$fpt_buffered_br_cache_logical_bits"
    printf 'fpt_buffered_blind_rotate_batch_cycles\t%s\n' \
        "$fpt_buffered_br_batch_cycles"
    printf 'fpt_buffered_blind_rotate_cycles_per_result\t%s\n' \
        "$fpt_buffered_br_cycles_per_result"
    printf 'fpt_buffered_blind_rotate_input_phase_cycles\t%s\n' \
        "$fpt_buffered_br_input_phase_cycles"
    printf 'fpt_buffered_blind_rotate_compute_phase_cycles\t%s\n' \
        "$fpt_buffered_br_compute_phase_cycles"
    printf 'fpt_buffered_blind_rotate_drain_tail_cycles\t%s\n' \
        "$fpt_buffered_br_drain_tail_cycles"
    printf 'fpt_buffered_blind_rotate_input_coefficients\t%s\n' \
        "$fpt_buffered_br_input_coefficients"
    printf 'fpt_buffered_blind_rotate_key_load_coefficients\t%s\n' \
        "$fpt_buffered_br_key_load_coefficients"
    printf 'fpt_buffered_blind_rotate_key_load_beats\t%s\n' \
        "$fpt_buffered_br_key_load_beats"
    printf 'fpt_buffered_blind_rotate_key_transfer_bits\t%s\n' \
        "$fpt_buffered_br_key_transfer_bits"
    printf 'fpt_buffered_blind_rotate_schedule_output_beats\t%s\n' \
        "$fpt_buffered_br_schedule_output_beats"
    printf 'fpt_buffered_blind_rotate_output\tsample-extracted-tlwe\n'
    printf 'fpt_buffered_blind_rotate_output_beats\t16400\n'
    printf 'hoge_blind_rotate_dimension\t636\n'
    printf 'hoge_blind_rotate_contexts\t2\n'
    printf 'hoge_blind_rotate_top\tHOGEBlindRotateBaseline\n'
    printf 'hoge_blind_rotate_top_ports\t%s\n' "$hoge_br_top_ports"
    printf 'hoge_blind_rotate_top_port_bits\t%s\n' \
        "$hoge_br_top_port_bits"
    printf 'hoge_blind_rotate_key_streams\t%s\n' "$hoge_br_key_streams"
    printf 'hoge_blind_rotate_key_data_bits\t%s\n' \
        "$hoge_br_key_data_bits"
    printf 'hoge_blind_rotate_key_interface_bits\t%s\n' \
        "$hoge_br_key_interface_bits"
    printf 'hoge_blind_rotate_tlwe_interface_bits\t%s\n' \
        "$hoge_br_tlwe_interface_bits"
    printf 'hoge_blind_rotate_result_interface_bits\t%s\n' \
        "$hoge_br_result_interface_bits"
    printf 'hoge_blind_rotate_trgsw_cache_instances\t%s\n' \
        "$hoge_br_cache_instances"
    printf 'hoge_blind_rotate_cache_banks_per_instance\t%s\n' \
        "$hoge_br_cache_banks"
    printf 'hoge_blind_rotate_cache_depth_per_bank\t%s\n' \
        "$hoge_br_cache_depth"
    printf 'hoge_blind_rotate_cache_word_bits\t%s\n' \
        "$hoge_br_cache_word_bits"
    printf 'hoge_blind_rotate_cache_logical_bits\t%s\n' \
        "$hoge_br_cache_logical_bits"
    printf 'hoge_blind_rotate_key_transfer_bits\t%s\n' \
        "$hoge_br_key_transfer_bits"
    printf 'hoge_blind_rotate_output\tsample-extracted-tlwe\n'
    printf 'hoge_blind_rotate_batch_cycles\t%s\n' "$hoge_br_batch_cycles"
    printf 'hoge_blind_rotate_cycles_per_result\t%s\n' \
        "$hoge_br_cycles_per_result"
    printf 'hoge_blind_rotate_input_beats\t%s\n' "$hoge_br_input_beats"
    printf 'hoge_blind_rotate_key_beats_per_bus\t%s\n' \
        "$hoge_br_key_beats_per_bus"
    printf 'hoge_blind_rotate_maximum_key_beat_skew\t%s\n' \
        "$hoge_br_maximum_key_beat_skew"
    printf 'hoge_blind_rotate_output_beats\t%s\n' "$hoge_br_output_beats"
    printf 'hoge_forward_intorus_mul_instances\t%s\n' "$hoge_forward_multipliers"
    printf 'hoge_inverse_intorus_mul_instances\t%s\n' "$hoge_inverse_multipliers"
    printf 'hoge_blind_rotate_intorus_mul_instances\t%s\n' "$hoge_br_multipliers"
    printf 'fpt_forward_sha256\t%s\n' "$fpt_forward_sha"
    printf 'fpt_inverse_sha256\t%s\n' "$fpt_inverse_sha"
    printf 'hoge_forward_sha256\t%s\n' "$hoge_forward_sha"
    printf 'hoge_inverse_sha256\t%s\n' "$hoge_inverse_sha"
    printf 'fpt_blind_rotate_sha256\t%s\n' "$fpt_br_sha"
    printf 'fpt_buffered_blind_rotate_sha256\t%s\n' \
        "$fpt_buffered_br_sha"
    printf 'hoge_blind_rotate_sha256\t%s\n' "$hoge_br_sha"
    printf 'fpt_blind_rotate_schedule_harness_sha256\t%s\n' \
        "$fpt_schedule_harness_sha"
    printf 'fpt_buffered_blind_rotate_schedule_harness_sha256\t%s\n' \
        "$fpt_buffered_schedule_harness_sha"
    printf 'hoge_blind_rotate_schedule_harness_sha256\t%s\n' \
        "$hoge_schedule_harness_sha"
    printf 'fpt_blind_rotate_schedule_flow_sha256\t%s\n' \
        "$fpt_schedule_flow_sha"
    printf 'fpt_buffered_blind_rotate_schedule_flow_sha256\t%s\n' \
        "$fpt_buffered_schedule_flow_sha"
    printf 'fpt_buffered_blind_rotate_boundary_flow_sha256\t%s\n' \
        "$fpt_buffered_boundary_flow_sha"
    printf 'hoge_blind_rotate_schedule_flow_sha256\t%s\n' \
        "$hoge_schedule_flow_sha"
    printf 'hoge_blind_rotate_boundary_flow_sha256\t%s\n' \
        "$hoge_boundary_flow_sha"
    printf 'fpt_yosys_boundary_flow_sha256\t%s\n' \
        "$fpt_yosys_boundary_flow_sha"
    printf 'post_route_metrics_flow_sha256\t%s\n' \
        "$post_route_metrics_flow_sha"
    printf 'single_source_top_flow_sha256\t%s\n' "$single_top_flow_sha"
    printf 'composed_top_flow_sha256\t%s\n' "$composed_top_flow_sha"
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
        -source "$repo_root/chisel/scripts/synth_sgen_u280.tcl" \
        -tclargs "$source_file" "$top" "$run_dir" "$period" "$part" \
            "$jobs" "$clock_port"
}

run_fpt_blind_rotate() {
    local design=$1
    local source_file=$2
    local source_sha=$3
    local top=$4
    local period=$5
    local period_tag=${period//./p}
    local run_dir=$runs_dir/period-$period_tag/$design
    local signature

    signature=$(printf '%s\n' "$design" "$source_sha" "$fpt_forward_sha" \
        "$fpt_inverse_sha" "$composed_flow_sha" "$part" "$period" "$jobs" \
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
        -source "$repo_root/chisel/scripts/synth_paper_cmux_u280.tcl" \
        -tclargs "$source_file" "$fpt_forward" "$fpt_inverse" "$run_dir" \
            "$period" "$top" "$part" "$jobs"
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
                run_fpt_blind_rotate "$design" "$fpt_br" "$fpt_br_sha" \
                    BatchedBlindRotateSampleExtractEngine "$period" ;;
            fpt-buffered-blind-rotate)
                run_fpt_blind_rotate "$design" "$fpt_buffered_br" \
                    "$fpt_buffered_br_sha" BufferedBlindRotateAccelerator \
                    "$period" ;;
            hoge-blind-rotate)
                run_single "$design" "$hoge_br" HOGEBlindRotateBaseline clock "$period" ;;
        esac
    done
done

"$repo_root/tools/report_u280_fpt_hoge_comparison.sh" "$output_root"

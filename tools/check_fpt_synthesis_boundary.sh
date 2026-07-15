#!/usr/bin/env bash
set -euo pipefail

script_path=$(realpath "${BASH_SOURCE[0]}")
repo_root=$(cd "$(dirname "$script_path")/.." && pwd)
source_dir=${1:-$repo_root/build/chisel-paper-bitwise-blind-rotate-sample-extract}
sgen_dir=${2:-$repo_root/build/sgen-fpt}
build_dir=${3:-$repo_root/build/fpt-synthesis-boundary}
source_file=$source_dir/BatchedBlindRotateSampleExtractEngine.sv
forward=$sgen_dir/forward.v
inverse=$sgen_dir/inverse.v
top=BatchedBlindRotateSampleExtractEngine

for tool in jq yosys; do
    if ! command -v "$tool" >/dev/null; then
        echo "$tool is required for the FPT synthesis-boundary check" >&2
        exit 1
    fi
done
for input_file in "$source_file" "$forward" "$inverse"; do
    if [[ ! -s $input_file ]]; then
        echo "Missing FPT synthesis input: $input_file" >&2
        exit 1
    fi
done

source_file=$(realpath "$source_file")
forward=$(realpath "$forward")
inverse=$(realpath "$inverse")
build_dir=$(realpath -m "$build_dir")
mkdir -p "$build_dir"
if [[ $build_dir == *[[:space:]]* ]]; then
    echo "Yosys output paths must not contain whitespace: $build_dir" >&2
    exit 1
fi

if rg -q '\bautomatic\b' "$source_file"; then
    echo "CIRCT left block-local automatic declarations in $source_file" >&2
    echo "Emit the design with disallowLocalVariables enabled" >&2
    exit 1
fi
if rg -q ':[[:space:]]+assert\(' "$source_file"; then
    echo "CIRCT left unguarded immediate assertions in $source_file" >&2
    exit 1
fi
for pattern in \
    '^module BatchedBlindRotateSampleExtractEngine\(' \
    '^module mem_120x128\(' \
    '^module exponentMemory_9450x11\(' \
    '^module maskMemory_16x2048\('; do
    if ! rg -q "$pattern" "$source_file"; then
        echo "Missing expected synthesis structure $pattern in $source_file" >&2
        exit 1
    fi
done

accumulator_memories=$(awk '
    /^  mem_120x128 / { count++ }
    END { print count + 0 }
' "$source_file")
if [[ $accumulator_memories != 128 ]]; then
    echo "Expected 128 replicated accumulator memories, found $accumulator_memories" >&2
    exit 1
fi
if ! rg -q 'reg \[127:0\] Memory\[0:119\];' "$source_file" || \
   ! rg -q 'reg \[10:0\] Memory\[0:9449\];' "$source_file" || \
   ! rg -q 'reg \[2047:0\] Memory\[0:15\];' "$source_file"; then
    echo "An expected Chisel memory shape changed in $source_file" >&2
    exit 1
fi

yosys_version=$(yosys -V)
signature=$(
    {
        for signature_input in \
            "$source_file" "$forward" "$inverse" "$script_path"; do
            sha256sum "$signature_input" | awk '{ print $1 }'
        done
        printf '%s\n' "$yosys_version" "$top"
    } | sha256sum | awk '{ print $1 }'
)
signature_file=$build_dir/input.sha256
metrics_file=$build_dir/metrics.tsv
if [[ -s $metrics_file && -s $signature_file && \
      $(<"$signature_file") == "$signature" ]]; then
    echo "Reusing the matching FPT Yosys boundary check in $build_dir" >&2
    cat "$metrics_file"
    exit 0
fi

log_file=$build_dir/yosys.log
console_file=$build_dir/yosys-console.log
yosys_command="read_verilog -sv -DSYNTHESIS \"$source_file\"; "
yosys_command+="read_verilog \"$forward\"; "
yosys_command+="read_verilog \"$inverse\"; "
yosys_command+="hierarchy -check -top $top; check; stat"
if ! yosys -ql "$log_file" -p "$yosys_command" \
    >"$console_file" 2>&1; then
    echo "Yosys could not elaborate the full FPT synthesis boundary" >&2
    tail -100 "$console_file" >&2
    exit 1
fi

unexpected_warnings=$(awk '
    /^Warning:/ && $0 !~ /Replacing memory .* with list of registers/ {
        count++
    }
    END { print count + 0 }
' "$log_file")
if [[ $unexpected_warnings != 0 ]]; then
    echo "Yosys reported $unexpected_warnings unexpected warnings" >&2
    rg '^Warning:' "$log_file" | \
        rg -v 'Replacing memory .* with list of registers' >&2 || true
    exit 1
fi
if rg -q '^[[:space:]]+\$check[[:space:]]' "$log_file"; then
    echo "Yosys retained formal check cells in the synthesis hierarchy" >&2
    exit 1
fi

run_ultrascale_memory_map() {
    local name=$1
    local map_top=$2
    local setup=$3
    local map_log=$build_dir/$name-yosys.log
    local map_console=$build_dir/$name-yosys-console.log
    local map_stat=$build_dir/$name-stat.json
    local map_command="read_verilog -sv -DSYNTHESIS \"$source_file\"; "
    map_command+="$setup"
    map_command+="synth_xilinx -family xcup -top $map_top -flatten "
    map_command+="-noiopad -noclkbuf -widemux 5; "
    map_command+="tee -o $map_stat stat -tech xilinx -json"
    if ! yosys -ql "$map_log" -p "$map_command" \
        >"$map_console" 2>&1; then
        echo "Yosys could not map the $name memory context" >&2
        tail -100 "$map_console" >&2
        return 1
    fi
}

run_ultrascale_memory_map \
    accumulator ReplicatedAccumulatorBanks '' &
accumulator_map_pid=$!
run_ultrascale_memory_map \
    exponent BatchedBlindRotateEngine \
    'blackbox BatchedCmuxEngine; select -clear; ' &
exponent_map_pid=$!
run_ultrascale_memory_map \
    sample_extract SampleExtractIndexZero '' &
sample_extract_map_pid=$!
memory_map_failed=0
for map_pid in \
    "$accumulator_map_pid" "$exponent_map_pid" "$sample_extract_map_pid"; do
    if ! wait "$map_pid"; then
        memory_map_failed=1
    fi
done
if [[ $memory_map_failed != 0 ]]; then
    exit 1
fi

mapped_cell_count() {
    local stat_file=$1
    local map_top=$2
    local cell_type=$3
    jq -er --arg module "\\$map_top" --arg cell "$cell_type" \
        '.modules[$module].num_cells_by_type[$cell] // 0' "$stat_file"
}

mapped_total_cells() {
    local stat_file=$1
    local map_top=$2
    jq -er --arg module "\\$map_top" \
        '.modules[$module].num_cells' "$stat_file"
}

mapped_distributed_ram() {
    local stat_file=$1
    local map_top=$2
    jq -er --arg module "\\$map_top" '
        [
            .modules[$module].num_cells_by_type
            | to_entries[]
            | select(.key | test("^RAM(16|32|64|128|256|512)"))
            | .value
        ] | add // 0
    ' "$stat_file"
}

accumulator_map_stat=$build_dir/accumulator-stat.json
exponent_map_stat=$build_dir/exponent-stat.json
sample_extract_map_stat=$build_dir/sample_extract-stat.json
accumulator_ramb18e2=$(mapped_cell_count \
    "$accumulator_map_stat" ReplicatedAccumulatorBanks RAMB18E2)
accumulator_ramb36e2=$(mapped_cell_count \
    "$accumulator_map_stat" ReplicatedAccumulatorBanks RAMB36E2)
accumulator_distributed_ram=$(mapped_distributed_ram \
    "$accumulator_map_stat" ReplicatedAccumulatorBanks)
accumulator_mapped_cells=$(mapped_total_cells \
    "$accumulator_map_stat" ReplicatedAccumulatorBanks)
exponent_ramb18e2=$(mapped_cell_count \
    "$exponent_map_stat" BatchedBlindRotateEngine RAMB18E2)
exponent_ramb36e2=$(mapped_cell_count \
    "$exponent_map_stat" BatchedBlindRotateEngine RAMB36E2)
exponent_distributed_ram=$(mapped_distributed_ram \
    "$exponent_map_stat" BatchedBlindRotateEngine)
exponent_mapped_cells=$(mapped_total_cells \
    "$exponent_map_stat" BatchedBlindRotateEngine)
sample_extract_ramb18e2=$(mapped_cell_count \
    "$sample_extract_map_stat" SampleExtractIndexZero RAMB18E2)
sample_extract_ramb36e2=$(mapped_cell_count \
    "$sample_extract_map_stat" SampleExtractIndexZero RAMB36E2)
sample_extract_ram32m16=$(mapped_cell_count \
    "$sample_extract_map_stat" SampleExtractIndexZero RAM32M16)
sample_extract_distributed_ram=$(mapped_distributed_ram \
    "$sample_extract_map_stat" SampleExtractIndexZero)
sample_extract_mapped_cells=$(mapped_total_cells \
    "$sample_extract_map_stat" SampleExtractIndexZero)

if [[ $accumulator_ramb18e2 != 0 || \
      $accumulator_ramb36e2 != 256 || \
      $accumulator_distributed_ram != 0 || \
      $exponent_ramb18e2 != 9 || \
      $exponent_ramb36e2 != 0 || \
      $exponent_distributed_ram != 0 || \
      $sample_extract_ramb18e2 != 0 || \
      $sample_extract_ramb36e2 != 0 || \
      $sample_extract_ram32m16 != 150 || \
      $sample_extract_distributed_ram != 150 ]]; then
    echo "The FPT UltraScale+ memory mapping changed unexpectedly" >&2
    jq '.modules[].num_cells_by_type' \
        "$accumulator_map_stat" "$exponent_map_stat" \
        "$sample_extract_map_stat" >&2
    exit 1
fi

sgen_register_array_warnings=$(awk '
    /^Warning: Replacing memory .* with list of registers/ { count++ }
    END { print count + 0 }
' "$log_file")
hierarchy_port_bits=$(awk '
    /Number of port bits:/ { value = $5 }
    END { print value }
' "$log_file")
hierarchy_memory_bits=$(awk '
    /Number of memory bits:/ { value = $5 }
    END { print value }
' "$log_file")
hierarchy_cells=$(awk '
    /Number of cells:/ { value = $4 }
    END { print value }
' "$log_file")
for value in \
    "$hierarchy_port_bits" "$hierarchy_memory_bits" "$hierarchy_cells"; do
    if [[ ! $value =~ ^[0-9]+$ ]]; then
        echo "Could not parse the full-design Yosys statistics" >&2
        exit 1
    fi
done

{
    printf '%s\n' $'metric\tvalue'
    printf 'status\tpassed\n'
    printf 'top\t%s\n' "$top"
    printf 'yosys_version\t%s\n' "$yosys_version"
    printf 'automatic_declarations\t0\n'
    printf 'formal_check_cells\t0\n'
    printf 'accumulator_memories_120x128\t%s\n' "$accumulator_memories"
    printf 'accumulator_memory_bits\t1966080\n'
    printf 'exponent_memory_bits\t103950\n'
    printf 'sample_extract_memory_bits\t32768\n'
    printf 'hierarchy_port_bits\t%s\n' "$hierarchy_port_bits"
    printf 'hierarchy_memory_bits\t%s\n' "$hierarchy_memory_bits"
    printf 'hierarchy_cells\t%s\n' "$hierarchy_cells"
    printf 'ultrascale_mapping_status\tpassed\n'
    printf 'ultrascale_accumulator_ramb18e2\t%s\n' \
        "$accumulator_ramb18e2"
    printf 'ultrascale_accumulator_ramb36e2\t%s\n' \
        "$accumulator_ramb36e2"
    printf 'ultrascale_accumulator_distributed_ram\t%s\n' \
        "$accumulator_distributed_ram"
    printf 'ultrascale_accumulator_mapped_cells\t%s\n' \
        "$accumulator_mapped_cells"
    printf 'ultrascale_exponent_ramb18e2\t%s\n' "$exponent_ramb18e2"
    printf 'ultrascale_exponent_ramb36e2\t%s\n' "$exponent_ramb36e2"
    printf 'ultrascale_exponent_distributed_ram\t%s\n' \
        "$exponent_distributed_ram"
    printf 'ultrascale_exponent_mapped_cells\t%s\n' \
        "$exponent_mapped_cells"
    printf 'ultrascale_sample_extract_ramb18e2\t%s\n' \
        "$sample_extract_ramb18e2"
    printf 'ultrascale_sample_extract_ramb36e2\t%s\n' \
        "$sample_extract_ramb36e2"
    printf 'ultrascale_sample_extract_ram32m16\t%s\n' \
        "$sample_extract_ram32m16"
    printf 'ultrascale_sample_extract_distributed_ram\t%s\n' \
        "$sample_extract_distributed_ram"
    printf 'ultrascale_sample_extract_mapped_cells\t%s\n' \
        "$sample_extract_mapped_cells"
    printf 'sgen_register_array_warnings\t%s\n' \
        "$sgen_register_array_warnings"
    printf 'unexpected_warnings\t0\n'
} > "$metrics_file"
printf '%s\n' "$signature" > "$signature_file"
cat "$metrics_file"

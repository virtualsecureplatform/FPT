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

if ! command -v yosys >/dev/null; then
    echo "yosys is required for the FPT synthesis-boundary check" >&2
    exit 1
fi
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
        sha256sum "$source_file" "$forward" "$inverse" "$script_path"
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

sgen_register_array_warnings=$(awk '
    /^Warning: Replacing memory .* with list of registers/ { count++ }
    END { print count + 0 }
' "$log_file")
total_port_bits=$(awk '
    /Number of port bits:/ { value = $5 }
    END { print value }
' "$log_file")
total_memory_bits=$(awk '
    /Number of memory bits:/ { value = $5 }
    END { print value }
' "$log_file")
total_cells=$(awk '
    /Number of cells:/ { value = $4 }
    END { print value }
' "$log_file")
for value in "$total_port_bits" "$total_memory_bits" "$total_cells"; do
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
    printf 'total_port_bits\t%s\n' "$total_port_bits"
    printf 'total_memory_bits\t%s\n' "$total_memory_bits"
    printf 'total_cells\t%s\n' "$total_cells"
    printf 'sgen_register_array_warnings\t%s\n' \
        "$sgen_register_array_warnings"
    printf 'unexpected_warnings\t0\n'
} > "$metrics_file"
printf '%s\n' "$signature" > "$signature_file"
cat "$metrics_file"

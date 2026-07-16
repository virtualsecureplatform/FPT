#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
build_root=${1:-$repo_root/build/yosys-fpt-buffered-blind-rotate-accelerator}
sgen_dir=${2:-$repo_root/build/sgen-fpt}
source_dir=$build_root/source
result_dir=$build_root/result
source_file=$source_dir/BufferedBlindRotateAccelerator.sv
forward=$sgen_dir/forward.v
inverse=$sgen_dir/inverse.v
stat_file=$result_dir/stat.json
timing_file=$result_dir/timing.txt
log_file=$result_dir/synth.log
signature_file=$result_dir/input.sha256
top=BufferedBlindRotateAccelerator
expected_dsps=5408
expected_bram=457

for tool in jq rg sha256sum yosys; do
    if ! command -v "$tool" >/dev/null; then
        echo "Required tool not found: $tool" >&2
        exit 1
    fi
done
if [[ ! -x /usr/bin/time ]]; then
    echo "/usr/bin/time is required" >&2
    exit 1
fi

build_root=$(realpath -m "$build_root")
sgen_dir=$(realpath "$sgen_dir")
forward=$(realpath "$forward")
inverse=$(realpath "$inverse")
if [[ $build_root == *[[:space:]]* || $sgen_dir == *[[:space:]]* ]]; then
    echo "Yosys source and build paths must not contain whitespace" >&2
    exit 1
fi
mkdir -p "$source_dir" "$result_dir"
"$repo_root/tools/emit_paper_buffered_blind_rotate_accelerator.sh" \
    "$forward" "$inverse" "$source_dir"

forward_products=$(rg -c '\$signed\([^)]*\) \* \$signed\(' "$forward")
inverse_products=$(rg -c '\$signed\([^)]*\) \* \$signed\(' "$inverse")
gauss_modules=$(rg -c '^module FptExactGaussComplexMultiply ' \
    "$source_file")
split_instances=$(rg -c '^  FptSignedSplitMultiply #' "$source_file")
inverse_instances=$(rg -c '^  FptSGenInverse generated ' "$source_file")
block_memories=$(rg -c 'ram_style = "block"' "$source_file")
calculated_dsps=$((forward_products + inverse_products + 1536 + 2))
if [[ $calculated_dsps != "$expected_dsps" || $gauss_modules != 1 || \
      $split_instances != 3 || $inverse_instances != 1 || \
      $block_memories != 1 ]]; then
    echo "Unexpected physical FPT structure: forward_products=$forward_products inverse_products=$inverse_products calculated_dsps=$calculated_dsps gauss_modules=$gauss_modules split_instances=$split_instances inverse_instances=$inverse_instances block_memories=$block_memories" >&2
    exit 1
fi

source_hash=$(sha256sum "$source_file" | awk '{ print $1 }')
forward_hash=$(sha256sum "$forward" | awk '{ print $1 }')
inverse_hash=$(sha256sum "$inverse" | awk '{ print $1 }')
yosys_version=$(yosys -V)
flow='synth_xilinx -family xcup -flatten -noiopad -noclkbuf -nosrl; check; stat -tech xilinx -json'
signature=$(
    printf '%s\n' "$source_hash" "$forward_hash" "$inverse_hash" \
        "$top" "$yosys_version" "$flow" | sha256sum | awk '{ print $1 }'
)

sum_cells() {
    local pattern=$1
    jq -r --arg pattern "$pattern" '
        [(.design.num_cells_by_type // {} | to_entries[])
          | select(.key | test($pattern)) | .value] | add // 0
    ' "$stat_file"
}

validate_result() {
    local bram
    local dsp
    jq -e --arg top "\\$top" \
        '.modules[$top].num_cells > 0 and .design.num_cells > 0' \
        "$stat_file" >/dev/null
    bram=$(sum_cells '^RAMB')
    dsp=$(sum_cells '^DSP')
    if [[ $bram != "$expected_bram" || $dsp != "$expected_dsps" ]]; then
        echo "Physical FPT mapping contract failed: expected bram=$expected_bram dsp=$expected_dsps; actual bram=$bram dsp=$dsp" >&2
        exit 1
    fi
    echo "Validated physical FPT mapping: $bram BRAM and $dsp DSP48E2 primitives"
}

if [[ -s $stat_file && -s $signature_file && \
      $(<"$signature_file") == "$signature" ]]; then
    echo "Reusing signature-matched physical FPT map"
    validate_result
else
    yosys_command="read_verilog -sv -DSYNTHESIS $source_file; "
    yosys_command+="read_verilog $forward; read_verilog $inverse; "
    yosys_command+="synth_xilinx -family xcup -top $top -flatten "
    yosys_command+="-noiopad -noclkbuf -nosrl; check; "
    yosys_command+="tee -o $stat_file stat -tech xilinx -json"
    rm -f "$stat_file" "$timing_file" "$signature_file"
    echo "Synthesizing the complete physical FPT Blind Rotate accelerator"
    if ! /usr/bin/time \
        -f 'elapsed_seconds=%e\nmax_rss_kib=%M' \
        -o "$timing_file" \
        yosys -q -l "$log_file" -p "$yosys_command" \
        >"$result_dir/console.log" 2>&1; then
        tail -n 100 "$result_dir/console.log" >&2
        exit 1
    fi
    validate_result
    printf '%s\n' "$signature" > "$signature_file"
fi

logic_cells=$(jq -r '.design.estimated_num_lc // 0' "$stat_file")
total_cells=$(jq -r '.design.num_cells // 0' "$stat_file")
luts=$(sum_cells '^LUT[1-6]$')
ffs=$(sum_cells '^FD')
bram=$(sum_cells '^RAMB')
all_ram=$(sum_cells '^RAM')
distributed_ram=$((all_ram - bram))
dsp=$(sum_cells '^DSP')
carry=$(sum_cells '^CARRY')
muxf=$(sum_cells '^MUXF')
elapsed=$(sed -n 's/^elapsed_seconds=//p' "$timing_file")
max_rss=$(sed -n 's/^max_rss_kib=//p' "$timing_file")
{
    printf 'cycles-per-result\tlogic-cells\tluts\tffs\tbram\tdist-ram\tdsp\tcarry\tmuxf\ttotal-cells\telapsed-s\tmax-rss-kib\n'
    printf '11915.4\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$logic_cells" "$luts" "$ffs" "$bram" "$distributed_ram" \
        "$dsp" "$carry" "$muxf" "$total_cells" "$elapsed" "$max_rss"
} | tee "$build_root/summary.tsv"

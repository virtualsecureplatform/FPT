#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
build_root=${1:-$repo_root/build/yosys-fpt-external-product}
source_dir=$build_root/source
result_dir=$build_root/result
source_file=$source_dir/DoubleBufferedExternalProductAccumulator.sv
stat_file=$result_dir/stat.json
timing_file=$result_dir/timing.txt
log_file=$result_dir/synth.log
signature_file=$result_dir/input.sha256
top=DoubleBufferedExternalProductAccumulator
expected_dsps=1536

for tool in jq sha256sum yosys; do
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
if [[ $build_root == *[[:space:]]* ]]; then
    echo "Yosys build path must not contain whitespace" >&2
    exit 1
fi
mkdir -p "$source_dir" "$result_dir"
"$repo_root/tools/emit_paper_external_product.sh" "$source_dir"

source_hash=$(sha256sum "$source_file" | awk '{ print $1 }')
yosys_version=$(yosys -V)
flow='synth_xilinx -family xcup -flatten -noiopad -noclkbuf -nosrl; check; stat -tech xilinx -json'
signature=$(
    printf '%s\n' "$source_hash" "$top" "$yosys_version" "$flow" |
        sha256sum | awk '{ print $1 }'
)

mapped_dsps() {
    jq -r '
        [(.design.num_cells_by_type // {} | to_entries[])
          | select(.key | test("^DSP"))
          | .value]
        | add // 0
    ' "$stat_file"
}

validate_result() {
    local actual_dsps
    jq -e '.design.num_cells > 0' "$stat_file" >/dev/null
    actual_dsps=$(mapped_dsps)
    if [[ $actual_dsps != "$expected_dsps" ]]; then
        echo "External Product DSP contract failed: expected=$expected_dsps actual=$actual_dsps" >&2
        exit 1
    fi
    echo "Validated External Product mapping: $actual_dsps DSP48E2 primitives"
}

if [[ -s $stat_file && -s $signature_file && \
      $(<"$signature_file") == "$signature" ]]; then
    echo "Reusing signature-matched External Product map"
    validate_result
else
    yosys_command="read_verilog -sv -DSYNTHESIS $source_file; "
    yosys_command+="synth_xilinx -family xcup -top $top -flatten "
    yosys_command+="-noiopad -noclkbuf -nosrl; check; "
    yosys_command+="tee -o $stat_file stat -tech xilinx -json"
    rm -f "$stat_file" "$timing_file" "$signature_file"
    echo "Synthesizing the paper-shaped External Product for UltraScale+"
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
luts=$(jq -r '
    [(.design.num_cells_by_type // {} | to_entries[])
      | select(.key | test("^LUT[1-6]$")) | .value] | add // 0
' "$stat_file")
ffs=$(jq -r '
    [(.design.num_cells_by_type // {} | to_entries[])
      | select(.key | test("^FD")) | .value] | add // 0
' "$stat_file")
bram=$(jq -r '
    [(.design.num_cells_by_type // {} | to_entries[])
      | select(.key | test("^RAMB")) | .value] | add // 0
' "$stat_file")
distributed_ram=$(jq -r '
    [(.design.num_cells_by_type // {} | to_entries[])
      | select(.key | test("^RAM(16|32|64|128|256|512)"))
      | .value] | add // 0
' "$stat_file")
carry=$(jq -r '
    [(.design.num_cells_by_type // {} | to_entries[])
      | select(.key | test("^CARRY")) | .value] | add // 0
' "$stat_file")
muxf=$(jq -r '
    [(.design.num_cells_by_type // {} | to_entries[])
      | select(.key | test("^MUXF")) | .value] | add // 0
' "$stat_file")
elapsed=$(sed -n 's/^elapsed_seconds=//p' "$timing_file")
max_rss=$(sed -n 's/^max_rss_kib=//p' "$timing_file")
{
    printf 'logic-cells\tluts\tffs\tbram\tdist-ram\tdsp\tcarry\tmuxf\telapsed-s\tmax-rss-kib\n'
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$logic_cells" "$luts" "$ffs" "$bram" "$distributed_ram" \
        "$expected_dsps" "$carry" "$muxf" "$elapsed" "$max_rss"
} | tee "$build_root/summary.tsv"

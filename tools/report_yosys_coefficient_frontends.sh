#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
build_root=${1:-$repo_root/build/yosys-coeff}
if (( $# > 0 )); then
    shift
fi

if (( $# > 0 )); then
    designs=("$@")
else
    designs=(barrel-single bitwise-single)
fi

top_for() {
    case "$1" in
        barrel-single) echo CmuxCoefficientStore ;;
        bitwise-single) echo BitwiseCmuxForwardFrontend ;;
        barrel-batched) echo PrefetchedBatchedCmuxCoefficientStore ;;
        bitwise-batched) echo BitwisePrefetchedBatchedCmuxCoefficientStore ;;
        *)
            echo "Unknown coefficient frontend: $1" >&2
            return 1
            ;;
    esac
}

sum_cells() {
    local stat_file=$1
    local module=$2
    local pattern=$3
    jq -r --arg module "$module" --arg pattern "$pattern" '
        [(.modules[$module].num_cells_by_type // {} | to_entries[])
          | select(.key | test($pattern))
          | .value]
        | add // 0
    ' "$stat_file"
}

printf 'design\tlogic-cells\tluts\tffs\tcarry\tmuxf\tram\telapsed-s\tmax-rss-kib\n'
for design in "${designs[@]}"; do
    top=$(top_for "$design")
    result_dir=$build_root/$design
    stat_file=$result_dir/stat.json
    timing_file=$result_dir/timing.txt
    if [[ ! -s $stat_file ]]; then
        echo "Missing Yosys statistics: $stat_file" >&2
        exit 1
    fi

    module="\\$top"
    logic_cells=$(jq -r --arg module "$module" \
        '.modules[$module].estimated_num_lc // 0' "$stat_file")
    luts=$(sum_cells "$stat_file" "$module" '^LUT[1-6]$')
    ffs=$(sum_cells "$stat_file" "$module" '^FD')
    carry=$(sum_cells "$stat_file" "$module" '^CARRY')
    muxf=$(sum_cells "$stat_file" "$module" '^MUXF')
    ram=$(sum_cells "$stat_file" "$module" '^(RAM|RAMB|URAM)')
    elapsed=$(sed -n 's/^elapsed_seconds=//p' "$timing_file" 2>/dev/null || true)
    max_rss=$(sed -n 's/^max_rss_kib=//p' "$timing_file" 2>/dev/null || true)

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$design" "$logic_cells" "$luts" "$ffs" "$carry" "$muxf" \
        "$ram" "${elapsed:--}" "${max_rss:--}"
done

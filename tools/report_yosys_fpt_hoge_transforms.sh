#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
build_root=${1:-$repo_root/build/yosys-fpt-hoge-transforms}
if (( $# > 0 )); then
    shift
fi

if (( $# > 0 )); then
    designs=("$@")
else
    designs=(fpt-forward hoge-forward fpt-inverse hoge-inverse)
fi

for tool in jq awk; do
    if ! command -v "$tool" >/dev/null; then
        echo "Required tool not found: $tool" >&2
        exit 1
    fi
done

parameters_for() {
    case "$1" in
        fpt-forward) echo 'forward fpt 4' ;;
        hoge-forward) echo 'forward hoge 32' ;;
        fpt-inverse) echo 'inverse fpt 8' ;;
        hoge-inverse) echo 'inverse hoge 32' ;;
        *)
            echo "Unknown transform design: $1" >&2
            return 1
            ;;
    esac
}

sum_cells() {
    local stat_file=$1
    local pattern=$2
    jq -r --arg pattern "$pattern" '
        [(.design.num_cells_by_type // {} | to_entries[])
          | select(.key | test($pattern))
          | .value]
        | add // 0
    ' "$stat_file"
}

summary=$build_root/summary.tsv
mkdir -p "$build_root"
{
    printf 'design\trole\timplementation\tii\tlogic-cells\tluts\tffs\tcarry\tmuxf\tbram\tdist-ram\tsrl\tdsp\ttotal-cells\telapsed-s\tmax-rss-kib\n'
    for design in "${designs[@]}"; do
        read -r role implementation ii <<<"$(parameters_for "$design")"
        result_dir=$build_root/$design
        stat_file=$result_dir/stat.json
        timing_file=$result_dir/timing.txt
        if [[ ! -s $stat_file ]]; then
            echo "Missing Yosys statistics: $stat_file" >&2
            exit 1
        fi

        logic_cells=$(jq -r '.design.estimated_num_lc // 0' "$stat_file")
        total_cells=$(jq -r '.design.num_cells // 0' "$stat_file")
        luts=$(sum_cells "$stat_file" '^LUT[1-6]$')
        ffs=$(sum_cells "$stat_file" '^FD')
        carry=$(sum_cells "$stat_file" '^CARRY')
        muxf=$(sum_cells "$stat_file" '^MUXF')
        all_ram=$(sum_cells "$stat_file" '^RAM')
        bram=$(sum_cells "$stat_file" '^RAMB')
        dist_ram=$((all_ram - bram))
        srl=$(sum_cells "$stat_file" '^SRL')
        dsp=$(sum_cells "$stat_file" '^DSP')
        elapsed=$(sed -n 's/^elapsed_seconds=//p' "$timing_file" \
            2>/dev/null || true)
        max_rss=$(sed -n 's/^max_rss_kib=//p' "$timing_file" \
            2>/dev/null || true)

        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$design" "$role" "$implementation" "$ii" \
            "$logic_cells" "$luts" "$ffs" "$carry" "$muxf" \
            "$bram" "$dist_ram" "$srl" "$dsp" "$total_cells" \
            "${elapsed:--}" "${max_rss:--}"
    done
} | tee "$summary"

comparison=$build_root/comparison.tsv
{
    printf 'role\tfpt-ii\thoge-ii\tframes-per-cycle-ratio\tlogic-efficiency-ratio\tlut-efficiency-ratio\tff-efficiency-ratio\tdsp-efficiency-ratio\n'
    for role in forward inverse; do
        fpt=$(awk -F '\t' -v role="$role" \
            '$2 == role && $3 == "fpt" { print }' "$summary")
        hoge=$(awk -F '\t' -v role="$role" \
            '$2 == role && $3 == "hoge" { print }' "$summary")
        if [[ -z $fpt || -z $hoge ]]; then
            continue
        fi
        awk -F '\t' -v role="$role" -v fpt="$fpt" -v hoge="$hoge" '
            BEGIN {
                split(fpt, f, "\t")
                split(hoge, h, "\t")
                rate = h[4] / f[4]
                logic = h[4] * h[5] / (f[4] * f[5])
                lut = h[4] * h[6] / (f[4] * f[6])
                ff = h[4] * h[7] / (f[4] * f[7])
                dsp = h[4] * h[13] / (f[4] * f[13])
                printf "%s\t%s\t%s\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\n", \
                    role, f[4], h[4], rate, logic, lut, ff, dsp
            }
        '
    done
} | tee "$comparison"

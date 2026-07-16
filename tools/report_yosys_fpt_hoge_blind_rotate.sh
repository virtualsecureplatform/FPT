#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
build_root=${1:-$repo_root/build/yosys-fpt-hoge-blind-rotate}
manifest=${2:-$repo_root/build/vivado-u280-fpt-hoge-prepared/manifest.tsv}
if (( $# > 1 )); then
    shift 2
else
    set --
fi

if (( $# > 0 )); then
    designs=("$@")
else
    designs=(fpt-blind-rotate hoge-blind-rotate)
fi

for tool in awk jq; do
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

parameters_for() {
    case "$1" in
        fpt-blind-rotate)
            printf 'fpt\t%s\t%s\n' \
                "$(manifest_value fpt_blind_rotate_contexts)" \
                "$(manifest_value fpt_blind_rotate_cycles_per_result)"
            ;;
        hoge-blind-rotate)
            printf 'hoge\t%s\t%s\n' \
                "$(manifest_value hoge_blind_rotate_contexts)" \
                "$(manifest_value hoge_blind_rotate_cycles_per_result)"
            ;;
        *)
            echo "Unknown Blind Rotate design: $1" >&2
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
    printf 'design\timplementation\tcontexts\tcycles-per-result\tresults-per-cycle\tlogic-cells\tluts\tffs\tcarry\tmuxf\tbram\tdist-ram\tsrl\tdsp\ttotal-cells\telapsed-s\tmax-rss-kib\n'
    for design in "${designs[@]}"; do
        IFS=$'\t' read -r implementation contexts cycles_per_result \
            <<<"$(parameters_for "$design")"
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
        results_per_cycle=$(awk -v cycles="$cycles_per_result" \
            'BEGIN { printf "%.12g", 1 / cycles }')

        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$design" "$implementation" "$contexts" \
            "$cycles_per_result" "$results_per_cycle" "$logic_cells" \
            "$luts" "$ffs" "$carry" "$muxf" "$bram" "$dist_ram" \
            "$srl" "$dsp" "$total_cells" "${elapsed:--}" \
            "${max_rss:--}"
    done
} | tee "$summary"

comparison=$build_root/comparison.tsv
{
    printf 'scope\tfpt-cycles-per-result\thoge-cycles-per-result\tresult-rate-ratio\tlogic-efficiency-ratio\tlut-efficiency-ratio\tff-efficiency-ratio\tdsp-efficiency-ratio\n'
    fpt=$(awk -F '\t' '$2 == "fpt" { print; exit }' "$summary")
    hoge=$(awk -F '\t' '$2 == "hoge" { print; exit }' "$summary")
    if [[ -n $fpt && -n $hoge ]]; then
        awk -F '\t' -v fpt="$fpt" -v hoge="$hoge" '
            BEGIN {
                split(fpt, f, "\t")
                split(hoge, h, "\t")
                rate = h[4] / f[4]
                logic = h[4] * h[6] / (f[4] * f[6])
                lut = h[4] * h[7] / (f[4] * f[7])
                ff = h[4] * h[8] / (f[4] * f[8])
                dsp = h[4] * h[14] / (f[4] * f[14])
                printf "blind-rotate\t%s\t%s\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\n", \
                    f[4], h[4], rate, logic, lut, ff, dsp
            }
        '
    fi
} | tee "$comparison"

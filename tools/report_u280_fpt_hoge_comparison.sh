#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_root=${1:-$repo_root/build/vivado-u280-fpt-hoge-comparison}
runs_dir=$output_root/runs
summary_file=$output_root/summary.tsv
comparison_file=$output_root/comparison.tsv
manifest_file=$output_root/manifest.tsv
route_metrics_checker=$repo_root/tools/check_u280_route_metrics.sh

metric_value() {
    local metrics_file=$1
    local metric=$2
    awk -F '\t' -v metric="$metric" '
        $1 == metric { print $2; found = 1; exit }
        END { if (!found) print "-" }
    ' "$metrics_file"
}

power_value() {
    local power_report=$1
    if [[ ! -f $power_report ]]; then
        printf '%s\n' '-'
        return
    fi
    awk -F '|' '
        /Total On-Chip Power \(W\)/ {
            value = $3
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
            print value
            found = 1
            exit
        }
        END { if (!found) print "-" }
    ' "$power_report"
}

is_number() {
    [[ $1 =~ ^-?[0-9]+([.][0-9]+)?$ ]]
}

manifest_value() {
    local key=$1
    if [[ ! -f $manifest_file ]]; then
        printf '%s\n' -
        return
    fi
    awk -F '\t' -v key="$key" '
        $1 == key { print $2; found = 1; exit }
        END { if (!found) print "-" }
    ' "$manifest_file"
}

design_ii() {
    local ii

    case "$1" in
        fpt-forward) printf '%s\n' 4 ;;
        hoge-forward) printf '%s\n' 32 ;;
        fpt-inverse) printf '%s\n' 8 ;;
        hoge-inverse) printf '%s\n' 32 ;;
        fpt-blind-rotate)
            ii=$(manifest_value fpt_blind_rotate_cycles_per_result)
            if [[ $ii == - ]]; then
                manifest_value fpt_blind_rotate_schedule_cycles
            else
                printf '%s\n' "$ii"
            fi ;;
        hoge-blind-rotate)
            manifest_value hoge_blind_rotate_cycles_per_result ;;
        *) printf '%s\n' - ;;
    esac
}

derived_metric() {
    local metrics_file=$1
    local design=$2
    local metric=$3
    local ii
    local frequency
    local divisor

    ii=$(design_ii "$design")
    frequency=$(metric_value "$metrics_file" achieved_mhz)
    if ! is_number "$ii" || ! is_number "$frequency"; then
        printf '%s\n' -
        return
    fi
    case "$metric" in
        frame_rate_mfps)
            awk -v frequency="$frequency" -v ii="$ii" \
                'BEGIN { printf "%.6f", frequency / ii }' ;;
        frame_rate_kfps_per_lut)
            divisor=$(metric_value "$metrics_file" logic_luts)
            if ! is_number "$divisor" || [[ $divisor == 0 ]]; then
                printf '%s\n' -
            else
                awk -v frequency="$frequency" -v ii="$ii" \
                    -v divisor="$divisor" \
                    'BEGIN { printf "%.6f", 1000.0 * frequency / ii / divisor }'
            fi ;;
        frame_rate_mfps_per_dsp)
            divisor=$(metric_value "$metrics_file" dsp48e2)
            if ! is_number "$divisor" || [[ $divisor == 0 ]]; then
                printf '%s\n' -
            else
                awk -v frequency="$frequency" -v ii="$ii" \
                    -v divisor="$divisor" \
                    'BEGIN { printf "%.6f", frequency / ii / divisor }'
            fi ;;
        *) printf '%s\n' - ;;
    esac
}

write_comparison_metric() {
    local role=$1
    local period=$2
    local metric=$3
    local hoge=$4
    local fpt=$5
    local delta=-
    local percent=-

    if is_number "$hoge" && is_number "$fpt"; then
        delta=$(awk -v hoge="$hoge" -v fpt="$fpt" \
            'BEGIN { printf "%.3f", fpt - hoge }')
        if awk -v value="$hoge" 'BEGIN { exit value == 0 }'; then
            percent=$(awk -v hoge="$hoge" -v fpt="$fpt" \
                'BEGIN { printf "%.1f%%", 100.0 * (fpt - hoge) / hoge }')
        fi
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$role" "$period" "$metric" "$hoge" "$fpt" "$delta" \
        "$percent" >> "$comparison_file"
}

if [[ ! -d $runs_dir ]]; then
    echo "Vivado run directory not found: $runs_dir" >&2
    exit 1
fi

printf '%s\n' \
    $'design\tperiod_ns\twns_ns\tachieved_mhz\troute_fully_routed\troute_errors\tdrc_violations\tdrc_fatal\tdrc_error\tdrc_critical_warning\tdrc_warning\tdrc_advisory\tdrc_unclassified\tframe_ii_cycles\tframe_rate_mfps\tframe_rate_kfps_per_lut\tframe_rate_mfps_per_dsp\tlogic_luts\tflip_flops\tdsp48e2\tramb18e2\tramb36e2\turam288\tdistributed_ram\tsrl\tcarry8\tpower_w' \
    > "$summary_file"
printf '%s\n' \
    $'role\tperiod_ns\tmetric\thoge_ntt\tfpt_ftt\tfpt_minus_hoge\tchange' \
    > "$comparison_file"

found=0
while IFS= read -r -d '' metrics_file; do
    run_dir=$(dirname "$metrics_file")
    design=$(basename "$run_dir")
    "$route_metrics_checker" "$metrics_file"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$design" \
        "$(metric_value "$metrics_file" clock_period_ns)" \
        "$(metric_value "$metrics_file" wns_ns)" \
        "$(metric_value "$metrics_file" achieved_mhz)" \
        "$(metric_value "$metrics_file" route_fully_routed)" \
        "$(metric_value "$metrics_file" route_errors)" \
        "$(metric_value "$metrics_file" drc_violations)" \
        "$(metric_value "$metrics_file" drc_fatal)" \
        "$(metric_value "$metrics_file" drc_error)" \
        "$(metric_value "$metrics_file" drc_critical_warning)" \
        "$(metric_value "$metrics_file" drc_warning)" \
        "$(metric_value "$metrics_file" drc_advisory)" \
        "$(metric_value "$metrics_file" drc_unclassified)" \
        "$(design_ii "$design")" \
        "$(derived_metric "$metrics_file" "$design" frame_rate_mfps)" \
        "$(derived_metric "$metrics_file" "$design" frame_rate_kfps_per_lut)" \
        "$(derived_metric "$metrics_file" "$design" frame_rate_mfps_per_dsp)" \
        "$(metric_value "$metrics_file" logic_luts)" \
        "$(metric_value "$metrics_file" flip_flops)" \
        "$(metric_value "$metrics_file" dsp48e2)" \
        "$(metric_value "$metrics_file" ramb18e2)" \
        "$(metric_value "$metrics_file" ramb36e2)" \
        "$(metric_value "$metrics_file" uram288)" \
        "$(metric_value "$metrics_file" distributed_ram)" \
        "$(metric_value "$metrics_file" srl)" \
        "$(metric_value "$metrics_file" carry8)" \
        "$(power_value "$run_dir/power.rpt")" >> "$summary_file"
    found=1
done < <(find "$runs_dir" -mindepth 3 -maxdepth 3 -name metrics.tsv \
    -type f -print0 | sort -z)

if [[ $found == 0 ]]; then
    echo "No completed Vivado metrics found below $runs_dir" >&2
    exit 1
fi

pairs=(
    'forward-transform:hoge-forward:fpt-forward'
    'inverse-transform:hoge-inverse:fpt-inverse'
    'blind-rotate:hoge-blind-rotate:fpt-blind-rotate'
)
for period_dir in "$runs_dir"/period-*; do
    [[ -d $period_dir ]] || continue
    for pair in "${pairs[@]}"; do
        IFS=: read -r role hoge_design fpt_design <<< "$pair"
        hoge_metrics=$period_dir/$hoge_design/metrics.tsv
        fpt_metrics=$period_dir/$fpt_design/metrics.tsv
        [[ -f $hoge_metrics && -f $fpt_metrics ]] || continue
        period=$(metric_value "$hoge_metrics" clock_period_ns)
        for metric in achieved_mhz logic_luts flip_flops dsp48e2 ramb18e2 \
            ramb36e2 uram288 distributed_ram srl carry8; do
            write_comparison_metric "$role" "$period" "$metric" \
                "$(metric_value "$hoge_metrics" "$metric")" \
                "$(metric_value "$fpt_metrics" "$metric")"
        done
        for metric in frame_rate_mfps frame_rate_kfps_per_lut \
            frame_rate_mfps_per_dsp; do
            write_comparison_metric "$role" "$period" "$metric" \
                "$(derived_metric "$hoge_metrics" "$hoge_design" "$metric")" \
                "$(derived_metric "$fpt_metrics" "$fpt_design" "$metric")"
        done
        write_comparison_metric "$role" "$period" power_w \
            "$(power_value "$period_dir/$hoge_design/power.rpt")" \
            "$(power_value "$period_dir/$fpt_design/power.rpt")"
    done
done

if command -v column >/dev/null; then
    column -t -s $'\t' "$summary_file"
else
    cat "$summary_file"
fi
printf '\nDetailed FPT-minus-HOGE comparisons: %s\n' "$comparison_file"

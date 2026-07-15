#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_root=${1:-$repo_root/build/vivado-u280-comparison}
runs_dir=$output_root/runs
summary_file=$output_root/summary.tsv
comparison_file=$output_root/comparison.tsv

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

write_comparison_metric() {
    local period=$1
    local metric=$2
    local barrel=$3
    local bitwise=$4
    local delta=-
    local percent=-

    if is_number "$barrel" && is_number "$bitwise"; then
        delta=$(awk -v barrel="$barrel" -v bitwise="$bitwise" \
            'BEGIN { printf "%.3f", bitwise - barrel }')
        if awk -v value="$barrel" 'BEGIN { exit value == 0 }'; then
            percent=$(awk -v barrel="$barrel" -v bitwise="$bitwise" \
                'BEGIN { printf "%.1f%%", 100.0 * (bitwise - barrel) / barrel }')
        fi
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$period" "$metric" "$barrel" "$bitwise" "$delta" "$percent" \
        >> "$comparison_file"
}

if [[ ! -d $runs_dir ]]; then
    echo "Vivado run directory not found: $runs_dir" >&2
    exit 1
fi

mkdir -p "$output_root"
printf '%s\n' \
    $'design\tperiod_ns\twns_ns\tachieved_mhz\tlogic_luts\tflip_flops\tdsp48e2\tramb18e2\tramb36e2\turam288\tdistributed_ram\tsrl\tcarry8\tpower_w' \
    > "$summary_file"
printf '%s\n' \
    $'period_ns\tmetric\tbarrel\tbitwise\tbitwise_minus_barrel\tchange' \
    > "$comparison_file"

found=0
while IFS= read -r -d '' metrics_file; do
    run_dir=$(dirname "$metrics_file")
    design=$(basename "$run_dir")
    period=$(metric_value "$metrics_file" clock_period_ns)
    power=$(power_value "$run_dir/power.rpt")
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$design" \
        "$period" \
        "$(metric_value "$metrics_file" wns_ns)" \
        "$(metric_value "$metrics_file" achieved_mhz)" \
        "$(metric_value "$metrics_file" logic_luts)" \
        "$(metric_value "$metrics_file" flip_flops)" \
        "$(metric_value "$metrics_file" dsp48e2)" \
        "$(metric_value "$metrics_file" ramb18e2)" \
        "$(metric_value "$metrics_file" ramb36e2)" \
        "$(metric_value "$metrics_file" uram288)" \
        "$(metric_value "$metrics_file" distributed_ram)" \
        "$(metric_value "$metrics_file" srl)" \
        "$(metric_value "$metrics_file" carry8)" \
        "$power" >> "$summary_file"
    found=1
done < <(find "$runs_dir" -mindepth 3 -maxdepth 3 -name metrics.tsv \
    -type f -print0 | sort -z)

if [[ $found == 0 ]]; then
    echo "No completed Vivado metrics found below $runs_dir" >&2
    exit 1
fi

for period_dir in "$runs_dir"/period-*; do
    [[ -d $period_dir ]] || continue
    barrel_metrics=$period_dir/barrel-batched/metrics.tsv
    bitwise_metrics=$period_dir/bitwise-batched/metrics.tsv
    [[ -f $barrel_metrics && -f $bitwise_metrics ]] || continue
    period=$(metric_value "$barrel_metrics" clock_period_ns)

    for metric in achieved_mhz logic_luts flip_flops dsp48e2 ramb18e2 \
        ramb36e2 uram288 distributed_ram srl carry8; do
        write_comparison_metric "$period" "$metric" \
            "$(metric_value "$barrel_metrics" "$metric")" \
            "$(metric_value "$bitwise_metrics" "$metric")"
    done
    write_comparison_metric "$period" power_w \
        "$(power_value "$period_dir/barrel-batched/power.rpt")" \
        "$(power_value "$period_dir/bitwise-batched/power.rpt")"
done

if command -v column >/dev/null; then
    column -t -s $'\t' "$summary_file"
else
    cat "$summary_file"
fi
printf '\nDetailed pairwise comparison: %s\n' "$comparison_file"

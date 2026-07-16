#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source_file=${1:-$repo_root/build/hoge-baselines/HOGEBlindRotateBaseline.v}
metrics_file=${2:-}

for tool in awk rg; do
    if ! command -v "$tool" >/dev/null; then
        echo "Required tool not found: $tool" >&2
        exit 1
    fi
done
if [[ ! -s $source_file ]]; then
    echo "HOGE Blind Rotate source not found: $source_file" >&2
    exit 1
fi

read -r top_ports top_port_bits key_ports key_port_bits key_data_ports \
    key_data_bits tlwe_ports tlwe_port_bits result_ports result_port_bits < <(
    awk '
        /^module HOGEBlindRotateBaseline\(/ { inside = 1; next }
        inside && /^\);/ { inside = 0 }
        inside && ($1 == "input" || $1 == "output") {
            width = 1
            if ($2 ~ /^\[/) {
                field = $2
                gsub(/^\[/, "", field)
                gsub(/\]$/, "", field)
                split(field, bounds, ":")
                width = bounds[1] - bounds[2] + 1
            }
            top_ports++
            top_bits += width
            if ($0 ~ /io_bootstrappingKey_[0-7]_(TVALID|TREADY|TDATA)/) {
                key_ports++
                key_bits += width
                if ($0 ~ /_TDATA/) {
                    key_data_ports++
                    key_data_bits += width
                }
            }
            if ($0 ~ /io_tlwe_/) {
                tlwe_ports++
                tlwe_bits += width
            }
            if ($0 ~ /io_result_/) {
                result_ports++
                result_bits += width
            }
        }
        END {
            print top_ports + 0, top_bits + 0, key_ports + 0, key_bits + 0,
                key_data_ports + 0, key_data_bits + 0, tlwe_ports + 0,
                tlwe_bits + 0, result_ports + 0, result_bits + 0
        }
    ' "$source_file"
)

top_modules=$(rg -c '^module HOGEBlindRotateBaseline\(' "$source_file" || true)
cache_modules=$(rg -c '^module TRGSWBatchMemory\(' "$source_file" || true)
cache_instances=$(rg -c '^  TRGSWBatchMemory trgswbatchmem \(' \
    "$source_file" || true)
memory_modules=$(rg -c '^module RWDmem\(' "$source_file" || true)
memory_instances=$(rg -c '^  RWDmem mem \(' "$source_file" || true)
memory_declarations=$(rg -c '^  reg \[2047:0\] mem \[0:383\];' \
    "$source_file" || true)

for index in {0..7}; do
    for signal in TVALID TREADY TDATA; do
        if ! rg -q "io_bootstrappingKey_${index}_${signal}" "$source_file"; then
            echo "Missing HOGE key-stream port $index/$signal" >&2
            exit 1
        fi
    done
done

if [[ $top_modules != 1 || $top_ports != 33 || $top_port_bits != 4663 || \
      $key_ports != 24 || $key_port_bits != 4112 || \
      $key_data_ports != 8 || $key_data_bits != 4096 || \
      $tlwe_ports != 3 || $tlwe_port_bits != 514 || \
      $result_ports != 4 || $result_port_bits != 35 ]]; then
    echo "Unexpected HOGE physical port boundary: top_modules=$top_modules top_ports=$top_ports top_bits=$top_port_bits key_ports=$key_ports key_bits=$key_port_bits key_data_ports=$key_data_ports key_data_bits=$key_data_bits tlwe_ports=$tlwe_ports tlwe_bits=$tlwe_port_bits result_ports=$result_ports result_bits=$result_port_bits" >&2
    exit 1
fi
if [[ $cache_modules != 1 || $cache_instances != 2 || \
      $memory_modules != 1 || $memory_instances != 1 || \
      $memory_declarations != 1 ]]; then
    echo "Unexpected HOGE key-cache structure: cache_modules=$cache_modules cache_instances=$cache_instances memory_modules=$memory_modules memory_instances=$memory_instances memory_declarations=$memory_declarations" >&2
    exit 1
fi

cache_banks_per_instance=2
cache_depth_per_bank=192
cache_word_bits=2048
cache_logical_bits=$((
    cache_instances * cache_banks_per_instance * cache_depth_per_bank *
        cache_word_bits
))
key_beats_per_bus=$((636 * 2 * 3 * 32))
key_transfer_bits=$((key_beats_per_bus * key_data_bits))

write_metrics() {
    printf '%s\n' $'metric\tvalue'
    printf 'status\tpassed\n'
    printf 'top\tHOGEBlindRotateBaseline\n'
    printf 'top_ports\t%s\n' "$top_ports"
    printf 'top_port_bits\t%s\n' "$top_port_bits"
    printf 'key_streams\t%s\n' "$key_data_ports"
    printf 'key_data_bits\t%s\n' "$key_data_bits"
    printf 'key_interface_bits\t%s\n' "$key_port_bits"
    printf 'tlwe_interface_bits\t%s\n' "$tlwe_port_bits"
    printf 'result_interface_bits\t%s\n' "$result_port_bits"
    printf 'trgsw_cache_instances\t%s\n' "$cache_instances"
    printf 'cache_banks_per_instance\t%s\n' "$cache_banks_per_instance"
    printf 'cache_depth_per_bank\t%s\n' "$cache_depth_per_bank"
    printf 'cache_word_bits\t%s\n' "$cache_word_bits"
    printf 'cache_logical_bits\t%s\n' "$cache_logical_bits"
    printf 'key_beats_per_bus\t%s\n' "$key_beats_per_bus"
    printf 'key_transfer_bits\t%s\n' "$key_transfer_bits"
}

if [[ -n $metrics_file ]]; then
    mkdir -p "$(dirname "$metrics_file")"
    write_metrics > "$metrics_file"
    cat "$metrics_file"
else
    write_metrics
fi

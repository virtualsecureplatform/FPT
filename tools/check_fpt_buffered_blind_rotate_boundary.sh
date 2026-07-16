#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=tools/fpt_arithmetic_profile_contract.sh
source "$repo_root/tools/fpt_arithmetic_profile_contract.sh"
source_file=${1:-$repo_root/build/chisel-paper-buffered-blind-rotate-accelerator/BufferedBlindRotateAccelerator.sv}
metrics_file=${2:-}
arithmetic_profile=${3:-${FPT_ARITHMETIC_PROFILE:-paper-set-ii}}
fpt_resolve_arithmetic_profile "$arithmetic_profile"

for tool in awk rg; do
    if ! command -v "$tool" >/dev/null; then
        echo "Required tool not found: $tool" >&2
        exit 1
    fi
done
if [[ ! -s $source_file ]]; then
    echo "Buffered FPT Blind Rotate source not found: $source_file" >&2
    exit 1
fi

# CIRCT groups ports of the same direction and width, so continuation lines do
# not repeat input/output or the range.  Retain both while walking the ANSI
# header and count every identifier independently.
read -r top_ports top_port_bits key_ports key_port_bits key_data_ports \
    key_data_bits input_ports input_port_bits < <(
    awk '
        /^module BufferedBlindRotateAccelerator\(/ {
            inside = 1
            direction = ""
            width = 1
            next
        }
        inside && /^\);/ { inside = 0 }
        inside {
            line = $0
            gsub(/^[[:space:]]+/, "", line)
            split(line, fields, /[[:space:]]+/)
            position = 1
            if (fields[position] == "input" || fields[position] == "output") {
                direction = fields[position]
                position++
                width = 1
                if (fields[position] ~ /^\[/) {
                    field = fields[position]
                    gsub(/^\[/, "", field)
                    gsub(/\]$/, "", field)
                    split(field, bounds, ":")
                    width = bounds[1] - bounds[2] + 1
                    position++
                }
            }
            name = fields[position]
            gsub(/,$/, "", name)
            if (name != "") {
                top_ports++
                top_bits += width
                if (name ~ /^io_keyLoad/) {
                    key_ports++
                    key_bits += width
                }
                if (name ~ /^io_keyLoad_[0-9]+_(real|imag)$/) {
                    key_data_ports++
                    key_data_bits += width
                }
                if (name ~ /^io_input/ || name == "io_testVector") {
                    input_ports++
                    input_bits += width
                }
            }
        }
        END {
            print top_ports + 0, top_bits + 0, key_ports + 0, key_bits + 0,
                key_data_ports + 0, key_data_bits + 0, input_ports + 0,
                input_bits + 0
        }
    ' "$source_file"
)

top_modules=$(rg -c '^module BufferedBlindRotateAccelerator\(' \
    "$source_file" || true)
cache_modules=$(rg -c '^module BootstrappingKeyPingPongBuffer\(' \
    "$source_file" || true)
cache_instances=$(rg -c '^  BootstrappingKeyPingPongBuffer keyBuffer \(' \
    "$source_file" || true)
memory_declarations=$(rg -c \
    "^  \\(\\* ram_style = \"block\" \\*\\) reg \
\\[$((FPT_PROFILE_BK_WIDTH * 512 - 1)):0\\] Memory\\[0:31\\];" \
    "$source_file" || true)

for lane in {0..15}; do
    for component in real imag; do
        if ! rg -q "io_keyLoad_${lane}_${component}" "$source_file"; then
            echo "Missing FPT key-load port $lane/$component" >&2
            exit 1
        fi
    done
done

expected_key_data_bits=$((32 * FPT_PROFILE_BK_WIDTH))
expected_key_port_bits=$((889 + expected_key_data_bits - 864))
expected_top_port_bits=$((1012 + expected_key_data_bits - 864))
if [[ $top_modules != 1 || $top_ports != 60 || \
      $top_port_bits != "$expected_top_port_bits" || \
      $key_ports != 39 || $key_port_bits != "$expected_key_port_bits" || \
      $key_data_ports != 32 || \
      $key_data_bits != "$expected_key_data_bits" || \
      $input_ports != 9 || $input_port_bits != 77 ]]; then
    echo "Unexpected FPT physical port boundary: top_modules=$top_modules top_ports=$top_ports top_bits=$top_port_bits key_ports=$key_ports key_bits=$key_port_bits key_data_ports=$key_data_ports key_data_bits=$key_data_bits input_ports=$input_ports input_bits=$input_port_bits" >&2
    exit 1
fi
if [[ $cache_modules != 1 || $cache_instances != 1 || \
      $memory_declarations != 1 ]]; then
    echo "Unexpected FPT key-cache structure: cache_modules=$cache_modules cache_instances=$cache_instances memory_declarations=$memory_declarations" >&2
    exit 1
fi

cache_banks=2
cache_depth_per_bank=16
cache_word_bits=$((FPT_PROFILE_BK_WIDTH * 512))
cache_logical_bits=$((cache_banks * cache_depth_per_bank * cache_word_bits))

write_metrics() {
    printf '%s\n' $'metric\tvalue'
    printf 'status\tpassed\n'
    printf 'top\tBufferedBlindRotateAccelerator\n'
    printf 'arithmetic_profile\t%s\n' "$arithmetic_profile"
    printf 'key_component_bits\t%s\n' "$FPT_PROFILE_BK_WIDTH"
    printf 'top_ports\t%s\n' "$top_ports"
    printf 'top_port_bits\t%s\n' "$top_port_bits"
    printf 'key_data_ports\t%s\n' "$key_data_ports"
    printf 'key_data_bits\t%s\n' "$key_data_bits"
    printf 'key_interface_bits\t%s\n' "$key_port_bits"
    printf 'input_interface_bits\t%s\n' "$input_port_bits"
    printf 'cache_instances\t%s\n' "$cache_instances"
    printf 'cache_banks\t%s\n' "$cache_banks"
    printf 'cache_depth_per_bank\t%s\n' "$cache_depth_per_bank"
    printf 'cache_word_bits\t%s\n' "$cache_word_bits"
    printf 'cache_logical_bits\t%s\n' "$cache_logical_bits"
}

if [[ -n $metrics_file ]]; then
    mkdir -p "$(dirname "$metrics_file")"
    write_metrics > "$metrics_file"
    cat "$metrics_file"
else
    write_metrics
fi

#!/usr/bin/env bash
set -euo pipefail

barrel=${1:-build/chisel-paper/CmuxEngine.sv}
bitwise=${2:-build/chisel-paper-bitwise-cmux/CmuxEngine.sv}
batched=${3:-build/chisel-paper-bitwise-batched/BatchedCmuxEngine.sv}

for source in "$barrel" "$bitwise" "$batched"; do
    if [[ ! -f $source ]]; then
        echo "Missing emitted Chisel source: $source" >&2
        exit 1
    fi
done

if ! rg -q '^module NegacyclicBarrelRotator\(' "$barrel"; then
    echo "Baseline barrel module not found in $barrel" >&2
    exit 1
fi
for source in "$bitwise" "$batched"; do
    if rg -q '^module NegacyclicBarrelRotator\(' "$source"; then
        echo "Unexpected full-width barrel module in $source" >&2
        exit 1
    fi
done

count_pattern() {
    awk -v pattern="$2" '$0 ~ pattern { count += 1 } END { print count + 0 }' \
        "$1"
}

barrel_bits=$((1024 * 10 * 32))
single_bits=$((2 * 1024 * 10 * 2))
batched_bits=$((2 * 2 * 1024 * 10 * 2))

printf 'design\tbytes\tbitwise-cores\taccumulator-memories\tmux-bit-stages\n'
printf 'barrel-single\t%s\t0\t0\t%s\n' \
    "$(stat -c %s "$barrel")" "$barrel_bits"
printf 'bitwise-single\t%s\t%s\t0\t%s\n' \
    "$(stat -c %s "$bitwise")" \
    "$(count_pattern "$bitwise" 'BitwiseCmuxForwardFrontend store \\(')" \
    "$single_bits"
printf 'bitwise-batched\t%s\t%s\t%s\t%s\n' \
    "$(stat -c %s "$batched")" \
    "$(count_pattern "$batched" 'BitwiseCmuxForwardFrontend cores_[0-9]+ \\(')" \
    "$(count_pattern "$batched" '^  mem_128x128 ')" \
    "$batched_bits"

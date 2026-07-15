#!/usr/bin/env bash
set -euo pipefail

stock_forward=${1:-build/sgen/dft512_l128.v}
cyclic_forward=${2:-build/sgen-cyclic/forward.v}
tangent_forward=${3:-build/sgen-fpt/forward.v}
stock_inverse=${4:-build/sgen/idft512_l64.v}
cyclic_inverse=${5:-build/sgen-cyclic/inverse.v}
tangent_inverse=${6:-build/sgen-fpt/inverse.v}

for source in \
    "$stock_forward" "$cyclic_forward" "$tangent_forward" \
    "$stock_inverse" "$cyclic_inverse" "$tangent_inverse"; do
    if [[ ! -f "$source" ]]; then
        echo "Missing generated SGen source: $source" >&2
        exit 1
    fi
done

latency() {
    sed -n 's/.*latency of \([0-9][0-9]*\) cycles.*/\1/p' "$1" | head -n 1
}

interval() {
    sed -n 's/.*new transformation every \([0-9][0-9]*\) cycles.*/\1/p' \
        "$1" | head -n 1
}

multipliers() {
    rg -c 'assign .* \* ' "$1"
}

constant_multipliers() {
    rg -c "assign .* \* .*'d" "$1"
}

row() {
    printf '%s\t%s\t%s\t%s\t%s\n' \
        "$1" "$(latency "$2")" "$(interval "$2")" \
        "$(multipliers "$2")" "$(constant_multipliers "$2")"
}

printf 'design\tlatency\tinterval\treal-multiply-expressions\tconstant-multiplies\n'
row stock-forward "$stock_forward"
row fpt-cyclic-forward "$cyclic_forward"
row fpt-tangent-forward "$tangent_forward"
row stock-inverse "$stock_inverse"
row fpt-cyclic-inverse "$cyclic_inverse"
row fpt-tangent-inverse "$tangent_inverse"

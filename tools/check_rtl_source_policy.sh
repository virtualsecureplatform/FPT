#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
violations=()

while IFS= read -r -d '' source_file; do
    [[ -e $repo_root/$source_file ]] || continue
    case "$source_file" in
        chisel/src/test/resources/generated/*.v)
            if ! rg -q 'generated using SGen' "$repo_root/$source_file"; then
                violations+=("$source_file (missing SGen generator marker)")
            fi
            ;;
        *)
            violations+=("$source_file")
            ;;
    esac
done < <(git -C "$repo_root" ls-files --cached --others --exclude-standard \
    -z -- '*.v' '*.sv')

if [[ ${#violations[@]} != 0 ]]; then
    echo "Tracked Verilog/SystemVerilog violates the Chisel/SGen source policy:" >&2
    printf '  %s\n' "${violations[@]}" >&2
    exit 1
fi

echo "RTL source policy passed: repository HDL is generated SGen fixture output"

#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
sgen_dir=${1:-$repo_root/../SGen}
output_root=${2:-$repo_root/build/vivado-u280-blind-rotate-comparison}

export FPT_VIVADO_SCOPE=blind-rotate
exec "$repo_root/tools/run_u280_comparison.sh" "$sgen_dir" "$output_root"

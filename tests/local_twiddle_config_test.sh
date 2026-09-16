#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# These configurations must fail before an output directory or SGen is used.
if FPT_SGEN_FORWARD_TWIDDLE_ISLAND_CONSUMERS=3 bash "$repo/tools/generate_sgen_fpt.sh" /nonexistent /nonexistent; then
  echo 'invalid consumer count accepted' >&2; exit 1
fi
if FPT_SGEN_FORWARD_TWIDDLE_ISLAND_CONSUMERS=4 FPT_SGEN_FORWARD_PARTITION=none bash "$repo/tools/generate_sgen_fpt.sh" /nonexistent /nonexistent; then
  echo 'unpartitioned local-twiddle configuration accepted' >&2; exit 1
fi
if FPT_SGEN_FORWARD_TWIDDLE_REGION_ISLANDS=7 bash "$repo/tools/generate_sgen_fpt.sh" /nonexistent /nonexistent; then
  echo 'invalid regional group size accepted' >&2; exit 1
fi
if FPT_SGEN_INVERSE_MUX_ISLAND_BITS=60 bash "$repo/tools/generate_sgen_fpt.sh" /nonexistent /nonexistent; then
  echo 'invalid scalar island size accepted' >&2; exit 1
fi
echo LOCAL_TWIDDLE_CONFIG_PASS

#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
for value in -1 invalid 2147483648; do
  if output=$(FPT_SGEN_INVERSE_MUX_CONTROL_MAX_BITS=$value \
    bash "$repo/tools/generate_sgen_fpt.sh" /nonexistent-sgen /nonexistent-output 2>&1); then
    echo 'Invalid inverse mux-control budget accepted' >&2
    exit 1
  fi
  [[ $output == *'FPT_SGEN_INVERSE_MUX_CONTROL_MAX_BITS must be a nonnegative 32-bit integer'* ]]
done
echo SGEN_INVERSE_CONTROL_CONFIG_PASS

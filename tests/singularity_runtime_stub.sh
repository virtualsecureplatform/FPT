#!/usr/bin/env bash
set -euo pipefail

: "${FPT_SINGULARITY_STUB_LOG:?stub log path is required}"
printf '%s\n' "$@" > "$FPT_SINGULARITY_STUB_LOG"

if [[ ${FPT_SINGULARITY_STUB_REQUIRE_USERNS:-0} == 1 ]]; then
    found_userns=0
    for argument in "$@"; do
        if [[ $argument == --userns ]]; then
            found_userns=1
            break
        fi
    done
    if [[ $found_userns == 0 ]]; then
        exit 1
    fi
fi

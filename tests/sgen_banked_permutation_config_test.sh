#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
must_reject() {
    local expected=$1 output
    shift
    if output=$(env "$@" bash "$repo/tools/generate_sgen_fpt.sh" /nonexistent-sgen /nonexistent-output 2>&1); then
        echo 'Unsupported banked configuration was accepted' >&2
        exit 1
    fi
    [[ $output == *"$expected"* ]]
}
must_reject 'must be legacy or banked_tiles' FPT_SGEN_FORWARD_PERMUTATION_ARCH=invalid
must_reject 'must be 0 or 1' FPT_SGEN_PRESERVE_PARTITION_REGISTERS=invalid
must_reject 'require a forward partition' FPT_SGEN_PRESERVE_PARTITION_REGISTERS=1 FPT_SGEN_FORWARD_PARTITION=none
must_reject 'requires FPT_SGEN_FORWARD_PARTITION=stage2spill' FPT_SGEN_FORWARD_PERMUTATION_ARCH=banked_tiles FPT_SGEN_FORWARD_PARTITION=none
must_reject 'requires the 512-point, 128-lane radix-8' FPT_SGEN_FORWARD_PERMUTATION_ARCH=banked_tiles FPT_SGEN_FORWARD_PARTITION=stage2spill FPT_SGEN_FORWARD_RADIX2K_MDC=0
must_reject 'requires FPT_SGEN_FORWARD_PARTITION=stage2spill' FPT_SGEN_FORWARD_PERMUTATION_ARCH=banked_local_control FPT_SGEN_FORWARD_PARTITION=none
must_reject 'requires the 512-point, 128-lane radix-8' FPT_SGEN_FORWARD_PERMUTATION_ARCH=banked_local_control FPT_SGEN_FORWARD_PARTITION=stage2spill FPT_SGEN_FORWARD_RADIX2K_MDC=0
must_reject 'requires FPT_SGEN_FORWARD_PARTITION=stage2spill' FPT_SGEN_FORWARD_PERMUTATION_ARCH=commutator_tiles FPT_SGEN_FORWARD_PARTITION=none
must_reject 'requires the 512-point, 128-lane radix-8' FPT_SGEN_FORWARD_PERMUTATION_ARCH=commutator_tiles FPT_SGEN_FORWARD_PARTITION=stage2spill FPT_SGEN_FORWARD_RADIX2K_MDC=0

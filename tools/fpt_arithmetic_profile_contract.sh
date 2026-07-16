#!/usr/bin/env bash

# Resolve the named C++/Chisel/SGen arithmetic profiles into shell values.
# This file is sourced by generation scripts; it is not a standalone command.
fpt_resolve_arithmetic_profile() {
    local profile=$1
    case "$profile" in
        paper-set-ii)
            FPT_PROFILE_BK_INTEGER_BITS=8
            FPT_PROFILE_BK_FRACTIONAL_BITS=19
            FPT_PROFILE_FFT_INTEGER_BITS=18
            FPT_PROFILE_FFT_FRACTIONAL_BITS=12
            FPT_PROFILE_IFFT_INTEGER_BITS=27
            FPT_PROFILE_IFFT_FRACTIONAL_BITS=3
            ;;
        tfhepp-hardware)
            FPT_PROFILE_BK_INTEGER_BITS=8
            FPT_PROFILE_BK_FRACTIONAL_BITS=21
            FPT_PROFILE_FFT_INTEGER_BITS=18
            FPT_PROFILE_FFT_FRACTIONAL_BITS=28
            FPT_PROFILE_IFFT_INTEGER_BITS=27
            FPT_PROFILE_IFFT_FRACTIONAL_BITS=24
            ;;
        *)
            echo "Unknown FPT arithmetic profile: $profile" >&2
            return 1
            ;;
    esac
    FPT_PROFILE_BK_WIDTH=$((
        FPT_PROFILE_BK_INTEGER_BITS + FPT_PROFILE_BK_FRACTIONAL_BITS
    ))
    FPT_PROFILE_FFT_WIDTH=$((
        FPT_PROFILE_FFT_INTEGER_BITS + FPT_PROFILE_FFT_FRACTIONAL_BITS
    ))
    FPT_PROFILE_IFFT_WIDTH=$((
        FPT_PROFILE_IFFT_INTEGER_BITS + FPT_PROFILE_IFFT_FRACTIONAL_BITS
    ))
    FPT_PROFILE_KEY_MEMORY_MODULE="memory_32x$((
        512 * FPT_PROFILE_BK_WIDTH
    ))"
}

fpt_check_sgen_arithmetic_profile() {
    local profile=$1
    local forward=$2
    local inverse=$3
    local forward_range
    local inverse_range

    fpt_resolve_arithmetic_profile "$profile"
    forward_range=$((2 * FPT_PROFILE_FFT_WIDTH - 1))
    inverse_range=$((2 * FPT_PROFILE_IFFT_WIDTH - 1))
    if ! rg -q "input[[:space:]]+\[$forward_range:0\][[:space:]]+i0," \
        "$forward"; then
        echo "FptSGenForward width does not match $profile" >&2
        return 1
    fi
    if ! rg -q "input[[:space:]]+\[$inverse_range:0\][[:space:]]+i0," \
        "$inverse"; then
        echo "FptSGenInverse width does not match $profile" >&2
        return 1
    fi
}

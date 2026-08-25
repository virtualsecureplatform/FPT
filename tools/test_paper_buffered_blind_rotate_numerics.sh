#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
sgen_dir=${1:-$repo_root/third_party/SGen}
work_dir=${2:-$repo_root/build/paper-buffered-blind-rotate-numerics}
arithmetic_profile=${FPT_ARITHMETIC_PROFILE:-paper-set-ii}
# shellcheck source=tools/fpt_arithmetic_profile_contract.sh
source "$repo_root/tools/fpt_arithmetic_profile_contract.sh"
fpt_resolve_arithmetic_profile "$arithmetic_profile"

if ! git -C "$sgen_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "SGen checkout not found: $sgen_dir" >&2
    exit 1
fi
for command in cmake sbt verilator; do
    if ! command -v "$command" >/dev/null; then
        echo "$command is required for the physical Blind Rotate regression" >&2
        exit 1
    fi
done

sgen_dir=$(realpath "$sgen_dir")
work_dir=$(realpath -m "$work_dir")
sgen_output=$work_dir/sgen
cmake_output=$work_dir/cmake
mkdir -p "$work_dir"

remove_sgen_binary=0
if [[ ! -e $sgen_dir/sgen.bat ]]; then
    remove_sgen_binary=1
fi
cleanup() {
    if [[ $remove_sgen_binary == 1 ]]; then
        rm -f "$sgen_dir/sgen.bat"
    fi
}
trap cleanup EXIT

(cd "$sgen_dir" && sbt assembly)
FPT_ARITHMETIC_PROFILE="$arithmetic_profile" FFT_LOG_LANES=7 IFFT_LOG_LANES=6 \
    "$repo_root/tools/generate_sgen_fpt.sh" "$sgen_dir" "$sgen_output"

cmake -S "$repo_root" -B "$cmake_output" \
    -DFPT_BUILD_TESTS=OFF -DFPT_BUILD_TFHEPP_TESTS=OFF \
    -DFPT_BUILD_RTL_TESTS=ON \
    -DFPT_RTL_ARITHMETIC_PROFILE="$arithmetic_profile"
cmake --build "$cmake_output" -j --target \
    fpt_paper_blind_rotate_vectors

(
    cd "$repo_root/chisel"
    FPT_PAPER_BUFFERED_BLIND_ROTATE_NUMERICS=1 \
    FPT_SGEN_FORWARD="$sgen_output/forward.v" \
    FPT_SGEN_INVERSE="$sgen_output/inverse.v" \
    FPT_PAPER_BLIND_ROTATE_VECTORS=\
"$cmake_output/rtl_paper_blind_rotate_vectors.txt" \
    FPT_ARITHMETIC_PROFILE="$arithmetic_profile" \
    FPT_U280_ROUTABLE_200_NUMERICS=1 \
    MAKEFLAGS="${MAKEFLAGS:--e -j4}" \
    VK_PCH_I_FAST= \
    VK_PCH_I_SLOW= \
        sbt -J-Xmx16G \
            'testOnly fpt.PaperBufferedBlindRotateNumericalSpec'
)

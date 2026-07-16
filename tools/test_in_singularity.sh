#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
jobs=${FPT_BUILD_JOBS:-4}
if [[ ! $jobs =~ ^[1-9][0-9]*$ ]]; then
    echo "FPT_BUILD_JOBS must be a positive integer" >&2
    exit 1
fi
export FPT_BUILD_JOBS=$jobs

exec "$repo_root/tools/run_singularity.sh" \
    /bin/bash -lc '
        set -euo pipefail
        remove_sgen_binary=0
        if [[ ! -e third_party/SGen/sgen.bat ]]; then
            remove_sgen_binary=1
        fi
        cleanup() {
            if [[ $remove_sgen_binary == 1 ]]; then
                rm -f third_party/SGen/sgen.bat
            fi
        }
        trap cleanup EXIT
        git submodule status --recursive |
            awk '\''$1 ~ /^[-+U]/ { bad=1; print } END { exit bad }'\''
        cmake -S . -B build \
            -DFPT_BUILD_TESTS=ON \
            -DFPT_BUILD_TFHEPP_TESTS=ON \
            -DFPT_BUILD_RTL_TESTS=ON \
            -DFPT_TFHEPP_SOURCE_DIR="$PWD/third_party/TFHEpp"
        cmake --build build -j"$FPT_BUILD_JOBS"
        ctest --test-dir build --output-on-failure
        (cd third_party/SGen && sbt -batch assembly)
        (cd chisel && sbt -batch test)
    '

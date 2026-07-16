#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
runtime=$repo_root/tests/singularity_runtime_stub.sh
work_dir=$(mktemp -d)
cleanup() { rm -rf "$work_dir"; }
trap cleanup EXIT

image=$work_dir/fpt.sif
cache=$work_dir/cache
log=$work_dir/runtime-arguments.txt
mkdir -p "$cache"
touch "$image" "$cache/.fpt-sbt-cache-seeded"

export FPT_SINGULARITY_RUNTIME=$runtime
export FPT_SINGULARITY_IMAGE=$image
export FPT_SINGULARITY_CACHE_DIR=$cache
export FPT_SINGULARITY_STUB_LOG=$log
export FPT_SINGULARITY_STUB_REQUIRE_USERNS=1
export FPT_TEST_FORWARD=contained-value
export MAKEFLAGS='-e -j2'

"$repo_root/tools/run_singularity.sh" /bin/echo fpt-container-ok

required_arguments=(
    exec
    --userns
    --cleanenv
    --home
    --bind
    --pwd
    "$repo_root"
    "FPT_TEST_FORWARD=contained-value"
    "MAKEFLAGS=-e -j2"
    "$image"
    /bin/echo
    fpt-container-ok
)
for argument in "${required_arguments[@]}"; do
    if ! rg -Fqx -- "$argument" "$log"; then
        echo "Singularity wrapper omitted argument: $argument" >&2
        exit 1
    fi
done

if ! rg -q '^SBT_OPTS=-Dsbt[.]global[.]base=/fpt-cache/' "$log"; then
    echo "Singularity wrapper omitted its writable sbt cache" >&2
    exit 1
fi
if ! rg -Fqx -- "$repo_root:$repo_root" "$log"; then
    echo "Singularity wrapper did not preserve the checkout path" >&2
    exit 1
fi

export FPT_SINGULARITY_STUB_REQUIRE_USERNS=0
export FPT_SINGULARITY_FAKEROOT=1
build_image=$work_dir/built.sif
"$repo_root/tools/build_singularity.sh" "$build_image"
for argument in build --force --fakeroot "$build_image" \
    "$repo_root/containers/fpt-verilator.def"; do
    if ! rg -Fqx -- "$argument" "$log"; then
        echo "Singularity builder omitted argument: $argument" >&2
        exit 1
    fi
done

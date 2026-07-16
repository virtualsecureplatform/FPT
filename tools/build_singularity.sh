#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
definition=$repo_root/containers/fpt-verilator.def
image=${1:-$repo_root/build/singularity/fpt-verilator.sif}

runtime=${FPT_SINGULARITY_RUNTIME:-}
if [[ -z $runtime ]]; then
    if command -v singularity >/dev/null 2>&1; then
        runtime=singularity
    elif command -v apptainer >/dev/null 2>&1; then
        runtime=apptainer
    else
        echo "Singularity CE or Apptainer is required to build the image" >&2
        exit 1
    fi
fi
if ! command -v "$runtime" >/dev/null 2>&1; then
    echo "Container runtime not found: $runtime" >&2
    exit 1
fi

required_sources=(
    chisel/build.sbt
    chisel/project/build.properties
    third_party/SGen/build.sbt
    third_party/SGen/project/build.properties
    third_party/SGen/project/plugins.sbt
    third_party/HOGE/chisel/HomGate/build.sbt
)
for source in "${required_sources[@]}"; do
    if [[ ! -f $repo_root/$source ]]; then
        echo "Container input is missing: $source" >&2
        echo "Run 'git submodule update --init --recursive' first" >&2
        exit 1
    fi
done

image=$(realpath -m "$image")
mkdir -p "$(dirname "$image")"
build_options=(--force)
case "${FPT_SINGULARITY_FAKEROOT:-auto}" in
    auto)
        if [[ $(id -u) != 0 ]]; then
            build_options+=(--fakeroot)
        fi
        ;;
    0) ;;
    1) build_options+=(--fakeroot) ;;
    *)
        echo "FPT_SINGULARITY_FAKEROOT must be auto, 0, or 1" >&2
        exit 1
        ;;
esac
(
    cd "$repo_root"
    "$runtime" build "${build_options[@]}" "$image" "$definition"
)
printf 'Built FPT Singularity image: %s\n' "$image"

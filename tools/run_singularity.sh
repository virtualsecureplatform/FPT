#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
image=${FPT_SINGULARITY_IMAGE:-$repo_root/build/singularity/fpt-verilator.sif}
cache_dir=${FPT_SINGULARITY_CACHE_DIR:-$repo_root/build/singularity/cache}

runtime=${FPT_SINGULARITY_RUNTIME:-}
if [[ -z $runtime ]]; then
    if command -v singularity >/dev/null 2>&1; then
        runtime=singularity
    elif command -v apptainer >/dev/null 2>&1; then
        runtime=apptainer
    else
        echo "Singularity CE or Apptainer is required to run the image" >&2
        exit 1
    fi
fi
if ! command -v "$runtime" >/dev/null 2>&1; then
    echo "Container runtime not found: $runtime" >&2
    exit 1
fi
if [[ ! -f $image ]]; then
    echo "FPT Singularity image not found: $image" >&2
    echo "Build it with tools/build_singularity.sh" >&2
    exit 1
fi

image=$(realpath "$image")
cache_dir=$(realpath -m "$cache_dir")
mkdir -p "$cache_dir/home"

execution_options=()
case "${FPT_SINGULARITY_USERNS:-auto}" in
    auto)
        if ! "$runtime" exec --cleanenv "$image" /bin/true \
            >/dev/null 2>&1; then
            if "$runtime" exec --userns --cleanenv "$image" /bin/true \
                >/dev/null 2>&1; then
                execution_options+=(--userns)
            else
                echo "The container cannot run in setuid or userns mode" >&2
                exit 1
            fi
        fi
        ;;
    0) ;;
    1) execution_options+=(--userns) ;;
    *)
        echo "FPT_SINGULARITY_USERNS must be auto, 0, or 1" >&2
        exit 1
        ;;
esac

seed_marker=$cache_dir/.fpt-sbt-cache-seeded
if [[ ! -e $seed_marker ]]; then
    "$runtime" exec "${execution_options[@]}" --cleanenv \
        --bind "$cache_dir:/fpt-cache" \
        "$image" \
        /bin/bash -c \
        'set -eu; cp -R /opt/fpt-cache-seed/. /fpt-cache/; touch /fpt-cache/.fpt-sbt-cache-seeded'
fi

sbt_options="-Dsbt.global.base=/fpt-cache/sbt/global -Dsbt.boot.directory=/fpt-cache/sbt/boot -Dsbt.ivy.home=/fpt-cache/ivy2 -Dsbt.server.autostart=false"
if [[ -n ${SBT_OPTS:-} ]]; then
    sbt_options+=" $SBT_OPTS"
fi

runtime_options=(
    "${execution_options[@]}"
    --cleanenv
    --home "$cache_dir/home"
    --bind "$repo_root:$repo_root"
    --bind "$cache_dir:/fpt-cache"
    --pwd "$repo_root"
    --env "COURSIER_CACHE=/fpt-cache/coursier"
    --env "XDG_CACHE_HOME=/fpt-cache/xdg"
    --env "SBT_OPTS=$sbt_options"
)

while IFS='=' read -r name value; do
    case "$name" in
        FPT_SINGULARITY_*) ;;
        FPT_*|MAKEFLAGS) runtime_options+=(--env "$name=$value") ;;
    esac
done < <(env)

if [[ $# == 0 ]]; then
    exec "$runtime" shell "${runtime_options[@]}" "$image"
fi
exec "$runtime" exec "${runtime_options[@]}" "$image" "$@"

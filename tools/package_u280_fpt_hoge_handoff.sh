#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
prepared_root=${1:-$repo_root/build/vivado-u280-fpt-hoge-prepared}
bundle_root=${2:-$repo_root/build/u280-fpt-hoge-portable}

for tool in awk find realpath sha256sum sort xargs; do
    if ! command -v "$tool" >/dev/null; then
        echo "Required tool not found: $tool" >&2
        exit 1
    fi
done

prepared_root=$(realpath "$prepared_root")
bundle_root=$(realpath -m "$bundle_root")
manifest=$prepared_root/manifest.tsv
if [[ ! -s $manifest ]]; then
    echo "Prepared manifest not found: $manifest" >&2
    exit 1
fi
if [[ -e $bundle_root ]]; then
    echo "Portable bundle destination already exists: $bundle_root" >&2
    exit 1
fi

manifest_value() {
    local key=$1
    awk -F '\t' -v key="$key" '
        $1 == key { print $2; found = 1; exit }
        END { if (!found) exit 1 }
    ' "$manifest"
}

verify_manifest_file() {
    local key=$1
    local source_file=$2
    local expected
    local actual

    if [[ ! -s $source_file ]]; then
        echo "Prepared handoff input is missing: $source_file" >&2
        exit 1
    fi
    expected=$(manifest_value "$key")
    actual=$(sha256sum "$source_file" | awk '{ print $1 }')
    if [[ $actual != "$expected" ]]; then
        echo "Prepared handoff hash mismatch for $key: expected=$expected actual=$actual" >&2
        exit 1
    fi
}

hash_lines() {
    printf '%s\n' "$@" | sha256sum | awk '{ print $1 }'
}

source_entries=(
    'fpt_forward_sha256:sources/sgen/forward.v'
    'fpt_inverse_sha256:sources/sgen/inverse.v'
    'hoge_forward_sha256:sources/hoge/HOGEForwardINTTBaseline.v'
    'hoge_inverse_sha256:sources/hoge/HOGEInverseNTTBaseline.v'
    'fpt_blind_rotate_sha256:sources/fpt-blind-rotate/BatchedBlindRotateSampleExtractEngine.sv'
    'fpt_buffered_blind_rotate_sha256:sources/fpt-buffered-blind-rotate/BufferedBlindRotateAccelerator.sv'
    'hoge_blind_rotate_sha256:sources/hoge/HOGEBlindRotateBaseline.v'
)
for entry in "${source_entries[@]}"; do
    IFS=: read -r key relative_path <<< "$entry"
    verify_manifest_file "$key" "$prepared_root/$relative_path"
done

flow_entries=(
    'single_source_top_flow_sha256:chisel/scripts/synth_sgen_u280.tcl'
    'composed_top_flow_sha256:chisel/scripts/synth_paper_cmux_u280.tcl'
    'post_route_metrics_flow_sha256:chisel/scripts/u280_post_route_metrics.tcl'
)
for entry in "${flow_entries[@]}"; do
    IFS=: read -r key relative_path <<< "$entry"
    verify_manifest_file "$key" "$repo_root/$relative_path"
done

single_flow_sha=$(hash_lines \
    "$(sha256sum "$repo_root/chisel/scripts/synth_sgen_u280.tcl" | awk '{ print $1 }')" \
    "$(sha256sum "$repo_root/chisel/scripts/u280_post_route_metrics.tcl" | awk '{ print $1 }')")
composed_flow_sha=$(hash_lines \
    "$(sha256sum "$repo_root/chisel/scripts/synth_paper_cmux_u280.tcl" | awk '{ print $1 }')" \
    "$(sha256sum "$repo_root/chisel/scripts/u280_post_route_metrics.tcl" | awk '{ print $1 }')")
if [[ $single_flow_sha != "$(manifest_value single_source_flow_sha256)" || \
      $composed_flow_sha != "$(manifest_value composed_flow_sha256)" ]]; then
    echo "Prepared handoff combined flow hash mismatch" >&2
    exit 1
fi

for checkout in fpt hoge sgen; do
    state=$(manifest_value "${checkout}_tracked_state")
    if [[ $state != clean ]]; then
        echo "Refusing tracked-dirty $checkout source provenance: $state" >&2
        exit 1
    fi
done

mkdir -p "$bundle_root/sources/sgen" \
    "$bundle_root/sources/hoge" \
    "$bundle_root/sources/fpt-blind-rotate" \
    "$bundle_root/sources/fpt-buffered-blind-rotate" \
    "$bundle_root/tools" \
    "$bundle_root/chisel/scripts" \
    "$bundle_root/docs"

for entry in "${source_entries[@]}"; do
    IFS=: read -r _ relative_path <<< "$entry"
    cp "$prepared_root/$relative_path" "$bundle_root/$relative_path"
done
cp "$manifest" "$bundle_root/manifest.tsv"
cp "$repo_root/tools/run_prepared_u280_fpt_hoge_comparison.sh" \
    "$repo_root/tools/check_u280_route_metrics.sh" \
    "$repo_root/tools/report_u280_fpt_hoge_comparison.sh" \
    "$bundle_root/tools/"
for entry in "${flow_entries[@]}"; do
    IFS=: read -r _ relative_path <<< "$entry"
    cp "$repo_root/$relative_path" "$bundle_root/chisel/scripts/"
done
cp "$repo_root/docs/fpt-hoge-comparison.md" \
    "$repo_root/docs/u280-handoff.md" \
    "$bundle_root/docs/"
chmod +x "$bundle_root/tools/"*.sh

prepared_manifest_sha=$(sha256sum "$manifest" | awk '{ print $1 }')
{
    printf 'bundle_format_version\t2\n'
    printf 'bundle_generated_utc\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'prepared_manifest_sha256\t%s\n' "$prepared_manifest_sha"
} >> "$bundle_root/manifest.tsv"

(
    cd "$bundle_root"
    find manifest.tsv sources tools chisel docs -type f -print0 |
        sort -z | xargs -0 sha256sum
) > "$bundle_root/bundle.sha256"

file_count=$(wc -l < "$bundle_root/bundle.sha256")
bundle_bytes=$(du -sb "$bundle_root" | awk '{ print $1 }')
printf 'Packaged %s verified files (%s bytes) in %s\n' \
    "$file_count" "$bundle_bytes" "$bundle_root"
printf 'Verify without Vivado: FPT_PREPARED_VERIFY_ONLY=1 %s/tools/run_prepared_u280_fpt_hoge_comparison.sh %s\n' \
    "$bundle_root" "$bundle_root"

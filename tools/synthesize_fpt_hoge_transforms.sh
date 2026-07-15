#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source_root=${FPT_YOSYS_TRANSFORM_SOURCES:-\
$repo_root/build/vivado-u280-fpt-hoge-prepared/sources}
build_root=${FPT_YOSYS_TRANSFORM_BUILD:-\
$repo_root/build/yosys-fpt-hoge-transforms}

if (( $# > 0 )); then
    designs=("$@")
else
    designs=(fpt-forward hoge-forward fpt-inverse hoge-inverse)
fi

for tool in jq rg yosys sha256sum; do
    if ! command -v "$tool" >/dev/null; then
        echo "Required tool not found: $tool" >&2
        exit 1
    fi
done
if [[ ! -x /usr/bin/time ]]; then
    echo "/usr/bin/time is required" >&2
    exit 1
fi

source_root=$(realpath "$source_root")
build_root=$(realpath -m "$build_root")
if [[ $source_root == *[[:space:]]* || $build_root == *[[:space:]]* ]]; then
    echo "Yosys source and build paths must not contain whitespace" >&2
    exit 1
fi

parameters_for() {
    case "$1" in
        fpt-forward)
            echo 'sgen/forward.v FptSGenForward verilog'
            ;;
        hoge-forward)
            echo 'hoge/HOGEForwardINTTBaseline.v HOGEForwardINTTBaseline systemverilog'
            ;;
        fpt-inverse)
            echo 'sgen/inverse.v FptSGenInverse verilog'
            ;;
        hoge-inverse)
            echo 'hoge/HOGEInverseNTTBaseline.v HOGEInverseNTTBaseline systemverilog'
            ;;
        *)
            echo "Unknown transform design: $1" >&2
            return 1
            ;;
    esac
}

validate_fpt_dsp_contract() {
    local design=$1
    local source_file=$2
    local stat_file=$3
    local multiply_expressions
    local mapped_dsps

    case "$design" in
        fpt-forward|fpt-inverse) ;;
        *) return ;;
    esac

    multiply_expressions=$(rg -c \
        '\$signed\([^)]*\) \* \$signed\(' "$source_file" || true)
    mapped_dsps=$(jq -r '
        [(.design.num_cells_by_type // {} | to_entries[])
          | select(.key | test("^DSP"))
          | .value]
        | add // 0
    ' "$stat_file")
    if [[ ! $multiply_expressions =~ ^[1-9][0-9]*$ || \
          $mapped_dsps != "$multiply_expressions" ]]; then
        echo "$design violates the one-DSP-per-split-product contract: expressions=$multiply_expressions DSPs=$mapped_dsps" >&2
        exit 1
    fi
    echo "Validated $design DSP mapping: $multiply_expressions expressions -> $mapped_dsps DSPs"
}

yosys_version=$(yosys -V)
flow='synth_xilinx -family xcup -flatten -noiopad -noclkbuf -widemux 5; check; stat -tech xilinx -json'
mkdir -p "$build_root"
for design in "${designs[@]}"; do
    read -r relative_source top language <<<"$(parameters_for "$design")"
    source_file=$source_root/$relative_source
    if [[ ! -s $source_file ]]; then
        echo "Missing transform source: $source_file" >&2
        exit 1
    fi

    result_dir=$build_root/$design
    stat_file=$result_dir/stat.json
    signature_file=$result_dir/input.sha256
    mkdir -p "$result_dir"
    source_sha=$(sha256sum "$source_file" | awk '{ print $1 }')
    signature=$(printf '%s\n' "$source_sha" "$top" "$language" \
        "$yosys_version" "$flow" | sha256sum | awk '{ print $1 }')

    if [[ -s $stat_file && -s $signature_file && \
          $(<"$signature_file") == "$signature" ]]; then
        echo "Reusing signature-matched $design map"
        validate_fpt_dsp_contract "$design" "$source_file" "$stat_file"
        continue
    fi

    echo "Synthesizing $design for UltraScale+"
    rm -f "$stat_file" "$result_dir/timing.txt"
    read_command="read_verilog"
    if [[ $language == systemverilog ]]; then
        read_command+=' -sv'
    fi
    yosys_command="$read_command $source_file; "
    yosys_command+="synth_xilinx -family xcup -top $top -flatten "
    yosys_command+="-noiopad -noclkbuf -widemux 5; check; "
    yosys_command+="tee -o $stat_file stat -tech xilinx -json"
    if ! /usr/bin/time \
        -f 'elapsed_seconds=%e\nmax_rss_kib=%M' \
        -o "$result_dir/timing.txt" \
        yosys -q -l "$result_dir/synth.log" -p "$yosys_command" \
        >"$result_dir/console.log" 2>&1; then
        tail -n 100 "$result_dir/console.log" >&2
        exit 1
    fi
    jq -e --arg top "\\$top" \
        '.modules[$top].num_cells > 0 and .design.num_cells > 0' \
        "$stat_file" >/dev/null
    validate_fpt_dsp_contract "$design" "$source_file" "$stat_file"
    printf '%s\n' "$signature" > "$signature_file"
done

"$repo_root/tools/report_yosys_fpt_hoge_transforms.sh" \
    "$build_root" "${designs[@]}"

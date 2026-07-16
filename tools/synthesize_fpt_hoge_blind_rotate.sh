#!/usr/bin/env bash
set -euo pipefail

script_path=$(realpath "${BASH_SOURCE[0]}")
repo_root=$(cd "$(dirname "$script_path")/.." && pwd)
source_root=${FPT_YOSYS_BLIND_ROTATE_SOURCES:-\
$repo_root/build/vivado-u280-fpt-hoge-prepared/sources}
manifest=${FPT_YOSYS_BLIND_ROTATE_MANIFEST:-\
$repo_root/build/vivado-u280-fpt-hoge-prepared/manifest.tsv}
build_root=${FPT_YOSYS_BLIND_ROTATE_BUILD:-\
$repo_root/build/yosys-fpt-hoge-blind-rotate}

if (( $# > 0 )); then
    designs=("$@")
else
    designs=(fpt-blind-rotate hoge-blind-rotate)
fi

for tool in awk jq rg sha256sum yosys; do
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
manifest=$(realpath "$manifest")
build_root=$(realpath -m "$build_root")
if [[ $source_root == *[[:space:]]* || $build_root == *[[:space:]]* ]]; then
    echo "Yosys source and build paths must not contain whitespace" >&2
    exit 1
fi
if [[ ! -s $manifest ]]; then
    echo "Prepared manifest not found: $manifest" >&2
    exit 1
fi

manifest_value() {
    local key=$1
    awk -F '\t' -v key="$key" '
        $1 == key { print $2; found = 1; exit }
        END { if (!found) exit 1 }
    ' "$manifest"
}

sha256() {
    sha256sum "$1" | awk '{ print $1 }'
}

verify_source() {
    local key=$1
    local source_file=$2
    local expected
    local actual

    if [[ ! -s $source_file ]]; then
        echo "Missing Blind Rotate source: $source_file" >&2
        exit 1
    fi
    expected=$(manifest_value "$key")
    actual=$(sha256 "$source_file")
    if [[ $actual != "$expected" ]]; then
        echo "Blind Rotate source hash mismatch for $key: expected=$expected actual=$actual" >&2
        exit 1
    fi
}

fpt_top=$(manifest_value fpt_blind_rotate_top)
hoge_top=$(manifest_value hoge_blind_rotate_top)
fpt_br=$source_root/fpt-blind-rotate/BatchedBlindRotateSampleExtractEngine.sv
fpt_forward=$source_root/sgen/forward.v
fpt_inverse=$source_root/sgen/inverse.v
hoge_br=$source_root/hoge/HOGEBlindRotateBaseline.v
verify_source fpt_blind_rotate_sha256 "$fpt_br"
verify_source fpt_forward_sha256 "$fpt_forward"
verify_source fpt_inverse_sha256 "$fpt_inverse"
verify_source hoge_blind_rotate_sha256 "$hoge_br"

fpt_forward_products=$(rg -c \
    '\$signed\([^)]*\) \* \$signed\(' "$fpt_forward" || true)
fpt_inverse_products=$(rg -c \
    '\$signed\([^)]*\) \* \$signed\(' "$fpt_inverse" || true)
fpt_gauss_modules=$(rg -c '^module FptExactGaussComplexMultiply ' \
    "$fpt_br" || true)
fpt_split_modules=$(rg -c '^module FptSignedSplitMultiply ' \
    "$fpt_br" || true)
fpt_split_instances=$(rg -c '^  FptSignedSplitMultiply #' \
    "$fpt_br" || true)
fpt_inverse_instances=$(rg -c '^  FptSGenInverse generated ' \
    "$fpt_br" || true)
if [[ ! $fpt_forward_products =~ ^[1-9][0-9]*$ || \
      ! $fpt_inverse_products =~ ^[1-9][0-9]*$ || \
      $fpt_gauss_modules != 1 || $fpt_split_modules != 1 || \
      $fpt_split_instances != 3 || $fpt_inverse_instances != 1 ]]; then
    echo "Unexpected FPT split-product structure: forward=$fpt_forward_products inverse=$fpt_inverse_products gauss_modules=$fpt_gauss_modules split_modules=$fpt_split_modules split_instances=$fpt_split_instances inverse_instances=$fpt_inverse_instances" \
        >&2
    exit 1
fi
fpt_external_product_complex_lanes=$(manifest_value \
    fpt_external_product_complex_lanes)
fpt_external_product_real_products=$(manifest_value \
    fpt_external_product_real_products_per_complex)
fpt_external_product_dsps_per_real=$(manifest_value \
    fpt_external_product_dsps_per_real_product)
fpt_external_product_dsps=$(manifest_value \
    fpt_external_product_expected_dsps)
calculated_external_product_dsps=$((
    fpt_external_product_complex_lanes * fpt_external_product_real_products *
    fpt_external_product_dsps_per_real
))
if [[ $fpt_external_product_dsps != "$calculated_external_product_dsps" ]]; then
    echo "Inconsistent FPT External Product DSP manifest: recorded=$fpt_external_product_dsps calculated=$calculated_external_product_dsps" \
        >&2
    exit 1
fi
# The exact Gauss MAC maps three real products per complex lane. Each explicit
# 30x27-bit product is split into two signed DSP48E2-sized products. Yosys
# merges the mutually exclusive accumulator-buffer branches, giving
# 256 * 3 * 2 = 1536 DSPs. The serialized component path instantiates one
# inverse transform, and two additional DSPs remain in wrapper glue.
fpt_wrapper_glue_dsps=2
fpt_expected_dsps=$((
    fpt_forward_products + fpt_inverse_products +
    fpt_external_product_dsps + fpt_wrapper_glue_dsps
))
hoge_multipliers=$(manifest_value hoge_blind_rotate_intorus_mul_instances)
if [[ ! $hoge_multipliers =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid HOGE Blind Rotate multiplier count: $hoge_multipliers" >&2
    exit 1
fi
hoge_expected_dsps=$((16 * hoge_multipliers))

mapped_dsps() {
    jq -r '
        [(.design.num_cells_by_type // {} | to_entries[])
          | select(.key | test("^DSP"))
          | .value]
        | add // 0
    ' "$1"
}

validate_dsp_contract() {
    local design=$1
    local stat_file=$2
    local expected
    local actual

    case "$design" in
        fpt-blind-rotate) expected=$fpt_expected_dsps ;;
        hoge-blind-rotate) expected=$hoge_expected_dsps ;;
        *) return 1 ;;
    esac
    actual=$(mapped_dsps "$stat_file")
    if [[ $actual != "$expected" ]]; then
        echo "$design violates its complete-wrapper DSP contract: expected=$expected actual=$actual" >&2
        exit 1
    fi
    echo "Validated $design DSP mapping: $actual DSP48E2 primitives"
}

completed_yosys_map() {
    local stat_file=$1
    local timing_file=$2
    local log_file=$3
    local yosys_command=$4
    shift 4
    local source_file
    local log_birth

    [[ -s $stat_file && -s $timing_file && -s $log_file ]] || return 1
    jq -e '.design.num_cells > 0' "$stat_file" >/dev/null || return 1
    grep -Fq -- "-- Running command \`$yosys_command' --" "$log_file" || \
        return 1
    tail -n 8 "$log_file" | grep -Fq 'End of script.' || return 1
    tail -n 8 "$log_file" | grep -Fq "$yosys_version" || return 1
    grep -Eq '^elapsed_seconds=[0-9]+([.][0-9]+)?$' "$timing_file" || \
        return 1
    grep -Eq '^max_rss_kib=[1-9][0-9]*$' "$timing_file" || return 1

    # An unstamped result is recoverable only when every source predates the
    # synthesis log inode. This rejects a stale result after in-place source
    # regeneration while allowing a post-map validation contract to evolve.
    log_birth=$(stat -c %W "$log_file")
    [[ $log_birth =~ ^[1-9][0-9]*$ ]] || return 1
    for source_file in "$@"; do
        (( $(stat -c %Y "$source_file") <= log_birth )) || return 1
    done
}

yosys_version=$(yosys -V)
flow='synth_xilinx -family xcup -flatten -noiopad -noclkbuf -nosrl; check; stat -tech xilinx -json'
mkdir -p "$build_root"
for design in "${designs[@]}"; do
    case "$design" in
        fpt-blind-rotate)
            top=$fpt_top
            source_files=("$fpt_br" "$fpt_forward" "$fpt_inverse")
            source_hashes=("$(sha256 "$fpt_br")" \
                "$(sha256 "$fpt_forward")" "$(sha256 "$fpt_inverse")")
            read_commands="read_verilog -sv -DSYNTHESIS $fpt_br; "
            read_commands+="read_verilog $fpt_forward; "
            read_commands+="read_verilog $fpt_inverse; "
            ;;
        hoge-blind-rotate)
            top=$hoge_top
            source_files=("$hoge_br")
            source_hashes=("$(sha256 "$hoge_br")")
            read_commands="read_verilog -sv $hoge_br; "
            ;;
        *)
            echo "Unknown Blind Rotate design: $design" >&2
            exit 1
            ;;
    esac

    result_dir=$build_root/$design
    stat_file=$result_dir/stat.json
    signature_file=$result_dir/input.sha256
    timing_file=$result_dir/timing.txt
    log_file=$result_dir/synth.log
    mkdir -p "$result_dir"
    signature=$(
        printf '%s\n' "${source_hashes[@]}" "$top" "$yosys_version" \
            "$flow" | sha256sum | awk '{ print $1 }'
    )
    yosys_command="$read_commands"
    yosys_command+="synth_xilinx -family xcup -top $top -flatten "
    # Do not enable pmux2shiftx for these very wide complete wrappers. Its
    # intermediate expansion exceeds 50 GiB on the FPT design before LUT
    # mapping. The -nosrl path keeps both designs comparable and bounded.
    yosys_command+="-noiopad -noclkbuf -nosrl; check; "
    yosys_command+="tee -o $stat_file stat -tech xilinx -json"
    if [[ -s $stat_file && -s $signature_file && \
          $(<"$signature_file") == "$signature" ]]; then
        echo "Reusing signature-matched $design map"
        validate_dsp_contract "$design" "$stat_file"
        continue
    fi
    if [[ ! -e $signature_file ]] && completed_yosys_map \
          "$stat_file" "$timing_file" "$log_file" "$yosys_command" \
          "${source_files[@]}"; then
        echo "Recovering completed unstamped $design map"
        validate_dsp_contract "$design" "$stat_file"
        printf '%s\n' "$signature" > "$signature_file"
        continue
    fi

    echo "Synthesizing complete $design wrapper for UltraScale+"
    rm -f "$stat_file" "$timing_file" "$signature_file"
    if ! /usr/bin/time \
        -f 'elapsed_seconds=%e\nmax_rss_kib=%M' \
        -o "$timing_file" \
        yosys -q -l "$log_file" -p "$yosys_command" \
        >"$result_dir/console.log" 2>&1; then
        tail -n 100 "$result_dir/console.log" >&2
        exit 1
    fi
    jq -e --arg top "\\$top" \
        '.modules[$top].num_cells > 0 and .design.num_cells > 0' \
        "$stat_file" >/dev/null
    validate_dsp_contract "$design" "$stat_file"
    printf '%s\n' "$signature" > "$signature_file"
done

"$repo_root/tools/report_yosys_fpt_hoge_blind_rotate.sh" \
    "$build_root" "$manifest" "${designs[@]}"

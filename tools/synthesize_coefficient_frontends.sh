#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
build_root=${FPT_YOSYS_BUILD:-$repo_root/build/yosys-coeff}
heap_size=${FPT_CHISEL_HEAP:-12G}
reuse=${FPT_YOSYS_REUSE:-0}

if (( $# > 0 )); then
    designs=("$@")
else
    designs=(barrel-single bitwise-single)
fi

for tool in jq sbt yosys; do
    if ! command -v "$tool" >/dev/null; then
        echo "Required tool not found: $tool" >&2
        exit 1
    fi
done
if [[ $repo_root == *[[:space:]]* ]]; then
    echo "Yosys build paths must not contain whitespace: $repo_root" >&2
    exit 1
fi

parameters_for() {
    case "$1" in
        barrel-single)
            echo 'fpt.EmitPaperYosysBarrelCoefficient CmuxCoefficientStore'
            ;;
        bitwise-single)
            echo 'fpt.EmitPaperYosysBitwiseCoefficient BitwiseCmuxForwardFrontend'
            ;;
        barrel-batched)
            echo 'fpt.EmitPaperYosysBarrelBatchedCoefficient PrefetchedBatchedCmuxCoefficientStore'
            ;;
        bitwise-batched)
            echo 'fpt.EmitPaperYosysBitwiseBatchedCoefficient BitwisePrefetchedBatchedCmuxCoefficientStore'
            ;;
        *)
            echo "Unknown coefficient frontend: $1" >&2
            return 1
            ;;
    esac
}

mkdir -p "$build_root"
for design in "${designs[@]}"; do
    read -r emitter top <<<"$(parameters_for "$design")"
    result_dir=$build_root/$design
    source_file=$result_dir/$top.sv
    stat_file=$result_dir/stat.json
    mkdir -p "$result_dir"

    if [[ $reuse != 1 || ! -s $stat_file ]]; then
        echo "Emitting $design"
        (cd "$repo_root/chisel" &&
            sbt -J-Xmx"$heap_size" "runMain $emitter $result_dir")
        if [[ ! -s $source_file ]]; then
            echo "Chisel did not emit $source_file" >&2
            exit 1
        fi
        if rg -q "=\\s*'\\{" "$source_file"; then
            echo "Unsupported assignment pattern remains in $source_file" >&2
            exit 1
        fi

        echo "Synthesizing $design for UltraScale+"
        /usr/bin/time \
            -f 'elapsed_seconds=%e\nmax_rss_kib=%M' \
            -o "$result_dir/timing.txt" \
            yosys -q -l "$result_dir/synth.log" -p \
            "read_verilog -sv $source_file; \
             synth_xilinx -family xcup -top $top -flatten -noiopad -noclkbuf -widemux 5; \
             tee -o $stat_file stat -tech xilinx -json"
    else
        echo "Reusing $stat_file"
    fi
done

"$repo_root/tools/report_yosys_coefficient_frontends.sh" \
    "$build_root" "${designs[@]}"

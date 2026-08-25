#!/usr/bin/env bash
set -euo pipefail

target=${1:?usage: build_common.sh hw_emu|hw [--xo-only|--test]}
shift
xo_only=0
run_test=0
if [[ ${1:-} == --xo-only ]]; then
    xo_only=1
elif [[ ${1:-} == --test ]]; then
    run_test=1
elif [[ $# -ne 0 ]]; then
    echo "usage: build_common.sh hw_emu|hw [--xo-only|--test]" >&2
    exit 1
fi
if [[ $target != hw_emu && $target != hw ]]; then
    echo "target must be hw_emu or hw" >&2
    exit 1
fi

vitis_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$vitis_dir/.." && pwd)
build_dir=$vitis_dir/build
generated_dir=$build_dir/generated
platform=${FPT_PLATFORM:-xilinx_u280_gen3x16_xdma_1_202211_1}
floorplan=${FPT_FLOORPLAN:-A}

if [[ -f /opt/xilinx/Vitis/2023.2/settings64.sh ]]; then
    source /opt/xilinx/Vitis/2023.2/settings64.sh
elif [[ -f /home/opt/xilinx/Vitis/2023.2/settings64.sh ]]; then
    source /home/opt/xilinx/Vitis/2023.2/settings64.sh
elif [[ -z ${XILINX_VITIS:-} ]]; then
    echo "Vitis 2023.2 environment not found" >&2
    exit 1
fi

mkdir -p "$generated_dir" "$build_dir/xo" "$build_dir/xclbin" \
    "$build_dir/vpp_log" "$build_dir/vpp_temp" "$build_dir/vpp_report"
if [[ ! -x $repo_dir/third_party/SGen/sgen.bat ]]; then
    (cd "$repo_dir/third_party/SGen" && sbt assembly)
fi
FPT_ARITHMETIC_PROFILE=paper-set-ii \
    FFT_LOG_LANES=${FFT_LOG_LANES:-7} \
    IFFT_LOG_LANES=${IFFT_LOG_LANES:-6} \
    RADIX_LOG=${RADIX_LOG:-3} \
    IFFT_RADIX_LOG=${IFFT_RADIX_LOG:-3} \
    FPT_SGEN_FORWARD_SWITCH_TRANSPOSE=${FPT_SGEN_FORWARD_SWITCH_TRANSPOSE:-0} \
    FPT_SGEN_INVERSE_SWITCH_TRANSPOSE=${FPT_SGEN_INVERSE_SWITCH_TRANSPOSE:-0} \
    "$repo_dir/tools/generate_sgen_fpt.sh" \
    "$repo_dir/third_party/SGen" "$generated_dir"
(cd "$repo_dir/chisel" && sbt -J-Xmx12G \
    "runMain fpt.EmitFptBlindRotateKernelController $generated_dir $generated_dir/forward.v $generated_dir/inverse.v")

export FPT_FLOORPLAN=$floorplan
vivado -mode batch -source "$vitis_dir/scripts/build_fpt_xo.tcl" \
    -log "$build_dir/build_fpt_xo.log" \
    -journal "$build_dir/build_fpt_xo.jou"
if [[ $xo_only -eq 1 ]]; then
    exit 0
fi

xclbin=$build_dir/xclbin/FptBlindRotateKernel_${target}_${floorplan}.xclbin
debug_options=()
if [[ $target == hw ]]; then
    debug_options=(-g)
fi
v++ -l "${debug_options[@]}" -t "$target" --platform "$platform" \
    --config "$vitis_dir/cfg/link_config.cfg" --kernel_frequency 200 \
    -o "$xclbin" "$build_dir/xo/FptBlindRotateKernel.xo" \
    --log_dir "$build_dir/vpp_log/${target}_${floorplan}" \
    --temp_dir "$build_dir/vpp_temp/${target}_${floorplan}" \
    --report_dir "$build_dir/vpp_report/${target}_${floorplan}"
if [[ $target == hw_emu ]]; then
    emconfigutil --platform "$platform" --od "$build_dir/xclbin"
fi
echo "Built $xclbin"

if [[ $run_test -eq 1 ]]; then
    c++ -std=c++17 -O2 -I/opt/xilinx/xrt/include \
        "$vitis_dir/host/smoke.cpp" -L/opt/xilinx/xrt/lib \
        -lxrt_coreutil -pthread -o "$build_dir/fpt_kernel_smoke"
    if [[ $target == hw_emu ]]; then
        export XCL_EMULATION_MODE=hw_emu
        export EMCONFIG_PATH="$build_dir/xclbin"
    fi
    "$build_dir/fpt_kernel_smoke" "$xclbin"
fi

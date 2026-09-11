#!/usr/bin/env bash
# Run on ScratchReadoutHarness.sv emitted by Test / runMain
# fpt.EmitScratchReadoutGate. This catches generate-scope regressions that
# Verilator accepts but XSim 2023.2 cannot elaborate.
set -euo pipefail
if [[ $# != 1 ]]; then
    echo "Usage: $0 /path/to/ScratchReadoutHarness.sv" >&2
    exit 2
fi
scratch_rtl=$(realpath "$1")
test -f "$scratch_rtl"
xsim_bin=${XILINX_VIVADO:+$XILINX_VIVADO/bin/}
scratch_run=$(mktemp -d "${TMPDIR:-/tmp}/fpt-scratch-xsim.XXXXXX")
echo "XSim scratch regression logs: $scratch_run"
cd "$scratch_run"
"${xsim_bin}xvlog" --sv "$scratch_rtl" > compile.log 2>&1
"${xsim_bin}xelab" ScratchReadoutHarness -s scratch_regression > elaborate.log 2>&1
grep -q 'Built simulation snapshot scratch_regression' elaborate.log
echo 'FPT scratch XSim elaboration passed'

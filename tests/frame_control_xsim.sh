#!/usr/bin/env bash
set -euo pipefail
test "$#" = 4
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ruby "$repo/tests/frame_control_xsim.rb" "$1" "$2" "$3" "$4"
cd "$4"
vivado_bin=${XILINX_VIVADO:-/home/opt/xilinx/Vivado/2023.2}/bin
"$vivado_bin/xvlog" --sv reference.v candidate.v tb.sv > compile.log 2>&1
"$vivado_bin/xelab" frame_control_tb --timescale 1ns/1ps -s frame_control_sim > elaborate.log 2>&1
"$vivado_bin/xsim" frame_control_sim -runall > simulate.log 2>&1
rg '^FRAME_XSIM_PASS ' simulate.log
if rg 'Fatal:|Error:|ERROR:' simulate.log; then exit 1; fi

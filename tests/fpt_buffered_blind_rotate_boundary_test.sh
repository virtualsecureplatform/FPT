#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

source_file=$work_dir/BufferedBlindRotateAccelerator.sv
cat > "$source_file" <<'EOF'
module BootstrappingKeyPingPongBuffer(
  input clock
);
  (* ram_style = "block" *) reg [13823:0] Memory[0:31];
endmodule
module CacheOwner(
  input clock
);
  BootstrappingKeyPingPongBuffer keyBuffer (
    .clock(clock)
  );
endmodule
module BufferedBlindRotateAccelerator(
  input         clock,
                reset,
                io_inputStart,
  output        io_inputStartReady,
  input  [3:0]  io_inputContext,
  input  [31:0] io_testVector,
  input         io_inputValid,
  output        io_inputReady,
  input  [31:0] io_inputCoefficient,
  output        io_inputDone,
  output [3:0]  io_inputDoneContext,
  input         io_keyLoadStart,
  output        io_keyLoadStartReady,
  input  [9:0]  io_keyLoadIndex,
  input         io_keyLoadValid,
  output        io_keyLoadReady,
  input  [26:0] io_keyLoad_0_real,
EOF
for lane in {0..15}; do
    for component in real imag; do
        if [[ $lane == 0 && $component == real ]]; then
            continue
        fi
        printf '                io_keyLoad_%s_%s,\n' "$lane" "$component" \
            >> "$source_file"
    done
done
cat >> "$source_file" <<'EOF'
  output        io_keyLoadDone,
  output [9:0]  io_keyLoadDoneIndex,
  input         io_runStart,
  output        io_runReady,
                io_active,
                io_computeDone,
                io_resultValid,
  input         io_resultReady,
  output [31:0] io_result,
  output [3:0]  io_resultContext,
  output        io_resultLast,
                io_done
);
endmodule
EOF

metrics=$work_dir/metrics.tsv
"$repo_root/tools/check_fpt_buffered_blind_rotate_boundary.sh" \
    "$source_file" "$metrics" >/dev/null
awk -F '\t' '
    $1 == "top_port_bits" && $2 == 1012 { ports = 1 }
    $1 == "key_data_bits" && $2 == 864 { key = 1 }
    $1 == "cache_logical_bits" && $2 == 442368 { cache = 1 }
    END { exit !(ports && key && cache) }
' "$metrics"

cp "$source_file" "$work_dir/paper.sv"
sed -i \
    -e 's/\[13823:0\] Memory\[0:31\]/[14847:0] Memory[0:31]/' \
    -e 's/input  \[26:0\] io_keyLoad_0_real/input  [28:0] io_keyLoad_0_real/' \
    "$source_file"
hardware_metrics=$work_dir/hardware-metrics.tsv
"$repo_root/tools/check_fpt_buffered_blind_rotate_boundary.sh" \
    "$source_file" "$hardware_metrics" tfhepp-hardware >/dev/null
awk -F '\t' '
    $1 == "arithmetic_profile" && $2 == "tfhepp-hardware" { profile = 1 }
    $1 == "top_port_bits" && $2 == 1076 { ports = 1 }
    $1 == "key_data_bits" && $2 == 928 { key = 1 }
    $1 == "cache_logical_bits" && $2 == 475136 { cache = 1 }
    END { exit !(profile && ports && key && cache) }
' "$hardware_metrics"

cp "$work_dir/paper.sv" "$source_file"

sed -i 's/input  \[26:0\] io_keyLoad_0_real/input  [25:0] io_keyLoad_0_real/' \
    "$source_file"
if "$repo_root/tools/check_fpt_buffered_blind_rotate_boundary.sh" \
    "$source_file" >/dev/null 2>&1; then
    echo "FPT boundary checker accepted a narrowed key-load port" >&2
    exit 1
fi

echo "FPT buffered Blind Rotate physical-boundary tests passed"

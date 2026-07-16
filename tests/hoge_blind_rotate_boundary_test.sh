#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

source_file=$work_dir/HOGEBlindRotateBaseline.v
cat > "$source_file" <<'EOF'
module RWDmem(
  input clock
);
  reg [2047:0] mem [0:383];
endmodule
module TRGSWBatchMemory(
  input clock
);
  RWDmem mem (
    .clock(clock)
  );
endmodule
module CacheOwner0(
  input clock
);
  TRGSWBatchMemory trgswbatchmem (
    .clock(clock)
  );
endmodule
module CacheOwner1(
  input clock
);
  TRGSWBatchMemory trgswbatchmem (
    .clock(clock)
  );
endmodule
module HOGEBlindRotateBaseline(
  input          clock,
  input          reset,
  input          io_tlwe_TVALID,
  output         io_tlwe_TREADY,
  input  [511:0] io_tlwe_TDATA,
  input          io_bootstrappingKey_0_TVALID,
  output         io_bootstrappingKey_0_TREADY,
  input  [511:0] io_bootstrappingKey_0_TDATA,
  input          io_bootstrappingKey_1_TVALID,
  output         io_bootstrappingKey_1_TREADY,
  input  [511:0] io_bootstrappingKey_1_TDATA,
  input          io_bootstrappingKey_2_TVALID,
  output         io_bootstrappingKey_2_TREADY,
  input  [511:0] io_bootstrappingKey_2_TDATA,
  input          io_bootstrappingKey_3_TVALID,
  output         io_bootstrappingKey_3_TREADY,
  input  [511:0] io_bootstrappingKey_3_TDATA,
  input          io_bootstrappingKey_4_TVALID,
  output         io_bootstrappingKey_4_TREADY,
  input  [511:0] io_bootstrappingKey_4_TDATA,
  input          io_bootstrappingKey_5_TVALID,
  output         io_bootstrappingKey_5_TREADY,
  input  [511:0] io_bootstrappingKey_5_TDATA,
  input          io_bootstrappingKey_6_TVALID,
  output         io_bootstrappingKey_6_TREADY,
  input  [511:0] io_bootstrappingKey_6_TDATA,
  input          io_bootstrappingKey_7_TVALID,
  output         io_bootstrappingKey_7_TREADY,
  input  [511:0] io_bootstrappingKey_7_TDATA,
  output         io_result_TVALID,
  input          io_result_TREADY,
  output [31:0]  io_result_TDATA,
  output         io_result_TLAST
);
endmodule
EOF

metrics=$work_dir/metrics.tsv
"$repo_root/tools/check_hoge_blind_rotate_boundary.sh" \
    "$source_file" "$metrics" >/dev/null
awk -F '\t' '
    $1 == "top_port_bits" && $2 == 4663 { ports = 1 }
    $1 == "key_data_bits" && $2 == 4096 { key = 1 }
    $1 == "cache_logical_bits" && $2 == 1572864 { cache = 1 }
    $1 == "key_beats_per_bus" && $2 == 122112 { beats = 1 }
    END { exit !(ports && key && cache && beats) }
' "$metrics"

sed -i 's/input  \[511:0\] io_bootstrappingKey_7_TDATA/input  [255:0] io_bootstrappingKey_7_TDATA/' \
    "$source_file"
if "$repo_root/tools/check_hoge_blind_rotate_boundary.sh" "$source_file" \
    >/dev/null 2>&1; then
    echo "HOGE boundary checker accepted a narrowed key stream" >&2
    exit 1
fi

echo "HOGE Blind Rotate physical-boundary tests passed"

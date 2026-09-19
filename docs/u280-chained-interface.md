# Optional chained interface

Set `FPT_U280_CHAINED_INTERFACE=1` when emitting and packaging the kernel.
The default remains `ap_ctrl_hs`; the optional variant uses `ap_ctrl_chain`.
Use a separate `FPT_VITIS_BUILD_DIR` so the validated sequential image is preserved.

The frontend snapshots all six addresses for up to two outstanding invocations.
A 632-by-512-bit synchronous-memory FIFO prefetches the next input batch while
the current invocation executes. Compute, key streaming, and output DMA remain
serialized; this is not a second compute engine or overlapping arithmetic.
The dimension remains 630, batch size 16, and kernel clock 200 MHz.

Completions remain ordered and held until AP_CTRL bit 4 (`ap_continue`) is
written. Reading AP_CTRL does not consume a chained completion. Auto-restart
is deliberately disabled in chained mode. Output completion requires actual
DMA status, not the legacy drain timeout. Packaging enables DataMover status
FIFOs in this mode.

Additional read-only 32-bit counters (zero in the sequential variant):

| Offset | Counter |
| --- | --- |
| `0x58` | Accepted invocations |
| `0x5c` | Entire next input batches prefetched while computation was active |
| `0x60` | Completed invocations, including errors |

Counters reset with the kernel and wrap modulo 2^32. DMA errors stop further
acceptance and retire accepted jobs in order with errors. Reset is required
before reuse after an error; a partially transferred frame is not reused.

`vitis/host/chained.cpp` submits distinct buffers without per-invocation waits,
checks every output word, and requires counter evidence of input prefetch
overlap. It uses zero keys and nonzero input accumulators to isolate queue
ordering. Existing nonzero-key tests remain necessary for arithmetic coverage.

Validation so far: 14 Chisel tests pass across the frontend, sequencer, wide
output, and key ingress suites; the AXI control test passes in both modes.
The hardware-emulation image builds successfully with chained metadata and
DataMover status FIFOs enabled. The two-batch host test passed all 32,800 words,
with accepted=2, completed=2, and prefetched-while-active=1.
Its per-batch timeout is 60 minutes for hardware emulation and five
minutes for hardware. Emulation wall-clock time is not reported as FPGA throughput.

An isolated Vivado 2023.2 synthesis of `KernelChainFrontend` uses 527 LUTs,
186 registers, 14 RAMB36s, and one RAMB18 (14.5 BRAM tiles), with no DSPs or
URAM. This is a component measurement, not the full linked-kernel delta.
The input FIFO inferred block RAM without a forced RAM-style attribute.
Full-kernel synthesis completed with 530,170 LUTs, 757,886 registers, 482.5 BRAM
tiles, 5,624 DSPs and 236 URAMs. Versus the validated sequential baseline this
is +1,023 LUTs, -234 registers and +14.5 BRAM tiles. Structural and hook checks
passed. The trial-specific LUT cap is increased from 530,000 to 530,170 and
the expected BRAM count from 468 to 482.5, approved after reviewing these
measurements. The register cap and DSP/URAM expectations are unchanged.
P&R resumes from the existing synthesis with the exact emulation-tested XO;
200 MHz timing, complete routing, DRC and structural gates remain unchanged.
P&R subsequently passed with +0.005 ns setup and 0.000 ns hold slack, and
real FPGA correctness and queued throughput tests passed on 2026-09-19.
See [hardware results](u280-chained-hardware-20260919.md) for coverage,
performance measurements, and the artifact hash.

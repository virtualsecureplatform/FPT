# U280 buffered output (opt-in)

The accepted 200 MHz hardware result and its limitations are documented in
[`u280-buffered-output-hardware-20260924.md`](u280-buffered-output-hardware-20260924.md).

## Registered-control trial (September 23)

`build/buffered-output-local` finished routing but failed setup: WNS -0.346 ns,
TNS -125.371 ns, 953 failing endpoints. Global/short and timing congestion
were both 6. The main failing cones originated at output DMA status and the
accumulator initialization FSM; this is not an accepted hardware image.

The fresh trial is `build/buffered-output-control`. It retains the no-flow
drain queue, both elastic output slices, and SLR0 retirement placement, adding:

- One registered DMA status/error sampling boundary and preserved local stop
  registers. Output failures have one owner; the sequencer no longer duplicates
  the raw output-error cone. Fail-stop may take two cycles (10 ns), as approved.
  Transfers accepted in that interval belong to the failed invocation. An error
  in the same sampled event wins over success; reset is required after failure.
- A 321-bit completion snapshot plus valid through SLR0 TX and SLR1 RX registers.
  The two invocation credits reserve capacity; no ready crosses this boundary.
  Snapshot release is not delayed. Completion payloads/counters are captured at
  retirement rather than recomputed two cycles later; live telemetry is separate.
- A registered accumulator initialization command at the coefficient-store
  boundary, enabled only with registered drain and bank-local initialization.
  Ready holds the first beat until the command arrives. This adds one cycle per
  context, not a compute or read/modify/write pipeline stage.

The resource ceilings are 530913 LUTs, 760880 FFs, 488.5 BRAM tiles, 5624 DSPs,
and 236 URAMs. The target remains 200 MHz, n=630, sixteen contexts, capture
<=1152 cycles, handoff <=1601 cycles, and hardware median >=19.5 results/ms.
These are acceptance criteria, not achieved results for this trial.

The driver freezes inputs and runs unit/structural tests, bit-exact numerical
tests (enabled and disabled), and queued nonzero hw_emu before synthesis. It
checks the actual synthesized netlist before automatically starting one P&R.
Early timing/congestion estimates are not acceptance gates. After successful
routing, both final logged congestion estimates must be <=6; missing evidence
rejects acceptance. Setup/hold, route and critical DRC checks remain strict.
Placed/routed checkpoints and essential routed timing/utilization reports are
written before structural rejection. A failed trial is not retried automatically.

Wide-port retirement/sequencer unit tests use explicit register behavior models
with the existing interpreter backend; separate Verilator tests exercise the
actual inline DMA/completion registers. Numerical tests and fresh hw_emu use
the actual generated RTL, not those models.

## Local-output timing revision (September 22)

The first buffered-output implementation routed fully but failed 200 MHz:
WNS -0.283 ns, TNS -33.136 ns, 469 failing endpoints; global/short congestion
6 and timing congestion 7. Its output-packer feedback crossed SLR0/1 three
times in a single cycle. The coefficient drain queue also allowed raw URAM
responses to bypass storage into snapshot capture.

The next trial is `build/buffered-output-local`. Buffered mode now disables
only the drain queue's empty bypass, keeps the two-entry tiled storage, and
hard-places the output-retirement island in SLR0. Two-entry elastic slices
surround its packer (132 input bits, 145 output bits); payload is unreset and
neither slice propagates downstream ready combinationally. Retirement and
last-output timestamps observe the downstream slice's real DMA transfer.
Accumulator update/prefetch and the feature-disabled configuration are unchanged.

The trial permits at most 760158 kernel FFs (+1024 over the previous cap),
with the prior LUT/BRAM/DSP/URAM caps unchanged. Capture <=1152 cycles,
handoff <=1601 cycles, strict routed timing/DRC and >=19.5 results/ms hardware
acceptance remain required. It performs fresh numerical tests and hw_emu
before synthesis, and does not automatically retry a failed P&R.

The old trial's manifests, routed checkpoint, and hardware baseline remain
untouched. Snapshot allocation, no-flow tile identities, registered slice
cones, and placed output-island SLR ownership have separate structural gates.

`FPT_U280_BUFFERED_OUTPUT=1` requires `FPT_U280_PREPROCESSED_INPUT=1`,
`FPT_U280_CHAINED_INTERFACE=1`, and `FPT_U280_OUTPUT_LANES=4`. The default
remains disabled. Parameters, numerical arithmetic, 200 MHz clocks, kernel
arguments, AXI-Lite offsets, output layout, and 128-bit output link are unchanged.

## Storage and ownership

`BufferedSampleExtract` snapshots all sixteen mask polynomials into sixteen
1024-by-32 synchronous BRAM banks. Coefficient `i` of context `c` occupies bank
`i % 16`, row `64*c + i/16`. A separate inferred 16-by-32 memory stores `b(0)`.
Each existing 64-lane accumulator drain beat is held in its existing queue while
four 16-lane slices are written; it is acknowledged only after the fourth slice.
The payload has no reset. Only capture/emission ownership and valid state reset.

The wrapper reports capture completion after the final accumulator drain has
completed. The sequencer releases execution only when capture, all key loads,
and all four successful key DMA statuses are complete. That permits another
prepared batch to initialize and compute without waiting for old output DMA.

`BufferedOutputRetirement` keeps two tagged invocation records and one output
owner. Output pointers, counters, and DMA status cannot be overwritten by a new
execution. The snapshot is retained through final output acceptance **and**
successful S2MM status. If a second computation finishes while the snapshot is
occupied, it waits in the accumulator. There is no second accumulator and no
second snapshot. Host credits still return only on `ap_continue`.

Errors are fail-stop: no new execution or output command is accepted, the
snapshot is not retired, and dispatched invocations retire as errors in order.
Prepared banks belonging to affected work remain frozen until reset. Late DMA
status cannot resume a faulted pipeline. Reset/reinitialize the kernel and
DataMovers before reusing failed requests' buffers.

The existing stage counters remain per invocation. `run_cycles` ends at the
last output handshake, not execution release. `output_tail` includes capture,
buffer waiting, and emission. Completed counters remain readable after host
acknowledgement; while running, the oldest unretired invocation is visible.

## Validation and trial

The trial directory is `build/buffered-output`; its driver is `run-trial.sh`.
The previous `build/preprocessed-input-registered` image/checkpoints are retained.
The driver freezes source hashes, runs unit/structural/numerical regressions,
builds hardware emulation, checks four queued nonzero golden jobs, and only then
starts full synthesis and implementation. It stops on any failed gate.

The isolated buffer probe measured **1171 LUTs, 109 FFs, 16 BRAM tiles, no DSP,
and no URAM**. This is an out-of-context result, not a full-kernel resource or
routed-timing result. The first complete-core numerical test preserved the
released bit-exact output digest. The two-batch overlap test also passed with
reversed context order: capture took 1073 cycles and the next run started 1395
cycles after compute completion. Adding that handoff to the unchanged 161499
compute cycles projects a 162894-cycle steady-state interval before host effects;
this is a simulation-based estimate, not measured FPGA throughput.

Full-kernel limits are 530913 LUTs, 759134 FFs, 488.5 BRAM tiles, 5624 DSPs, and
236 URAMs. The original FF cap was 758967; following synthesis review and user
authorization on September 22 it was raised by 167 FFs (0.022%) to the measured
759134. The full design uses 530678 LUTs (235 fewer than the baseline), 488 BRAM
tiles, and unchanged DSP/URAM. Relative to baseline the sequencer adds 476 FFs;
the IFFT adds 441 FFs despite identical SGen source hashes. This does not yet
establish whether routed congestion improves. No RTL or validated XO changes
are made for this gate adjustment. Routed setup and hold
must both pass at 200 MHz, with clean routing/DRC. Congestion is reported against
the previous global/short 5 and timing 6 baseline, not used to waive timing.

`chained_nonzero.cpp` queues four distinct input/output allocations, alternating
nonzero keys placed at dimension 0 or 629 and reversing context order. It checks
all 65600 output words and trailing output guards. These are oracle-derived
single-active-CMUX fixtures, **not** arbitrary dense random bootstrapping keys.
The separate six-case hardware test retains its dense-key case.

Performance acceptance uses the same five 64-batch measurements as the baseline:
median at least **19.5 results/ms**, versus baseline 19.1947. These throughput
measurements use the existing zero-key workload and are reported separately
from nonzero correctness. Compute duration must remain 161499 cycles. Individual
batch output latency may increase; the trial permits at most 167148 run cycles
(the previous 165948 plus 6 microseconds at 200 MHz).

Do not promote this image until full hardware acceptance passes. No routed
timing or real-hardware throughput improvement is claimed by the RTL change alone.

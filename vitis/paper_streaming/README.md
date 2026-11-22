# Paper Set I streaming kernel

This opt-in U280 backend uses the profile in
`chisel/profiles/paper_set_i_streaming_tf26guard4acc40_raw_bram128_candidate.json`:
n=586, N=512, k=2, two base-256 decomposition levels, and batches of 12.
The shell explicitly selects 128 BRAM state lanes. Public inputs and outputs
are Torus32; lookup/state loading rounds to the documented 17-bit state grid.
The arithmetic uses 32-bit transforms, 28-bit complex keys, 40-bit accumulators,
26 fractional twiddle bits, and four butterfly guard bits.

**Current result (2026-11-19):** build `platform-hw-physical-v22` with
`--floorplan physical-slrs --place-directive AltSpreadLogic_high
--route-directive AggressiveExplore` closes 200 MHz (WNS +0.016 ns, WHS
0.000 ns) and passes the board exact-output test at 28.397 PBS/ms; see
`docs/releases/u280-paper-streaming-200mhz-20261119.md`. The paragraphs below
describe earlier candidates and the general flow.

The earlier accepted-launch candidate passed the complete full-platform
post-opt resource gate at 622,640 total LUTs (557,465 logic, 64,032 LUTRAM,
1,143 SRLs), 651,506 FFs, 6,578 DSPs and 570 BRAM equivalents. This leaves
8,560 LUTs below the 631,200 cap. Both accepted-launch registers were found
in the optimized netlist and assigned to SLR0. These counts precede routing;
200 MHz routed timing and board performance remain unqualified. The resource
limits include DMA, control, key cache, and arithmetic. The older 112-lane
kernel exceeds the total LUT limit.

## Build and physical verification

From the repository root, use a new output directory:

```sh
python3 tools/build_paper_streaming_kernel.py \
  --target hw --floorplan pipeline-slrs \
  --output build/paper-streaming-contract/platform-hw-new \
  --validation build/paper-streaming-contract/encrypted-dma-engine586-prep-fault-cut-rekey-rtl-v1/results.json
```

The builder freezes inputs, checks validation provenance, packages the six
DataMovers, and links at 200 MHz. A post-optimization hook enforces complete
kernel resource limits. The original post-route gate requires nonnegative
setup and hold slack, complete routing, and legal platform DRC. Successful
linking is followed by independent factory-default, unwaived DRC and final
resource audits. Image and routed-checkpoint hashes bind the resulting evidence.

`--reuse-xo-from PREVIOUS_BUILD` avoids repackaging only after checking the
frozen source manifest and every packaged HDL member against the current
sources. Keep running builds' frozen inputs unchanged.

The automatic-placement attempt `platform-hw-bram128-nosf-v3` failed at the
SLR0–SLR1 cut: 25,478 crossing nets exceeded 23,040 available SLLs. The
opt-in `--floorplan pipeline-slrs` setting uses the existing platform pblocks
to put input twist, frontend, state and inverse transform in SLR0; all four
forward FFT stages and their gather buffer in SLR1; and MAC plus key cache
and the existing alignment bank in SLR2. It preserves
RTL arithmetic and latency. It also saves pre-placement and post-placement
(including failed-placement) checkpoints for diagnosis. Original placement
errors still propagate, and all resource, timing and DRC gates remain required.

The first coarse floorplan also failed, requiring 34,439 SLLs at SLR0–SLR1.
Adding the existing 8,197 spectrum-alignment registers to SLR2 did not help:
the second partition required 34,440 SLLs at the same boundary. A checkpoint
property audit confirmed the three pblocks were already hard and the major
groups had their requested assignments. Keeping all FFT stages together in
SLR0 reduced demand to 25,984 crossings but still exceeded the 23,040 limit.
The current candidate moves that FFT group to the middle SLR1 and puts
twist/frontend/state/inverse in SLR0, keeping the forward FFT adjacent to the
MAC. In `platform-hw-bram128-pipeline-slrs-v4`, actual floorplanning reports
21,522 / 23,040 SLLs at SLR0–SLR1 and has advanced into partition-driven
placement. Connectivity is still high (93.4%); this does not prove complete
placement or routing. Resource fit alone does not prove
legal crossings or timing closure.

The V4 partition subsequently completed legal placement but failed estimated
kernel setup timing (-20.826 ns). The worst paths traverse reset and nested
ready/start handshakes repeatedly across SLRs. The next DMA RTL registers
cache and core launch credits, binds them to the output bank, and consumes
them on start; its placement hook pins those three bits and the sequencer
beside their control groups. Arithmetic is unchanged. The full encrypted
586-step two-key regression passes with a measured 84,480-cycle launch period.
The frozen launch-credit v2 build passed its complete post-optimization
resource gate (618,864 LUTs, 774,396 FFs, 6,578 DSPs, 570 BRAM equivalents)
and legal placement. Physical optimization estimated setup WNS -5.645 ns;
routing subsequently failed with `Route 35-5` at 85.35% vertical-wire
utilization and at least 1,882 CLBs with high pin use. The earlier V4 route failed
with 94% vertical-wire utilization and at least 3,216 CLBs with high pin use.

The current RTL additionally removes the redundant internal rejected-start
check where the raw wrapper already gates child start with readiness, and
requests distributed RAM for the four MAC accumulator slots. Public start
checking and stream-error detection remain. An isolated lane synthesis probe
measured 320 fewer FFs and 13 more total LUTs; this is not a complete-kernel
resource result. The combined full encrypted regression passed 61,500 words
across two keys, including stalls, reset and restart, with unchanged source
snapshots. Its XO packaging and full-platform post-opt resource gate passed.
Relative to launch-credit v2 post-opt, the new candidate uses 3,825 more LUTs
and 122,893 fewer FFs. Partition-driven placement completed in
`platform-hw-bram128-lutram-accum-v1`; the SLR0–SLR1 crossing check requires
21,530 of 23,040 SLLs. Arithmetic and launch period are unchanged. This candidate subsequently failed routing with very high congestion
(Route 35-3303 and global congestion level 7).

That distributed-RAM candidate completed legal placement, but the completed
5 ns kernel-clock report has setup WNS -17.155 ns and hold WHS -0.303 ns.
MAC-area congestion remains high. Its worst setup path returns from MAC fault
through launch readiness and the sequencer to exponent-bank activation.
The current RTL adds a registered pending compute request in the sequencer,
holding the selected bank until launch and preventing duplicate requests.
Existing raw readiness/fault checks remain. Full encrypted regression passed
61,500 words across two keys with stalls/reset/restart and unchanged snapshots;
the measured launch period is 84,481 cycles. XO packaging passed, and
`platform-hw-bram128-registered-request-v1` is running full-platform
implementation. Its post-opt resource gate passed, but completed placed timing
still fails at WNS -10.039 ns and WHS -0.344 ns. Routing subsequently
failed with global congestion level 7 (Route 35-3); the build closed with
link exit code 1 and unchanged snapshot inputs.

The latest RTL registers accepted launch and its bank at the keyed compute
boundary. Pending launch suppresses credit replenishment and contributes to
busy. Idle readiness uses the incoming bank; a pending launch retains the
captured bank. The corrected full encrypted regression passed 61,500 words
with stalls, reset and rekey, at a compute-start spacing of 84,482 cycles.
XO packaging passed. Full-platform implementation is running in
`platform-hw-bram128-accepted-launch-bank-v1`; the floorplan requires exactly
two accepted-launch registers in SLR0; this optimized-netlist check passed.
Placement and routed timing remain pending.

The accepted-launch candidate completed placement, but its completed report
still fails at setup WNS -7.965 ns and hold WHS -0.308 ns. All 50 worst
kernel setup paths run from MAC fault to input DataMover skid-buffer enables.
The latest DMA configuration disables same-cycle input-decoder refill
(`REFILL_ON_POP=0`), cutting downstream readiness from DMA input-ready.
The existing beat buffer refills on the following cycle. Full encrypted
regression passed 61,500 words with stalls/reset/rekey and unchanged frozen
inputs. Steady batch spacing remains 84,482 cycles; first launch takes about
473 additional cycles. XO packaging passed, and
`platform-hw-bram128-input-ready-cut-v1` passed full-platform post-opt
resources at 622,641 total LUTs, 651,505 FFs, 6,578 DSPs and 570 BRAM
equivalents. Placement completed but fails setup WNS -9.137 ns and hold
WHS -0.326 ns. Its 50 worst setup paths originate at MAC fault and reach
preparation/decoder controls; the worst destination is lookup-memory BRAM
write enable. Physical optimization continues. The preceding accepted-launch
build failed routing with global congestion level 7.

The current DMA RTL enables `REGISTER_PREP_FAULT=1`, registering the global
fault observation used by automatic preparation start beside preparation in
SLR0. Public fault remains immediate. Fault-free handshakes and preparation
latency are unchanged; after a fatal fault, one final internal preparation
start may occur before the local stop takes effect. The option defaults off
for other RawBlindRotate callers. Full encrypted regression passed 61,500
words including stalls/reset/rekey, with unchanged 84,482-cycle steady batch
spacing and first outputs at 14,229/14,230 cycles. XO packaging passed.
`platform-hw-bram128-prep-fault-cut-v1` is running full-platform implementation;
its resource, timing, routing and board qualification gates remain pending.

## Correctness and board qualification

The full 586-step DMA RTL regression checks exact outputs under stalls,
reset/restart, status draining, and two ordinary encrypted keys loaded across
successive jobs without a global reset. The selected arithmetic also matches
the primary model in the isolated 36-geometry training sweep. Training
survivors alone do not qualify alternative arithmetic formats.

The expanded board fixture generator creates 11 distinct batches per key,
alternating Boolean and nonconstant lookup functions. Across 32 keys this is
4,224 distinct encrypted inputs. Each fixture is checked against the fixed
model and an independent floating reference. Long board runs cycle these
fixtures to measure sustained throughput; repeated inputs are counted
separately from distinct correctness coverage.

After the hardware build and expanded fixture set both pass:

```sh
python3 tools/run_paper_streaming_board_qualification.py \
  --kernel-build build/paper-streaming-contract/platform-hw-new \
  --fixture-set build/paper-streaming-contract/board-distinct-fixtures-seeds81-112-v1 \
  --output build/paper-streaming-contract/board-qualification-new \
  --frames 4096 --device 0 --expected-bdf 0000:0a:00.1
```

This command programs the selected board only after checking the completed
build gates, image/checkpoint hashes, and fixture bindings. Keys remain in HBM
during each run. It records exact output comparisons, actual clocks, kernel
throughput, launch throughput, and distinct input counts. The performance
target is 28.4 PBS/ms. Simulated compute-start spacing is 84,482 cycles;
this does not establish routed or board throughput. Finite passing samples
do not establish a rare-error probability.

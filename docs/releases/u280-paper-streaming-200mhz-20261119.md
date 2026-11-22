# U280 paper Set I streaming kernel, 200 MHz, board exact-output test — 2026-11-19

This experimental release is the first image of the paper Set I streaming
kernel (`FptPaperStreamingKernel`, `vitis/paper_streaming`) that closes timing
at 200 MHz on the Alveo U280 and produces exact outputs on the physical card.
It is blind rotation plus sample extraction for n=586, N=512, k=2, two
base-256 decomposition levels, batches of 12, with bootstrapping keys resident
in HBM. Identity key switching is not included.

## Build

- Target: U280, `xilinx_u280_gen3x16_xdma_1_202211_1`, Vitis/Vivado 2023.2.
- Build `platform-hw-physical-v22`: `tools/build_paper_streaming_kernel.py
  --target hw --floorplan physical-slrs --place-directive AltSpreadLogic_high
  --route-directive AggressiveExplore`, validated by the 586-step encrypted
  DMA regression `encrypted-dma-engine586-physical-rtl-v23`.
- Kernel clock 200 MHz (5 ns). Fully routed, no routing errors or unrouted
  nets. Post-route setup WNS +0.016 ns, hold WHS 0.000 ns.
- The router itself ended at WNS -0.051 ns; the post-route gate
  (`check_fpt_post_route.tcl`) removes the placement margin on the HBM clock
  and runs up to two incremental `route_design` repair passes, which gave the
  figures above. The margin is thin and should not be read as robust closure
  across seeds or tool revisions.
- Post-route DRC: 0 fatal, 0 errors, 0 critical warnings; 6,918 warnings,
  5 advisories. The independent unwaived-DRC and routed-resource audits passed.
- Routed kernel: 536,895 LUTs (502,855 logic, 33,354 LUTRAM, 686 SRL),
  1,000,121 FFs, 5,810 DSPs, 566 RAMB36 + 643 RAMB18 (887.5 BRAM equivalents),
  no URAM. The BRAM-equivalent cap in the resource gate is 896 for this build
  (paper-derived value 606; the ROM forward twist and the per-point state
  memories account for the difference).
- xclbin sha256 `1efa3feafa3a330240c9dd4407577dbf357fc5129d957a9f7f2dddec732f3034`.

## Physical design

Three-SLR floorplan (`place_physical_slrs.tcl`): SLR0 frontend, state store,
inverse transform and sequencer; SLR1 ROM twist, forward stages and the near
MAC group; SLR2 MAC and key cache. Registered lane pipelines on both sides of
every SLR boundary, per-block reset fan-out and a registered fault tree
(`PaperStreamingPhysical.sv`).

Two RTL changes were needed after the first timing-clean image (v16) gave
wrong outputs on the card:

- Vivado 2023.2 mis-inferred the state store's block-RAM template (36-bit word
  with 18-bit partial writes sharing a port with the feedback read): memories
  were split and merged and only one of 128 lanes kept its contents. The store
  now uses one plain 17-bit dual-port memory per point
  (`PaperStreamingStateRam17`); the synthesized store was simulated against
  its RTL with no mismatch.
- With the store correct, the full 512x17 rotation network no longer routed.
  Each frontend issue uses only 256 rotated points, so the network now builds
  half of every stage (`PaperStreamingRotate17 #(.LOW_PAIRS(1))`), rotating by
  `exponent-2` for the second half. Function and latency are unchanged.

## Board result

`tools/run_paper_streaming_board_qualification.py`, fixture set
`board-distinct-fixtures-seeds81-112-v1`, 4,096 frames per key, run
`board-qualification-v22-run1`:

- 32 keys, 1,572,864 bootstraps, 4,224 distinct encrypted inputs.
- 1,612,185,600 output words compared exactly; all correct.
- Kernel cycle counter: 11,077,748,505 cycles, 28.397 PBS/ms at 200 MHz.
- Wall-clock over the launches: 55.40 s, 28.389 PBS/ms.

Both throughput figures are just below the script's 28.4 PBS/ms threshold
(about 84,516 cycles per 12-bootstrap frame against 84,507 allowed); the
register stages added at the SLR crossings account for the difference. The
script therefore reports the throughput targets as not met, and it leaves
`hardware_qualified` and `complete_kernel_qualified` false: this run is the
exact-output test, not a broader qualification.

History and intermediate builds are in `docs/paper-streaming-status.md`.

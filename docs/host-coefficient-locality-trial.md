# Complete host payload crossings and local coefficient queues

This opt-in combined trial addresses two different issues observed in
`build/host-link-laguna-edge`: host-HBM setup failures (WNS -0.112 ns, 10
endpoints), and the SLR0 long-wire congestion hotspot dominated by coefficients
(64%) and IFFT (25%). Kernel timing already passed at +0.035 ns.

`FPT_U280_LOCAL_COEFFICIENT_QUEUES=1` replaces only accumulator update and
drain-response queues with 512-bit storage tiles. The default remains off.
Depth, count, flow-through, full replacement, output ordering, and cycle timing
match Chisel Queue(pipe=true, flow=true). Each tile has preserved local pointers,
occupancy and control logic; data storage has no reset or forced RAM style.
This trades a small amount of control state for lower control fanout, not a
guaranteed reduction in total LUT/FF count. Five queue-equivalence cases cover
widths 1, 512, 513, 7685, and 4097 at depths 2 and 8.

The host placement flow inventories every surviving payload transmitter and
receiver in the five AXI channels on both seams. Missing or unsupported live
crossings fail closed. The original 18 allocations remain fixed; additional
bits can use all six compatible lanes of otherwise unused, physically verified
Laguna pairs. Separate one-edge pblocks preserve SLR ownership and the outer
DFX boundary. A v2 manifest records its expected count instead of assuming 18.
Fresh implementation names/connectivity, placed locations, and complete setup/
hold path coverage are checked; handshake/reset circuits and vendor RTL are
unchanged.

Artifacts and the gated runner are in `build/host-coefficient-locality`.
The queue miter and integrated nonzero regressions must preserve digest
`b02dc10d50e4111bd5e4b79993b355942fd58b2d6d758d40bc544901f38f372e`,
computeDone=474, wide stalled completion=5833, ready completion=4923, and
CMUX latency=239/II=16. Hardware emulation precedes fresh synthesis.
Synthesis and implementation check that all 25 queue tiles retain independent
control registers without driving another tile's payload controls.

Resource gates remain 530000 LUTs, 758967 FFs, 5624 DSPs, 468 BRAM tiles,
and 236 URAMs. Full routed setup/hold, route, and DRC acceptance are unchanged.
Timing congestion <=6 is a secondary goal, not a substitute for timing closure.
One implementation attempt, 30-minute status logging, no automatic retry or
hardware programming, and no automatic commit/push/release.

### Emitter recovery (2026-09-17)

The first run stopped during RTL emission, before hardware emulation, because
the legacy distributed-RAM annotation required a `ram_8x` module that tiled
queues no longer generate. All three coefficient/kernel emitters now apply
that annotation only to legacy queues. The strict memory-module lookup remains
unchanged; tiled memories retain automatic RAM inference.

Emission also exposed Chisel instance names `updateQueue_updateQueue` and
`drainResponses_drainResponses`. The queue structural gate accepts those exact
parents as well as the original names, with anchored tile matching; it still
requires all 25 tiles, preserved local state, bounded fanout, and no cross-tile
state loads. A naming regression test rejects unrelated/nested lookalikes.

Resume with `launch-monitored.sh --resume-hw-emu` after archiving the previous
driver files and freezing the reviewed emitter/runner changes. This mode checks
the completed functional logs and original allocation checksum, skips obsolete
process-ID waits, and resumes at hardware emulation. It does not regenerate the
allocation or relax resource, timing, DRC, or structural requirements.

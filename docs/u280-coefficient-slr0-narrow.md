# Coefficient SLR0 / lossless digit-link trial

Retained baseline: `c56d48e`. Immediate comparison: the failed
`build/slr-coefficient-locality` trial (377 unrouted signals, 343 node overlaps).
Work in `build/worktrees/u280-compressed-coefficients`, not the root worktree.

## Switch and invariant

`FPT_U280_COEFFICIENT_SLR0_NARROW_LINK=1` selects
`BatchedCmuxEngineConfig.coefficientSlr0NarrowLink` (default false). It requires
registered forward/inverse interfaces, serialized inverse components and
`PrecomputedWindowedBufferedSingleBanks`. Set
`FPT_U280_PAIRED_INVERSE_SLR_OUTPUT=0`; the old paired return is incompatible.
For hardware, use `FPT_HARD_FLOORPLAN_MODE=coefficient_slr0_narrow`.

All external interfaces and arithmetic remain unchanged. The forward input
is an integer gadget digit in signed fixed-point form: `signExtend(digit) << 12`.
The new link extracts bits 21:12 and reconstructs the exact signed 30-bit word.
A simulation assertion rejects any input whose discarded bits are not redundant.
There is no quantization, serialization, CDC, or faster clock.

## Physical ownership

- SLR0: coefficient memory/scratch/window/digit memory, IFFT,
  local inverse-output register, component join and inverse tags.
  Loader/scheduler/exponent memory/sample extraction are local as well.
- SLR1: existing External Product, inverse-input queue and FFT back partition,
  pending requests and forward tags; two distinct middle digit banks.
- SLR2: existing FFT front partition and destination digit bank.

The four digit banks are `coefficientDigitLink/{sourceTx,middleRx,middleTx,destinationRx}`.
Each has 2,560 unreset, unconditionally clocked payload FFs and two reset control
FFs. The original elastic `digitOutputBoundary` is unchanged. Explicit paired
payload registers are preserved for the two crossings; no middle FF has both
D and Q crossing an SLR. The local 3,840-bit inverse bank does not use Laguna.

The forward transport changes from one to four stages; the inverse return from
four to one. This removes 8,960 payload FFs overall and replaces 3,840 inverse
payload signals per seam with 2,560 digit signals. Actual total SLL use and
packing must be measured; auxiliary control paths also affect placement.

Current coefficient-locality controls/field stage, memory inference, SGen
commutator implementation, 128/64 lanes, 16 contexts and all precisions remain
as in the preceding trial. The regenerated SGen forward/inverse files are
byte-identical to that trial. Do not move the BK buffer by assigning a coarse
parent hierarchy to SLR0.

## Validation and reproduction

Experiment configuration and drivers: `build/coefficient-slr0-narrow/`.
The first full functional run passed 27 tests with exactly the preceding
schedule: CMUX 238 cycles, II 16, complete batch 20,851 cycles,
computeDone 473, firstResult 495, blockedOutputCycles 3,641.
The unchanged error histograms are:

- CMUX: `[896,837,261,43,11]`.
- Batch: `[(0,6539),(1,6619),(2,2168),(3,753),(4,321)]`.

`CoefficientDigitLinkSpec` exercises all 1,024 signed digits over all 256 lane
positions, consecutive frames, gaps, and reset with data in flight.
Standalone Vivado synthesis confirms 10,240 payload FFs, eight control FFs,
zero LUT/RAM/DSP, and all three direct one-to-one register-bank connections.
This is not a substitute for checking the full optimized and placed kernel.
`PaperBufferedBlindRotateNumericalSpec` additionally reports a SHA-256 of every
output word (fixed-width little-endian encoding); the reference and candidate
must match, with the digest frozen through `FPT_EXPECTED_BLIND_ROTATE_SHA256`.
Both configurations produced
`b02dc10d50e4111bd5e4b79993b355942fd58b2d6d758d40bc544901f38f372e`
for all 16,400 words of the existing nonzero batch benchmark. Their command
acceptance, completion, computeDone, first-result and final-result cycles match.
Use `build/singularity/fpt-verilator-5.050.sif` for Verilator regressions.
Generation runs on the host with Java 21 so SGen's worktree git provenance resolves.

```sh
source build/coefficient-slr0-narrow/configuration.sh
export FPT_TEST_SINGLE_ACCUMULATOR=1
tools/run_singularity.sh bash -lc 'cd chisel && sbt -J-Xmx12G "testOnly fpt.CoefficientDigitLinkSpec fpt.PairedInverseRelaySpec fpt.AccumulatorMemorySpec fpt.PrecomputedWindowedCoefficientStoreSpec fpt.PaperU280BatchScheduleSpec fpt.PaperBufferedBlindRotateNumericalSpec fpt.PaperCmuxNumericalSpec fpt.BankedShuffleEquivalenceSpec"'
tclsh tests/registered_link_names_test.tcl
tclsh tests/coefficient_slr0_floorplan_test.tcl
```

Before launch, require the exact reference/candidate digest and latency gates,
legacy regressions and unchanged SGen tests. The driver freezes source hashes,
requires a 16,400-word hw_emu pass, then performs one 200 MHz kernel synthesis
and one implementation. Structural checks run on the synthesized kernel and
placed design; final diagnostics are retained before strict timing rejection.
Whole-SLR utilization is diagnostic, not an arbitrary baseline-relative gate.
The monitor records status every 1,800 seconds and stops when the driver exits.

Full acceptance requires legal routing and clean setup/hold at 200 MHz.
A partial result is retainable only with legal routing, clean hold, kernel
WNS >= -1.704 ns, TNS > -16822.687 ns, fewer than 42593 failing endpoints,
II 16 and batch <=22951 cycles. Do not label a partial result 200 MHz capable.
No automatic retries or commits; commit only after a qualifying result is reviewed.

## Placement-checker recovery

The first implementation stopped in the post-placement ownership check on
`sampleExtract/maskMemory_ext/Memory_reg_0_15_0_13`; routing never started.
Vivado 2023.2 returns an empty direct cell-to-SLR query for a placed RAM32M16
macro. A small placed fixture reproduced this with a valid SLICE site in SLR2;
the same macro's 16 internal RAM cells resolve correctly.

`check_fpt_registered_links.tcl` now resolves each cell through its physical
sites to one SLR, failing on missing/unresolved sites, multiple SLRs, or wrong
ownership. RAM macros and internal RAM cells remain covered. Only VCC/GND
sources, which have no physical origin, are excluded from crossing detection.
Run `tclsh tests/registered_link_placement_test.tcl` for positive/negative
fixtures; `tests/registered_link_placement_vivado_test.tcl` validates an actual
placed RAM32M16, its internal RAM cells, registers, and wrong-SLR rejection.

The explicitly authorized, single recovery driver is
`build/coefficient-slr0-narrow-recovery/launch-monitored.sh`. Its archive retains
the original attempt, Vitis logs/reports, and failed `impl_1` directory. It
verifies the original frozen source manifest except the checker and
checkpoint-saving post-place hook, verifies the validated RTL/XO, and freezes
existing synthesized DCP hashes plus a separate recovery source manifest.
RTL, floorplan, 200 MHz target, and implementation options are unchanged.
No placed DCP survived the original hook failure, so recovery repeats placement
from synthesis before attempting routing for the first time. The post-place
hook now saves `placed.dcp` before diagnostics. Recovery status/logs are separate;
new placement/route reports remain in the original attempt directory, with old
reports preserved in the archive. Monitoring records status every 1,800 seconds
and stops on driver completion; there is no automatic retry or commit.

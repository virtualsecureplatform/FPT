# Four-lane sample extraction, n=630

Baseline: released `u280-200mhz-20260916`, 200 MHz, CMUX II=16, 16 contexts.
No cryptographic parameter, arithmetic, transform, key interface, or compute
pipeline change. Enable the candidate with `FPT_U280_OUTPUT_LANES=4`; default
is 1, retaining the scalar implementation.

The output extractor keeps one polynomial store, selects four descending
coefficients from each aligned group, and pipelines selection/negation. It
emits a(0) as a one-word beat, then groups of four; b(0) replaces the unused
-a(0) in the final group. A three-beat compactor removes partial-beat padding
across context boundaries, presenting 128-bit beats to the output DataMover.
The host layout remains exactly 16*1025 32-bit words / 65600 bytes. Key BTT,
input layout, HBM ports and the 630 iterations remain unchanged.

The compactor has registered-occupancy ready and retains valid/count/last on
stall. Completion is now based on the final packed output handshake, not the
earlier final acceptance from the core. Otherwise a prolonged output stall
could cause the existing bounded completion-drain timer to expire too soon.
The scalar completion path is unchanged.

Both Scala and Tcl validate lane count (1 or 4). Packaging generates and
includes FptOutputConfig.vh, so the shell's width and the DataMover's stream
width are controlled by the same build setting. FPT_VITIS_BUILD_DIR allows
candidate artifacts to be kept separate from the release's generated RTL/XO.

Verification includes small/U280-shape ordering, reset during queued output,
repeated frames, random stalls, arbitrary compactor tails, poisoned invalid
lanes, and a sequencer test that stalls the final packed output for over 300
cycles. The nonzero numerical regression flattens each valid beat back to
32-bit words and retains the exact baseline SHA256/oracle checks. Scalar and
wide cases are compared with ready held high and with patterned backpressure.
A test-only scalar-lane port adapter avoids the ChiselTest 6 / Verilator 5.050
VlWide harness incompatibility; production port widths are unaffected.

Expected ceiling: reducing 16400 scalar result transfers to 4100 packed beats
saves at most about 61.5 us per batch before extra context/pipeline overhead.
The ideal n=630 compute ceiling remains 19.84 results/ms, not the paper's
25 results/ms for n=500. No routed timing, resource, hw_emu or board result is
claimed until those respective stages complete. Preserve the release artifact
and do not relax 200 MHz or route/hold/structural gates.

## Initial measurements

Six focused unit tests pass, including continuous full-rate compaction after a
partial beat. The original scalar stalled fixture still completes in 20852
cycles with its released checksum. With output always ready, the scalar
fixture completes in 17211 cycles and the wide fixture in 4923: 12288 cycles
saved, equivalent to 61.44 us at 200 MHz. Both computeDone markers are cycle
474. Both output digests equal the released numerical digest. These fixtures
use domainDimension=1; the unchanged output count means the drain saving is
also relevant to the n=630 kernel, but is not a full hardware latency result.

The patterned-backpressure wide fixture also passes: 5833 cycles versus the
scalar 20852, with unchanged computeDone=474 and the same output digest.
The cycle-based stall pattern acts on beats, so the always-ready comparison
is the cleaner measure of the architectural drain saving. All four numerical
variants and the six final unit tests passed before launching the gated run.

Focused OOC synthesis of extraction plus compaction: scalar 1899 LUT / 110 FF,
wide 2914 LUT / 494 FF. Delta +1015 LUT / +384 FF, no BRAM/DSP increase. This
is not an integrated resource or routability result.

The candidate uses build/wide-output, isolated from all release artifacts.
The gated runner checks numerical equivalence/cycle improvement, unchanged
CMUX/key ingress, hw_emu, then one fresh full implementation with the existing
local-enable protection hooks. Caps remain 528916 LUT / 758967 FF with
5624 DSP / 468 BRAM / 236 URAM, and clean setup/hold at 200 MHz is mandatory.
Sources are frozen before launch. No automatic implementation retry, gate
relaxation, hardware programming, commit, or push is performed by the runner.

## Authorized implementation resume (2026-09-16)

The initial run passed hw_emu, synthesis, and structural checks, then stopped
at 15:46 JST because 529218 LUT exceeded the 528916 trial cap by 302.
Integrated usage is 758602 FF, 5624 DSP, 468 BRAM, and 236 URAM.
The user authorized raising only the LUT cap to 530000 and proceeding to P&R.
The separate resume-implementation.sh checks the original frozen-source and
validated-RTL hashes, reuses the synthesized checkpoint, audits hooks, and
launches implementation once. Original scripts, manifest, and failed-run logs
remain unchanged. Resume inputs are separately hashed. All other resource,
structural, 200 MHz timing, hold, and route gates remain unchanged. The resume
monitor records status every 30 minutes; no hardware programming is automatic.

# Isolated MSB-first coefficient-window experiment

2026-09-15. This detached worktree is based on 7ee86a2 with the active
u280-compressed-coefficients Chisel source snapshot overlaid. The inherited
uncommitted changes are not part of this experiment. No active-trial source was
edited, no full implementation was launched, and no changes were committed.

## Change

PipelinedWindowedNegacyclicRotatorSpan accepts msbFirst=false by default.
The experimental true setting orders alignment layers 32,16,8,4,2,1 rather
than 1,2,4,8,16,32 for 64-lane banks. Each layer retains outputLanes plus
the sum of unconsumed shifts. Pipeline cuts remain after every two layers;
latency remains four enabled cycles. The production caller is unchanged.

The test reference is copied from the active trial's original class, not
implemented by selecting the new default branch.

## Validation

Verilator 5.050 / ChiselTest with existing PaperVerilator compatibility flags.
Four configurations: (N,width,block,output) = (16,1,2,2), (64,12,8,16),
(1024,24,64,64), (1024,24,64,128). All pass against original RTL and a
software oracle. Every offset/ring-block combination is exercised, with
random stalls, zero/all-one/sign-bit seeds, and random seeds. Input lane
values are generated as seed + laneIndex*65537 modulo the coefficient width.
This is simulation evidence, not exhaustive verification of all data values.
4256 output beats checked across the four cases, with four-cycle latency.

## Isolated synthesis

Vivado 2023.2, xcu280-fsvh2892-2L-e, out-of-context, rebuilt hierarchy,
two threads, scheduling nice=10. Identical wrapper, always-enabled pipeline,
and firtool options for both variants.

| Resource | Original | MSB-first | Reduction |
|---|---:|---:|---:|
| LUT | 14965 | 12792 | 2173 (14.52%) |
| FF | 15162 | 12986 | 2176 (14.35%) |
| CARRY8 | 384 | 384 | 0 |
| DSP / BRAM | 0 / 0 | 0 / 0 | 0 |

Both post-synthesis timing estimates give WNS +4.318 ns at a 5 ns clock,
but these are unplaced, idealized OOC estimates with unconstrained I/O.
They do not establish routed timing or congestion improvement.
The isolated baseline LUT count differs from the integrated kernel window
(13405 LUT); savings must not be treated as measured full-kernel savings.

Logs/RTL/DCPs: build/window-shift-order/validated-tests.log, synth.log,
baseline/ and candidate/. Initial harness compilation errors are retained in
tests.log/tests-final.log; validated-tests.log is the final passing run.
active-source-check.log verifies that active trial frozen source hashes match.

## Reproduce

Set FPT_SINGULARITY_IMAGE to the repository's fpt-verilator-5.050.sif.
Use MAKEFLAGS=-j2 and SBT_OPTS=-XX:ActiveProcessorCount=2 with
tools/run_singularity.sh, then run in chisel:

    sbt -J-Xmx6G "testOnly fpt.WindowShiftOrderSpec" "Test/runMain fpt.EmitWindowShiftOrder ../build/window-shift-order"

Vivado script: build/window-shift-order/synth.tcl, argument:
build/window-shift-order. Existing DCP output paths should be preserved or
new output directories chosen for another synthesis run.

Next step: integrate as an opt-in configuration in a subsequent trial after
the current full P&R finishes, then check full-kernel resources and routing.

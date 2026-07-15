# Fixed-point FTT hardware comparison status

The paper-shaped implementation uses `N=1024`, four decomposition rows, a
512-point/128-lane forward tangent transform, and two parallel
512-point/64-lane inverse tangent transforms. The twist and untwist constants
are now generated inside SGen instead of arriving through runtime Chisel
ports. The single-command regression observes `done` 202 cycles after command
acceptance, or 203 cycles when the launch cycle is included.

The synthesis-oriented variant uses replicated synchronous memory banks and
two prefetch buffers. It has a 212-cycle latency, so fourteen contexts are
needed for continuous reuse, but its acceptance and completion interval
remains 16 cycles. The paper-size regression issues fourteen distinct
contexts and then wraps immediately to context zero.

Run the schedule check after generating the paper-size SGen sources:

```sh
cd chisel
FPT_PAPER_SCHEDULE=1 \
FPT_SGEN_FORWARD=../build/sgen-fpt/forward.v \
FPT_SGEN_INVERSE=../build/sgen-fpt/inverse.v \
sbt 'testOnly fpt.PaperCmuxScheduleSpec'

FPT_PAPER_BATCH=1 \
FPT_SGEN_FORWARD=../build/sgen-fpt/forward.v \
FPT_SGEN_INVERSE=../build/sgen-fpt/inverse.v \
sbt 'testOnly fpt.PaperBatchedScheduleSpec'

FPT_PAPER_BANKED_BATCH=1 \
FPT_SGEN_FORWARD=../build/sgen-fpt/forward.v \
FPT_SGEN_INVERSE=../build/sgen-fpt/inverse.v \
sbt 'testOnly fpt.PaperBankedBatchScheduleSpec'
```

## Locally measurable transform tradeoff

The following counts are source-level real multiply expressions in SGen's
generated RTL.  They are useful before technology mapping, but they are not
DSP48 counts: Vivado may decompose one wide expression into multiple DSPs or
implement a constant multiply in LUTs.

| Transform | Stock SGen | FPT cyclic | FPT tangent | Latency stock/cyclic/tangent | Interval |
| --- | ---: | ---: | ---: | ---: | ---: |
| 512-point, 128-lane forward | 1006 | 808 | 1192 | 59 / 64 / 70 | 4 |
| 512-point, 64-lane inverse | 506 | 411 | 603 | 80 / 102 / 107 | 8 |

The cyclic adapted design uses the three-real-multiply Gauss form and changes
twiddle literals from 30 to 26 bits. The tangent columns add exactly three
real multipliers per lane: 384 in the forward and 192 in the inverse. Those
same multipliers previously sat outside SGen in Chisel, so moving them is not
itself an arithmetic-count reduction. The benefit is faithful specialization:
the constants are generated in ROMs, their pipelines are included in SGen's
schedule, and they are no longer runtime top-level inputs. Frame throughput
remains unchanged. CMUX launches all four forward decomposition rows at the
four-cycle interval and runs both inverse components concurrently, producing
the 203-cycle launch-inclusive schedule.

The physical batch top combines a tagged, double-buffered External Product
accumulator with fourteen memory-backed coefficient contexts. A lane word
packs both polynomial halves and both TRLWE components. Sixty-four banks are
duplicated, giving forward prefetch and delayed inverse read-modify-write one
read port each while writes are mirrored. For Set II, CIRCT preserves this as
128 instances of a `112 x 128` synchronous array. The emitted Chisel top is
4.09 MB with 8,545 scalar register declarations; complete-design Verilator
lint processes 15.92 MB across 19 modules. These are structural source counts,
not placed resource counts.

The two prefetch buffers still feed a combinational ten-stage, 32-bit
negacyclic barrel rotator. A standalone `BitwiseNegacyclicReorder` now
reproduces the paper's coefficient-to-bitwise idea: 64 coefficient lanes load
a transposed bank structure, a 2-bit-wide logarithmic barrel emits all 1024
positions over 16 cycles, and serial carries implement exact wrapped
two's-complement negation. `BitwiseCmuxDecompositionFrontend` continues the
same stream through exact biased subtraction and both centered gadget levels.
The reorder alone emits as 3.02 MB; the combined Set-II frontend emits as
5.01 MB and passes complete lint across 4.79 MB of sources.
`BitwiseCmuxForwardFrontend` also runs both components and streams all four
rows in the FFT's 128-lane folded order. Two digit buffers overlap its 16
bitwise cycles with 16 coefficient-wise output cycles, giving a tested
initiation interval of 16 cycles when the downstream remains ready. It emits
as 7.51 MB before writeback logic. Adding lane-local inverse updates and the
accumulator drain path makes the stateful frontend 10.22 MB; it passes lint
across 9.75 MB of sources in eight modules. `CmuxEngine` now selects this path
with `bitwiseBitsPerCycle = Some(2)`: the small generated-SGen CMUX remains
within `2^18` Torus units of the C++ result and takes 166 cycles versus 149 for
the immediate barrel frontend. At Set II, the integrated bitwise CMUX emits as
11.57 MB and complete-design lint processes 37.34 MB across 19 modules; its
module list contains `BitwiseNegacyclicReorder` and no
`NegacyclicBarrelRotator`. The comparable barrel CMUX is 3.03 MB and lints
across 12.91 MB in 14 modules. These text/elaboration sizes are not FPGA area
results. The bitwise schedule is 219 cycles after acceptance (220
launch-inclusive), exactly 17 cycles beyond the barrel schedule as in the
end-to-end small regression. Complete RTL lint passes; the optional paper-size
Verilator executable was not completed because its monolithic C++ compilation
reached 46.5 GB RSS.

The batched bitwise frontend keeps the replicated accumulator memories and
alternates two transposed working sets: one streams buffered digits while the
other rotates the next command, and the first can prefetch its following
context after decomposition releases the coefficient banks. A complete small
model checks exact digits, updates, drains, and a no-bubble row interval. The
Set-II top uses fifteen contexts (predicted latency 229 cycles at II=16), emits
as 11.09 MB, and lints with the generated transforms across 35.97 MB in 25
modules. CIRCT emits 128 instances of a `120 x 128` synchronous array, and the
module list contains no `NegacyclicBarrelRotator`. Placement is still required
to determine whether the extra transposed working set costs less LUT/route
pressure than the removed 1024-way 32-bit barrel.

At the mux-network boundary, the source-level reduction is concrete. The
shared barrel has `1024 * log2(1024) * 32 = 327,680` mux-bit stages. The
single bitwise engine has two component networks at two bits, or 40,960 stages
(8x fewer). Duplicating the complete working set for sustained batching gives
81,920 stages (4x fewer than the shared 32-bit barrel). This does not predict
LUT packing or routing, but it is the mechanism the U280 comparison is meant
to measure. Reproduce the emitted hierarchy and counts with:

```sh
tools/report_bitwise_structure.sh
```

Regenerate the cyclic comparison and table from local SGen outputs with:

```sh
INTEGRATED_TANGENT=0 tools/generate_sgen_fpt.sh ../SGen build/sgen-cyclic
tools/report_sgen_structure.sh
```

## What remains before claiming a real U280 benefit

The paper's Table 3 gives these Set II implementation targets. They are
reference numbers, not measurements from this repository:

| Block | LUT | FF | DSP | BRAM |
| --- | ---: | ---: | ---: | ---: |
| Full FPT | 595k | 1,024k | 5,980 | 412 |
| CMUX | 458k | 827k | 5,980 | 215 |
| 256-lane MAC | 66k | 79k | 1,536 | 215 |
| FFT 512/128 | 222k | 449k | 2,958 | 0 |
| IFFT 512/64 | 130k | 255k | 1,486 | 0 |

The exact U280 result must come from Vivado because generic synthesis does not
model DSP48E2 packing for the signed asymmetric fixed-point products.  Run
both generated transforms, the single-command CMUX, and `BatchedCmuxEngine`
through the scripts in
`chisel/scripts/`, at 5.0 ns for the paper point and 3.425 ns for a direct
HOGE-frequency comparison.  Record DSP, LUT, FF, BRAM/URAM, achieved timing,
and power from the routed reports.

HOGE's checked-in tree reports about 1.55 ms per gate at 292 MHz, but contains
no utilization report to normalize against.  Its transform structure uses
32-lane, 64-bit modular NTT/INTT datapaths with 32 `INTorusMUL` instances per
transform; each `INTorusMUL` contains a 64-by-64 multiply and modular
reduction.  Comparing that source-level count directly with FPT's narrower
real products would be misleading, so the common U280 post-route flow is the
measurement boundary.

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
two's-complement negation. Its paper-scale Chisel output is 3.02 MB and passes
complete lint. Integrating its bit stream with centered gadget decomposition
is the next step; until then, the memory-backed top remains the conservative
full-width rotation baseline.

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

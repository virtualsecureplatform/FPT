# Fixed-point FTT hardware comparison status

The paper-shaped implementation now reproduces both FPT Set II schedule
numbers in real generated RTL. Its Chisel CMUX uses `N=1024`, four
decomposition rows, a 512-point/128-lane forward transform, and two parallel
512-point/64-lane inverse transforms. The single-command regression observes
`done` 191 cycles after command acceptance, or 192 cycles when the launch
cycle is included. The twelve-context batched regression accepts and completes
a different context every 16 cycles, in order, with the same 192-cycle
launch-inclusive latency.

The synthesis-oriented variant replaces those twelve register contexts with
replicated synchronous memory banks and two prefetch buffers. It has a
201-cycle latency, so thirteen contexts are needed for continuous reuse, but
its acceptance and completion interval remains 16 cycles. The regression
issues thirteen distinct contexts and then wraps immediately to context zero.

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

| Transform | Stock SGen multiplies | FPT-adapted | Reduction | Stock/FPT latency | Launch interval |
| --- | ---: | ---: | ---: | ---: | ---: |
| 512-point, 128-lane forward | 1006 | 808 | 19.7% | 59 / 64 | 4 / 4 |
| 512-point, 64-lane inverse | 506 | 411 | 18.8% | 80 / 102 | 8 / 8 |

The adapted design uses the three-real-multiply Gauss form and changes
twiddle literals from 30 to 26 bits.  Pipeline depth increases, especially in
the inverse with its per-stage scaling, but frame throughput is unchanged.
CMUX exploits that distinction: all four forward decomposition rows enter at
the four-cycle launch interval, and both inverse components run concurrently.
The result is the launch-inclusive 192-cycle schedule even though the forward
and inverse pipelines themselves are 64 and 102 cycles deep.

The physical batch top combines a tagged, double-buffered External Product
accumulator with thirteen memory-backed coefficient contexts. A lane word
packs both polynomial halves and both TRLWE components. Sixty-four banks are
duplicated, giving forward prefetch and delayed inverse read-modify-write one
read port each while writes are mirrored. For Set II, CIRCT preserves this as
128 instances of a `104 x 128` synchronous array. The emitted Chisel top falls
from 9.5 MB and 28,745 scalar register declarations to 4.2 MB and 8,545,
respectively. These are structural source counts, not placed resource counts.

The two prefetch buffers still feed a combinational ten-stage negacyclic
barrel rotator. Reproducing the paper's bitwise stream reorder remains the
next timing/area optimization; the memory-backed top establishes a much more
credible synthesis baseline without claiming that unpublished organization.

Regenerate the table from local SGen outputs with:

```sh
tools/report_sgen_structure.sh \
  build/sgen/dft512_l128.v build/sgen/dft512_l128_fpt.v \
  build/sgen/idft512_l64.v build/sgen-fpt/inverse.v
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

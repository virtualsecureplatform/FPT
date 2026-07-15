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

The bitwise-prefetched variant uses fifteen contexts and two transposed
2-bit working sets. Its executable Set-II regression measures 229-cycle
latency and the same 16-cycle acceptance and completion interval, then drains
every context to verify exact preservation under a zero external product.

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

FPT_PAPER_BITWISE_SCHEDULE=1 \
FPT_SGEN_FORWARD=../build/sgen-fpt/forward.v \
FPT_SGEN_INVERSE=../build/sgen-fpt/inverse.v \
sbt -J-Xmx12G 'testOnly fpt.PaperCmuxScheduleSpec'

FPT_PAPER_BITWISE_BANKED_BATCH=1 \
FPT_SGEN_FORWARD=../build/sgen-fpt/forward.v \
FPT_SGEN_INVERSE=../build/sgen-fpt/inverse.v \
sbt -J-Xmx16G 'testOnly fpt.PaperBankedBatchScheduleSpec'
```

The paper tests default `FPT_VERILATOR_SPLIT` to 20,000 for both generated
model and C-function splitting; override it to tune another Verilator host.
`FPT_VERILATOR_COMPILER` selects Verilator's C++ compiler. The local Verilator
5.027 development build also needed its broken parallel precompiled-header
path disabled while retaining four-way compilation:

```sh
export MAKEFLAGS='-e -j4'
export VK_PCH_I_FAST=
export VK_PCH_I_SLOW=
```

Measured executable schedules on this host are:

| Top | Accept cycles | Done cycles | Latency / II | Identity coverage |
| --- | --- | --- | --- | --- |
| Bitwise single CMUX | `0` | `219` | `219 / -` | 2,048 nonzero Torus words |
| 14-context barrel batch | `0,16,...,224` | `212,228,...,436` | `212 / 16` | 28,672 nonzero Torus words |
| 15-context bitwise batch | `0,16,...,240` | `229,245,...,469` | `229 / 16` | 30,720 nonzero Torus words |

The single bitwise run took 2:15 and 8.37 GiB peak RSS. The barrel batch took
0:41 and 3.39 GiB; the bitwise batch took 1:58 and 14.42 GiB. These are
simulation host costs, not FPGA costs.

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
initiation interval of 16 cycles when the downstream remains ready. Each
output lane uses an explicit balanced selector over its sixteen legal
row/beat coefficients; it does not infer a variable 1024-entry array index.
It emits as 7.51 MB before writeback logic. Adding lane-local inverse updates
and the accumulator drain path makes the stateful frontend 10.22 MB; it passes
lint across 9.75 MB of sources in eight modules. `CmuxEngine` now selects this
path with `bitwiseBitsPerCycle = Some(2)`: the small generated-SGen CMUX remains
within `2^18` Torus units of the C++ result and takes 166 cycles versus 149 for
the immediate barrel frontend. At Set II, the integrated bitwise CMUX emits as
11.57 MB and complete-design lint processes 37.34 MB across 19 modules; its
module list contains `BitwiseNegacyclicReorder` and no
`NegacyclicBarrelRotator`. The comparable barrel CMUX is 3.03 MB and lints
across 12.91 MB in 14 modules. These text/elaboration sizes are not FPGA area
results. The bitwise schedule is 219 cycles after acceptance (220
launch-inclusive), exactly 17 cycles beyond the barrel schedule as in the
end-to-end small regression. Complete RTL lint and the split paper-size
Verilator executable both pass. The executable also exactly preserves a
nonzero accumulator when the bootstrapping key is zero.

The batched bitwise frontend keeps the replicated accumulator memories and
alternates two transposed working sets: one streams buffered digits while the
other rotates the next command, and the first can prefetch its following
context after decomposition releases the coefficient banks. A complete small
model checks exact digits, updates, drains, and a no-bubble row interval. The
Set-II top uses fifteen contexts and measures 229-cycle latency at II=16. It
emits as 11.09 MB and lints with the generated transforms across 35.97 MB in
25 modules. CIRCT emits 128 instances of a `120 x 128` synchronous array, and
the module list contains no `NegacyclicBarrelRotator`. Placement is still
required to determine whether the extra transposed working set costs less
LUT/route pressure than the removed 1024-way 32-bit barrel.

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

## Local UltraScale+ coefficient-frontend mapping

Stock Yosys cannot parse CIRCT's block-local variables, so the synthesis-only
emitters use `firtool --lowering-options=disallowLocalVariables`. The normal
Vivado/Verilator emitters do not use that lowering option. With Yosys
0.45+139, the single and batched Set-II coefficient frontends were flattened
and mapped with:

```text
synth_xilinx -family xcup -flatten -noiopad -noclkbuf -widemux 5
```

This boundary includes coefficient storage, negacyclic rotation, centered
gadget decomposition, and the four folded forward-transform rows. It excludes
SGen, External Product, and inverse transforms. The result is a technology-
mapped estimate, not placement or routing:

| Metric | Shared 32-bit barrel | Two-component 2-bit path | Change |
| --- | ---: | ---: | ---: |
| Estimated logic cells | 532,572 | 282,811 | -46.9% |
| LUT1--LUT6 primitives | 685,356 | 287,620 | -58.0% |
| Flip-flops | 65,566 | 194,653 | +196.9% |
| CARRY4 | 18,682 | 4,362 | -76.7% |
| MUXF7/8/9 | 364,305 | 84,013 | -76.9% |
| Total mapped cells | 1,133,912 | 574,766 | -49.3% |

The local result reproduces the expected benefit: replacing the full-word
barrel roughly halves the mapped logic estimate and removes most dedicated
wide-mux cells. The cost is about three times as many flip-flops for the
transposed bitwise state and ping-pong digit buffers, plus the already tested
17-cycle pipeline fill. Yosys needed 9:06 and 12.5 GiB peak RSS for the barrel
map, versus 15:19 and 21.3 GiB for the bitwise map; those host costs do not
represent FPGA area.

The memory-backed comparison is the more relevant sustained-throughput point.
The barrel design has fourteen contexts and a 212-cycle latency; the bitwise
design has fifteen contexts, two complete working cores, and a 229-cycle
latency. Both accept a command every 16 cycles:

| Metric | 14-context barrel | 15-context bitwise | Change |
| --- | ---: | ---: | ---: |
| Estimated logic cells | 532,471 | 319,358 | -40.0% |
| LUT1--LUT6 primitives | 626,032 | 327,943 | -47.6% |
| Flip-flops | 140,165 | 398,406 | +184.2% |
| CARRY4 | 18,685 | 8,478 | -54.6% |
| MUXF7/8/9 | 211,378 | 118,910 | -43.7% |
| RAMB36E2 | 256 | 256 | 0% |
| Distributed RAM | 0 | 0 | 0% |
| Total mapped cells | 996,658 | 862,370 | -13.5% |

Thus the bitwise path still removes about 40% of the estimated logic-cell
pressure after duplicating the working core to preserve II=16. Both context
stores map to the same 256 RAMB36E2 primitives; the extra bitwise context fits
within the same primitive-depth granularity, and neither design uses a
distributed-RAM primitive. The FF cost remains close to 3x. Yosys needed
10:40 and 10.12 GiB peak RSS for barrel-batched, versus 21:59 and 40.79 GiB
for bitwise-batched. These synthesis host costs are not FPGA costs.

Re-run either single frontend, or request the larger batched coefficient
stores explicitly, with:

```sh
tools/synthesize_coefficient_frontends.sh
tools/synthesize_coefficient_frontends.sh barrel-batched bitwise-batched
```

The script emits Yosys-compatible SystemVerilog, retains `synth.log`,
`stat.json`, and timing data under `build/yosys-coeff/`, and prints a compact
TSV report. Vivado post-route remains the measurement boundary for achieved
frequency, routing pressure, and the complete CMUX including SGen/DSP48E2
packing.

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
model DSP48E2 packing for the signed asymmetric fixed-point products. The
first apples-to-apples route is automated as:

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
tools/run_u280_comparison.sh ../SGen build/vivado-u280-comparison
```

It regenerates the transforms and both batched tops, then routes the
14-context barrel and 15-context/two-core bitwise designs at the same II=16,
part, periods, and implementation settings. It records source hashes and
writes routed resource/timing/power summaries plus pairwise differences.
Vivado is not installed on this host, but the complete preparation-only path
has been exercised. See `docs/u280-handoff.md` for the route-machine
prerequisites and result definitions. Standalone forward and inverse routes
remain useful for isolating FTT DSP packing; the complete batched result is
the throughput-matched coefficient-path comparison.

HOGE's checked-in tree reports about 1.55 ms per gate at 292 MHz, but contains
no utilization report to normalize against.  Its transform structure uses
32-lane, 64-bit modular NTT/INTT datapaths with 32 `INTorusMUL` instances per
transform; each `INTorusMUL` contains a 64-by-64 multiply and modular
reduction.  Comparing that source-level count directly with FPT's narrower
real products would be misleading, so the common U280 post-route flow is the
measurement boundary.

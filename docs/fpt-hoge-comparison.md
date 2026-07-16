# FPT FTT versus HOGE NTT U280 comparison

This flow prepares and routes source-faithful arithmetic baselines from this
FPT checkout and the sibling HOGE checkout. It is the direct hardware test of
whether the narrower fixed-point tangent FTT buys useful throughput/resource
efficiency over HOGE's 64-bit modular NTT.

## Compared boundaries

| Role | FPT FTT | HOGE NTT |
| --- | --- | --- |
| Forward transform | 512 complex tangent points, 128 lanes, frame II 4 | 1024 modular coefficients, 32 lanes, frame II 32 |
| Inverse transform | 512 complex tangent points, 64 lanes, frame II 8 | 1024 modular coefficients, 32 lanes, frame II 32 |
| Blind Rotate | `n=630`, 16 contexts, base-10 level 2, one shared inverse FTT, two-coefficient key cache, index-zero sample extraction, 11,915.4 measured wrapper cycles/result | `n=636`, 2 contexts, base-6 level 3, index-zero sample extraction, 158,318.5 measured wrapper cycles/result |

Both transform frames represent one 1024-coefficient negacyclic polynomial.
The FPT transforms come from the tracked SGen `fpt` branch. The HOGE wrappers
are compiled in a temporary copy of the current `chisel/HomGate` project; the
HOGE checkout is not patched. Structural guards require 31 modular
multipliers in HOGE's forward INTT, 32 in its inverse NTT, and 127 in its
Blind Rotate path.

The baseline Blind Rotate tops exclude IKS, Vitis DataMover IP, and full
on-chip bootstrapping-key storage. HOGE's eight 512-bit HBM-style streams feed
two internal double-bank `TRGSWBatchMemory` caches. The default FPT top loads
its key through an 864-bit port into a two-coefficient ping-pong cache; the
former 13,824-bit direct-key top remains an opt-in diagnostic design. Both
retain index-zero sample extraction: HOGE returns two TLWEs and FPT returns
sixteen TLWEs. FPT's Chisel wrapper drains each completed TRLWE into
synchronous mask memory, emits
`a(0), -a(N-1), ..., -a(1), b(0)`, and marks only the last coefficient of the
full batch. Complete-top resource totals must still be reported with batch
size and throughput. The transform-only pairs remain the cleaner measurement
of the FTT-versus-NTT arithmetic representation.

The parameters are intentionally not identical at this stage, as requested.
They are recorded in `manifest.tsv` so a later parameter-alignment experiment
does not overwrite or get confused with this baseline.

## Local UltraScale+ transform mapping

Before routing, the four exact handoff transform sources can be flattened and
mapped through the same Yosys UltraScale+ pass:

```sh
tools/synthesize_fpt_hoge_transforms.sh
```

This is an opt-in, signature-cached flow. The 128-lane FPT forward map used
19.3 GiB peak RSS on the development host. Select individual designs by name
when memory or time is limited, for example:

```sh
tools/synthesize_fpt_hoge_transforms.sh fpt-inverse hoge-inverse
```

Yosys 0.45+139 maps the current source-only handoff as follows. These are
technology-mapped estimates without placement, routing, or clock timing:

| Kernel | Frame II | Estimated logic cells | LUT1--6 | FF | DSP48E2 | Distributed RAM | SRL |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FPT forward FTT | 4 | 128,279 | 256,527 | 303,060 | 2,384 | 1,536 | 59,624 |
| HOGE forward INTT | 32 | 63,702 | 115,931 | 75,407 | 496 | 0 | 27,026 |
| FPT inverse FTT | 8 | 97,976 | 195,919 | 268,839 | 1,486 | 1,216 | 44,820 |
| HOGE inverse NTT | 32 | 60,215 | 115,143 | 72,973 | 512 | 0 | 26,507 |

At an equal clock, throughput per resource relative to HOGE is:

| Role | Frame-rate ratio | Logic-cell efficiency | LUT efficiency | FF efficiency | DSP efficiency |
| --- | ---: | ---: | ---: | ---: | ---: |
| Forward | 8.0x | 3.973x | 3.615x | 1.991x | 1.664x |
| Inverse | 4.0x | 2.458x | 2.351x | 1.086x | 1.378x |

This reproduces the fixed-point transform's intended pre-route resource
advantage against the HOGE baseline: both throughput per logic and throughput
per mapped DSP are greater than one in each direction. The local SGen change
splits every exact 30-by-26-bit signed product into two signed products that
fit DSP48E2's 27-by-18-bit multiplier. Yosys therefore maps one DSP per
generated expression: `1,192 * 2 = 2,384` forward and
`743 * 2 = 1,486` inverse. The synthesis script rejects a result unless this
one-to-one contract holds. HOGE's modular datapaths still map to exactly
sixteen DSPs per top-level 64-bit multiplier: `31 * 16 = 496` forward and
`32 * 16 = 512` inverse.

The split is bit-exact rather than a precision shortcut. Its SGen test covers
all signed boundary combinations plus 10,000 deterministic random products;
all 3,485 SGen tests and the paper-size FPT transform/CMUX numerical
regressions pass without changing their results. The paper reported 2,958 and
1,486 DSPs for its forward and inverse blocks. Matching the inverse count is
encouraging, but it is not evidence of an identical microarchitecture: this
implementation uses a different public streaming schedule and still lacks
the paper's private per-stage width/scaling details. Vivado routing remains
necessary to determine achieved frequency, congestion, routed resources, and
whether the wider FPT design sustains its throughput advantage on the U280.

The script retains `stat.json`, the full Yosys log, console warnings, host
timing, and source/tool/flow signatures under
`build/yosys-fpt-hoge-transforms/`. Its `summary.tsv` contains raw counts and
`comparison.tsv` contains the throughput-normalized ratios above.

## Prepare without Vivado

```sh
FPT_VIVADO_PREPARE_ONLY=1 \
tools/run_u280_fpt_hoge_comparison.sh ../HOGE ../SGen \
  build/vivado-u280-fpt-hoge-prepared
```

This regenerates and lints all seven RTL inputs, records all three Git commits
and worktree states, checks HOGE's multiplier structure, and hashes every
synthesis source and Tcl flow. When Verilator is available it also measures
the direct-key FPT, physical buffered FPT, and HOGE complete Blind Rotate
schedules with zero data.

The physical FPT top loads 10,096 raw TLWE coefficients and 630 spectral-key
coefficients, consumes 161,280 narrow key beats, and returns 16,400
sample-extracted TLWE beats. The full batch takes 190,646 cycles (11,915.4
cycles/result): 10,400 input cycles, 163,397 cycles from run launch through
the end of CMUX computation, and a 16,849-cycle drain tail. The direct-key
diagnostic source takes 190,645 cycles (11,915.3 cycles/result). Sample
extraction of earlier contexts overlaps the computation. HOGE's final `TLAST`
occurs after 316,637 cycles (158,318.5 cycles/result). Each of its eight BK
streams consumes exactly 122,112 beats, with a maximum inter-port skew of
eight beats.
At an equal clock, these wrapper schedules imply 13.287 times the result rate
for physical FPT, before accounting for routed frequency or resources.

The first FPT Verilator build is large. All three schedule models use
content-based source, harness, flow, and tool signatures, so identical RTL is
reused even when it is regenerated under a different handoff directory. Set
`FPT_SCHEDULE_BUILD_DIR`, `FPT_BUFFERED_SCHEDULE_BUILD_DIR`, or
`HOGE_SCHEDULE_BUILD_DIR` to keep the caches outside that directory. Set
`FPT_SKIP_FPT_SCHEDULE=1` or `FPT_SKIP_HOGE_SCHEDULE=1` to omit the
corresponding measurement; its throughput fields are then recorded as
`unmeasured`.

When Yosys is installed, preparation also independently elaborates the exact
paper-sized Chisel top together with both generated SGen transforms. This
check rejects missing hierarchy, unexpected synthesis warnings, CIRCT
block-local declarations, or changes to the 128 replicated `128 x 128`
accumulator memories, the `10080 x 11` exponent memory, and the `16 x 2048`
sample-extraction memory. It then maps the real single-clock memory contexts
to 256 `RAMB36E2`, 9 `RAMB18E2`, and 150 `RAM32M16` primitives,
respectively. The exponent-memory context black-boxes the unrelated CMUX
datapath; the full generic hierarchy check still includes both real SGen
transforms. The first run is signature-cached. Set `FPT_YOSYS_BOUNDARY_DIR`
to place its cache elsewhere, or `FPT_SKIP_YOSYS_BOUNDARY=1` to skip it
explicitly. The manifest labels generic totals as hierarchy-wide and records
the three technology-mapped memory counts separately. These are independent
synthesis checks; only the Vivado runs provide placed and routed U280
resource and timing results.

## Physical bootstrapping-key boundaries

The direct-key FPT wrapper above is useful for measuring the arithmetic
schedule, but its 13,824-bit spectral-key input is not a realistic accelerator
boundary. The buffered wrapper adds a Chisel two-coefficient ping-pong cache.
It accepts 16 complex Q8.19 values, or 864 bits, per load beat and presents the
External Product's 256 complex operands through a synchronous 13,824-bit
internal read. While one coefficient serves all 16 Blind Rotate contexts, the
other bank loads the next coefficient. The first two coefficients load in
parallel with accumulator initialization.

An apples-to-apples Verilator run with the current split-DSP SGen sources gives:

| FPT key boundary | Key data bits | Top-level port bits | Batch cycles | Cycles/result | Compute cycles | Key-transaction gap |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Direct spectral key | 13,824 | 31,869 | 190,645 | 11,915.3 | 163,396 | 16--19 |
| Two-bank buffered load | 864 | 18,956 | 190,646 | 11,915.4 | 163,397 | 16--19 |
| Physical buffered accelerator | 864 | 1,012 | 190,646 | 11,915.4 | 163,397 | internal 16--19 |

Thus the external key datapath is 16 times narrower and the buffered
verification top has 40.5% fewer port bits. The physical Chisel wrapper also
removes the unused legacy twist/untwist ports--the selected SGen `fptdft` and
`fptidft` cores already integrate those operations--plus internal key and
transform diagnostics. This leaves 60 ports and 1,012 port bits: 94.7% fewer
bits than the buffered verification top and 96.8% fewer than the direct-key
top. The functional input, key-load, control, and result streams remain.

HOGE already has an equivalent throughput-shaped physical boundary; adding
another wrapper would duplicate its reference architecture's storage. The
generated top has eight 512-bit key streams and 4,663 total port bits. Its two
cache instances each contain two 192-deep banks with 2,048-bit words, for
1,572,864 logical cache bits. The exact per-batch interfaces are therefore:

| Physical Blind Rotate top | Key data bits/cycle | Total port bits | Internal throughput-cache bits | Key bits accepted/batch |
| --- | ---: | ---: | ---: | ---: |
| FPT buffered accelerator | 864 | 1,012 | 442,368 | 139,345,920 |
| HOGE baseline | 4,096 (`8 x 512`) | 4,663 | 1,572,864 | 500,170,752 |

These bandwidth and capacity differences are part of the current
architecture and parameter choices, not evidence about FTT arithmetic by
themselves. The transform-only routes remain the controlled representation
comparison. `tools/check_fpt_buffered_blind_rotate_boundary.sh` and
`tools/check_hoge_blind_rotate_boundary.sh` reject any change to either port or
cache contract. The HOGE schedule test also deasserts each AXI key stream after
exactly 122,112 accepted beats instead of counting idle `TREADY` cycles after
the transfer.

The synchronous cache costs two startup cycles per 16-result batch, or
0.001%, and introduces no transaction gap beyond the current SGen core's
existing three-cycle frame restart. Input initialization remains 10,400 cycles
and the overlapped sample-extraction tail remains 16,849 cycles. The
verification-boundary test streams all 630 key coefficients through 161,280
narrow load beats, checks all 10,080 wide key transactions in
dimension/context order, and checks all 16,400 output beats. A second full
Verilator run through the 1,012-bit physical boundary reproduces the same
190,646-cycle batch and all load/output counts. At an equal clock, the
buffered schedule still has 13.287 times HOGE's measured result rate.

That zero-data schedule is complemented by a nonzero arithmetic test of the
same 16-context, 864-bit physical boundary. With the domain dimension reduced
to one, it loads one dense spectral key coefficient through the real cache,
runs one complete CMUX per context with the generated FTTs, and verifies all
16,400 sample-extracted words against the fixed radix-2 C++ oracle under
output backpressure. The zero-through-four Q27.3-unit error histogram is
`[5551,6427,3166,1041,215]`; 11,978 words are within one unit and none exceed
four. The oracle changes 14,341 extracted words, so the test cannot pass via a
zero-key identity. Reproduce it with:

```sh
tools/test_paper_buffered_blind_rotate_numerics.sh \
  ../SGen build/paper-buffered-blind-rotate-numerics
```

The two banks store 442,368 logical bits (54 KiB). A standalone UltraScale+
Yosys map of the exact emitted cache is:

| Estimated logic cells | LUT1--6 | FF | RAMB36E2 | Distributed RAM | DSP48E2 | CARRY | MUXF |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 14,012 | 14,065 | 965 | 192 | 0 | 0 | 4 | 156 |

The BRAM count is governed by the 13,824-bit one-cycle read width rather than
logical bit capacity: 192 parallel 72-bit slices implement each depth-32
memory word. This is a throughput-shaped cache, not full bootstrapping-key
storage. The RTL is Chisel; the emitter only attaches a `ram_style = "block"`
attribute to the generated memory declaration because the current CIRCT path
does not preserve the legacy Chisel/FIRRTL memory attribute.

Reproduce the buffered source, schedule, and standalone cache map with:

```sh
tools/generate_sgen_fpt.sh ../SGen build/sgen-fpt
tools/emit_paper_buffered_bitwise_batched_blind_rotate_sample_extract.sh
tools/measure_fpt_buffered_blind_rotate_schedule.sh
tools/emit_paper_buffered_blind_rotate_accelerator.sh
FPT_VERILATOR_JOBS=8 \
  tools/measure_fpt_buffered_blind_rotate_accelerator_schedule.sh
tools/synthesize_fpt_bootstrapping_key_buffer.sh
tools/synthesize_fpt_buffered_blind_rotate_accelerator.sh
```

The full generated hierarchy, including both real SGen transforms, passes
Yosys hierarchy and memory-structure checks. The flat physical wrapper also
passes the same UltraScale+ map and its exact 457-BRAM/5,408-DSP contract:

| FPT wrapper | Cycles/result | Estimated logic cells | LUT1--6 | FF | BRAM | Distributed RAM | DSP48E2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Direct external key | 11,915.3 | 600,388 | 1,012,828 | 1,340,497 | 265 | 5,100 | 5,408 |
| Physical two-bank cache | 11,915.4 | 620,387 | 1,013,285 | 1,348,958 | 457 | 5,100 | 5,408 |

The complete physical map adds exactly 192 BRAMs and no distributed RAM or
DSPs. Whole-design packing adds only 457 LUTs and 8,461 FFs, rather than the
standalone cache's raw totals; it also removes 1,285 MUXF cells and adds four
carry cells. Yosys's packing heuristic raises estimated logic cells by 19,999
(3.3%), so the primitive counts are the clearer delta. This again shows why
standalone resource estimates must not simply be added to the complete map.

Against the unchanged HOGE map at an equal clock, the physical FPT result-rate
ratio is 13.287x and its logic-cell, LUT, FF, and DSP throughput efficiencies
are 3.361x, 3.693x, 7.641x, and 4.992x respectively. These remain pre-route
estimates with intentionally different FHE parameters and key boundaries.
The physical map took 6,178.76 seconds and 32,879,380 KiB peak RSS, 0.6% less
time and 6.1% less memory than the direct wrapper run. Placement, routing, and
clock measurement remain work for the Vivado machine; both complete-wrapper
routes now have audited throughput caches and physical streaming boundaries.

## Local UltraScale+ complete-wrapper mapping

After preparing the source-only handoff, map both complete Blind Rotate
wrappers through the same open-source UltraScale+ flow with:

```sh
tools/synthesize_fpt_hoge_blind_rotate.sh
```

The script verifies the prepared-source hashes before synthesis and uses an
opt-in content-signature cache. It flattens the raw-TLWE-through-sample-
extraction wrappers with `synth_xilinx -family xcup`, disables SRL inference,
and leaves wide-mux lowering disabled for both designs. An exploratory FPT
run with `-widemux 5` expanded its large multiplexers into `$shiftx` cells
and exceeded 53 GiB before technology mapping, so that pass is not a useful
common local baseline.

At checkpoint `66c8dc1`, Yosys 0.45+139 produced the following complete maps.
These remain technology-mapped estimates: there is no placement, routing,
clock result, or power estimate.

| Wrapper | Cycles/result | Estimated logic cells | LUT1--6 | FF | BRAM | Distributed RAM | DSP48E2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FPT, 15 contexts | 12,217.1 | 768,816 | 1,341,749 | 1,880,600 | 265 | 4,120 | 14,574 |
| HOGE, 2 contexts | 158,318.5 | 156,913 | 281,598 | 775,713 | 202 | 3,341 | 2,032 |

At an equal clock, the measured schedules and mapped resources give:

| Result-rate ratio | Logic-cell efficiency | LUT efficiency | FF efficiency | DSP efficiency |
| ---: | ---: | ---: | ---: | ---: |
| 12.959x | 2.645x | 2.720x | 5.345x | 1.807x |

Thus that FPT baseline produces more results per mapped resource than HOGE at
equal clock even before its main arithmetic inefficiencies are fixed. This is
encouraging but is not yet a hardware-speed result: congestion may reduce
FPT's achieved clock, and the 14,574-DSP implementation is far beyond the
paper's 5,980-DSP full-design target.

The baseline DSP contract also identifies why the total is high. The map is
`2,384` forward-transform DSPs, two inverse transforms at `1,486` DSPs each,
`9,216` External Product DSPs, and two wrapper-glue DSPs. CIRCT emitted each
nominal signed 30-by-27-bit External Product multiplication as a
57-by-57-bit unsigned modular multiplication over sign-extended operands.
After synthesis merges the two accumulator-buffer copies, each of the 1,024
remaining real multiplications occupies nine DSP48E2s. In contrast, an
explicit width-preserving schoolbook complex MAC would require two DSPs for
each of four real products per lane (`2,048` total), and a three-product Gauss
MAC would require `1,536`, matching the paper's MAC count.

The width-preserving Gauss MAC now implements that reduction. It keeps three
30-by-27-bit real products, splits each into two signed DSP48E2-sized
products, and uses explicit pre-add overflow corrections so its result is
bit-exact with the old four-product expression for every input bit pattern.
Its generated SystemVerilog is an inline Chisel BlackBox, the permitted
SGen-like exception for controlling signed multiplier widths. Reproduce its
standalone map with:

```sh
tools/synthesize_fpt_external_product.sh
```

The complete 256-lane double-buffered External Product maps to exactly 1,536
DSPs, down from 9,216. LUT1--6 also fall from 402,802 to 327,168, MUXF cells
from 233,119 to 89,560, and carry cells from 60,929 to 33,665; FFs remain
122,906. Yosys's packing heuristic raises its estimated logic-cell figure
from 216,504 to 253,102 despite the lower primitive counts, so the raw LUT
and mux counts are the safer local comparison. That result used the original
register-array accumulator storage and remains the arithmetic baseline.

The paper emitter now selects a Chisel `SyncReadMem` implementation that packs
each accumulator depth into one 15,360-bit lane word. Two `4 x 15,360` arrays
replace the double register array while retaining the same 1,536-DSP Gauss
MAC and one-result-per-16-cycle schedule:

| External Product storage | Estimated logic cells | LUT1--6 | FF | BRAM | Distributed RAM | DSP48E2 | CARRY | MUXF |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Register array | 253,102 | 327,168 | 122,906 | 0 | 0 | 1,536 | 33,665 | 89,560 |
| Inferred synchronous memory | 222,726 | 300,406 | 15,400 | 0 | 2,196 | 1,536 | 29,569 | 106,925 |

The inferred-memory version reduces estimated logic cells by 12.0%, LUTs by
8.2%, FFs by 87.5%, and carry cells by 12.2%. Its cost is 2,196 shallow
distributed-RAM primitives and 19.4% more MUXF cells. An exploratory forced-
block-RAM map used 428 `RAMB36E2` and was worse than natural inference in
logic cells, LUTs, and FFs; depth four is too shallow to use block RAM well.
This is still a technology map without placement or timing.

The throughput-oriented wrapper serializes both External Product component
frames through one inverse FTT. Distinct-component/backpressure tests and
paper-size register, banked, and bitwise schedules confirm that the shared
inverse preserves II=16. The bitwise
inferred-memory schedule has 237-cycle latency and still requires 16 contexts;
its measured full wrapper takes 11,915.3 cycles/result. The complete-wrapper
DSP contract remains 5,408 (`2,384 + 1,486 + 1,536 + 2`).

At checkpoint `0cd4e7b`, the register-backed complete-wrapper flow first
confirmed that contract:

| Wrapper | Cycles/result | Estimated logic cells | LUT1--6 | FF | BRAM | Distributed RAM | DSP48E2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FPT, 16 contexts | 11,915.2 | 768,755 | 1,243,985 | 1,448,004 | 265 | 2,904 | 5,408 |
| HOGE, 2 contexts | 158,318.5 | 156,913 | 281,598 | 775,713 | 202 | 3,341 | 2,032 |

At an equal clock, those measured schedules and mapped resources gave:

| Result-rate ratio | Logic-cell efficiency | LUT efficiency | FF efficiency | DSP efficiency |
| ---: | ---: | ---: | ---: | ---: |
| 13.287x | 2.712x | 3.008x | 7.118x | 4.992x |

Checkpoint `346deff` adds the inferred External Product memories and produces
the current complete map:

| Wrapper | Cycles/result | Estimated logic cells | LUT1--6 | FF | BRAM | Distributed RAM | DSP48E2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FPT, 16 contexts | 11,915.3 | 600,388 | 1,012,828 | 1,340,497 | 265 | 5,100 | 5,408 |
| HOGE, 2 contexts | 158,318.5 | 156,913 | 281,598 | 775,713 | 202 | 3,341 | 2,032 |

At an equal clock, the current schedules and mapped resources give:

| Result-rate ratio | Logic-cell efficiency | LUT efficiency | FF efficiency | DSP efficiency |
| ---: | ---: | ---: | ---: | ---: |
| 13.287x | 3.473x | 3.694x | 7.689x | 4.992x |

Relative to the `0cd4e7b` register-backed map, inferred accumulator memory
reduces estimated logic cells by 21.9%, LUTs by 18.6%, FFs by 7.4%, carry
cells by 3.2%, and MUXF cells by 30.5%. BRAM and DSP counts are unchanged.
Distributed RAM rises by exactly 2,196 primitives, from 2,904 to 5,100,
matching the standalone map of the two new arrays. This whole-wrapper packing
result is better than merely subtracting the standalone storage delta.

Relative to `66c8dc1`, the current FPT map uses 62.9% fewer DSPs, 28.7% fewer
FFs, 24.5% fewer LUTs, and 21.9% fewer estimated logic cells. Distributed RAM
is 23.8% higher because the inferred External Product storage more than
offsets the 1,216 primitives removed with the second inverse core. BRAM
remains 265 even with the sixteenth context.

The complete flow retains `stat.json`, synthesis logs, host timing and peak
RSS, and source/tool/flow signatures under
`build/yosys-fpt-hoge-blind-rotate-memory/`. The current FPT map took 6,218.61
seconds and 35,010,708 KiB peak RSS on the development host: 0.3% less time
and 11.6% less peak memory than the `0cd4e7b` map. The unchanged HOGE map took
1,919.05 seconds and 16,443,268 KiB. `summary.tsv` records the raw counts and
`comparison.tsv` records the throughput-normalized ratios.

## Route on the Vivado machine

For the lowest-drift route, package the already validated prepared sources
and copy only that bundle to the Vivado machine:

```sh
tools/package_u280_fpt_hoge_handoff.sh \
  build/vivado-u280-fpt-hoge-prepared \
  build/u280-fpt-hoge-portable

FPT_PREPARED_VERIFY_ONLY=1 \
  build/u280-fpt-hoge-portable/tools/run_prepared_u280_fpt_hoge_comparison.sh \
  build/u280-fpt-hoge-portable
```

The copied runner verifies the clean Git provenance, all seven manifest source
hashes, all three route-flow hashes, and the complete bundle checksum before
Vivado starts. It does not require the SGen or HOGE checkouts, JDK, sbt,
Verilator, or Yosys. On the route machine, remove the verification-only
variable and select periods/jobs normally:

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
FPT_VIVADO_JOBS=8 \
  ./u280-fpt-hoge-portable/tools/run_prepared_u280_fpt_hoge_comparison.sh \
  ./u280-fpt-hoge-portable
```

The regeneration-capable route remains available when all sibling checkouts
and generator dependencies are intentionally present:

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
FPT_VIVADO_JOBS=8 \
tools/run_u280_fpt_hoge_comparison.sh ../HOGE ../SGen \
  build/vivado-u280-fpt-hoge-comparison
```

The default design list is:

```text
fpt-forward hoge-forward fpt-inverse hoge-inverse
fpt-buffered-blind-rotate hoge-blind-rotate
```

Set `FPT_HOGE_DESIGNS` to a space-separated subset for a shorter run. For
example, route only the arithmetic kernels first:

```sh
FPT_HOGE_DESIGNS='fpt-forward hoge-forward fpt-inverse hoge-inverse' \
tools/run_u280_fpt_hoge_comparison.sh ../HOGE ../SGen \
  build/vivado-u280-fpt-hoge-transforms
```

Select `fpt-blind-rotate` explicitly to route the historical direct-key top.
When HOGE metrics are present, `comparison.tsv` labels that pair
`blind-rotate`; the default physical pair is labeled `buffered-blind-rotate`.

Runs are sequential. `FPT_VIVADO_REUSE=1` reuses a completed result only when
its source, flow, top, part, clock, tool version, and job-count signature still
matches and its metrics satisfy the current route/DRC acceptance contract.

## Result interpretation

`summary.tsv` reports validated route/DRC status, routed timing, primitive
counts, power, frame II, frames/s, frames/s/LUT, and frames/s/DSP. A result is
accepted only when the requested clock is present, routing is complete, route
status has no error categories, and DRC has no Fatal, Error, Critical Warning,
or unclassified violation. `comparison.tsv` reports FPT minus HOGE for each
matched role and period. The normalized transform metrics are the primary
benefit indicators:

- frame rate shows the benefit of FPT's wider streaming transforms;
- frame rate per LUT measures logic efficiency;
- frame rate per DSP measures multiplier efficiency;
- routed WNS and power show whether the extra parallelism remains physically
  usable.

For Blind Rotate, both throughput values now cover wrapper input through the
final sample-extracted TLWE. The default FPT route includes the BRAM-backed
two-coefficient cache and its physical 864-bit load port. The comparison still
includes intentionally different parameter sets, batch sizes, and key-stream
organizations, so it is an architectural upper-level view rather than a
controlled arithmetic microbenchmark. The transform-only pairs remain the
controlled evidence for the fixed-point FTT datapath itself. Exact Set II is
also not decrypt-reliable under TFHEpp's default decomposition; the
deterministic format results and synthesis interpretation are recorded in
[`u280-handoff.md`](u280-handoff.md#numerical-scope-before-synthesis). Vivado
vectorless power is an estimate, not board power.

# FPT reference implementation

This directory contains a C++ numerical reference for the fixed-point
negacyclic FFT used by FPT, the TFHE accelerator described in IACR ePrint
2022/1635.  It is intentionally separate from an optimized CPU FFT: its job is
to expose the quantization and overflow behavior that an RTL implementation
will have.

Implemented features:

- tangent-FFT folding from an `N`-coefficient real polynomial to an `N/2`
  complex FFT;
- the negacyclic twist and inverse twist;
- fixed-point butterflies with two's-complement wrap and truncating products,
  matching SGen's fixed-point operators;
- signed power-of-two inverse normalization and direct modular Torus output,
  without retaining hidden precision through a floating-point conversion;
- Q2 twiddle factors with four fewer total bits than the FFT datapath;
- the three formats reported in Table 2 of the paper;
- configurable per-stage scaling, because the exact FPT schedule is not part
  of the paper or its public demonstration artifact;
- a TFHEpp adapter implementing fixed-point External Product, CMUX, and Blind
  Rotate without changing TFHEpp's existing FFT backend.

The reference uses a radix-2 decimation-in-time FFT.  The paper's RTL uses
generated radix-2^4 streaming structures; this changes the order of rounding
events but not the transform.  Keeping this difference explicit is preferable
to claiming bit-exact compatibility with RTL that has not been published.

## Build the standalone tests

```sh
cmake -S . -B build
cmake --build build -j
ctest --test-dir build --output-on-failure
```

## Build the TFHEpp integration test

```sh
cmake -S . -B build-tfhepp -DFPT_BUILD_TFHEPP_TESTS=ON
cmake --build build-tfhepp -j --target \
  fpt_tfhepp_blind_rotate_test fpt_tfhepp_profile_sweep
ctest --test-dir build-tfhepp -R fpt_tfhepp --output-on-failure
./build-tfhepp/fpt_tfhepp_profile_sweep 16
```

The profile sweep is a deterministic, multi-minute diagnostic rather than a
default CTest. It reuses one secret key, spectral bootstrapping key, and input
sequence across every arithmetic profile.

## Chisel RTL

The synthesizable implementation is being developed in `chisel/`.  Chisel
owns the fixed-point arithmetic around the transform, External Product, CMUX
control, and top-level interfaces.  Generated SGen Verilog is instantiated as
a black box for the continuous-flow tangent FFT, including its fixed twist and
untwist ROMs.

Generate the deterministic C++ vectors, then build the Chisel design and run
its regressions:

```sh
cmake -S . -B build -DFPT_BUILD_RTL_TESTS=ON
cmake --build build -j
(cd chisel && sbt test)
```

The current Chisel regression covers the Gauss butterfly in scaled and
unscaled modes, the mixed-format complex External Product MAC, a configurable
multi-lane cyclic FFT, forward/inverse tangent wrappers, and the typed boundary
to a generated SGen continuous-flow FFT.  It also covers a six-row,
two-component External Product accumulator with asymmetric input/output lanes
and output backpressure, matching the paper's 128-lane forward versus 64-lane
inverse boundary.  The CMUX coefficient store covers negacyclic rotation,
centered gadget decomposition, fixed-point/Torus conversion, and in-place
add-back.  The native Chisel transforms, External Product arithmetic, and
coefficient-side CMUX operations are bit-exact with the C++ model.  The
16-point SGen fixture processes 16 frames back-to-back and stays within 6 raw
Q18.12 units of the C++ radix-2 oracle; its inputs are bounded to exclude
intermediate overflow because different radix factorizations need not agree
after fixed-width overflow.

`CmuxEngine` composes these blocks into an end-to-end correctness engine.  It
can select native Chisel or generated SGen transform backends and runs two
inverse transforms in parallel.  In the 32-coefficient, four-lane forward /
two-lane inverse regression, the native iterative backend is bit-exact with
the C++ CMUX model and takes 128 cycles.  The integrated-tangent SGen backend
overlaps the six forward decomposition rows at one frame every four cycles,
takes 149 cycles, and differs by at most one Q27.14-to-Torus quantum (`2^18`)
from the radix-2 C++ result. Selecting the 2-bit coefficient frontend removes
the full-width barrel network and produces the same bounded result in 166
cycles. The small case is dominated by pipeline fill; the paper-sized
throughput/resource comparison is generated separately.

`BatchedCmuxEngine` supports register, barrel-prefetched, and bitwise-prefetched
coefficient storage. Its throughput-oriented configurations serialize the two
External Product components through one inverse FTT. With the integrated
transforms, the register schedule is 211 cycles and needs 14 contexts for
sustained 16-cycle reuse. The emitted physical top instead uses replicated
synchronous memory banks and two polynomial prefetch buffers. Its eight-cycle
prefetch overlaps the prior 16-cycle decomposition stream, retaining the
16-cycle interval with a 220-cycle latency and 14 contexts. In the bitwise
variant, two transposed working sets replace those buffers and the barrel. The
Set-II schedule is 237 cycles with a 16-cycle completion interval; 16 contexts
sustain reuse at that rate. The double-buffered External Product PISO drains
the two component frames sequentially at the 64-lane inverse width while the
next transaction accumulates at the 128-lane forward width. `CmuxEngine`
remains the simpler two-inverse, single-command correctness top.

`SampleExtractIndexZero` converts the natural-order TRLWE drain to TFHEpp's
index-zero TLWE order using synchronous mask memory. It sustains one Torus
coefficient per cycle, including the reversed/negated mask, and preserves the
output across arbitrary backpressure. `BatchedBlindRotateSampleExtractEngine`
automatically drains every completed context through that stage, tags each
result coefficient with its context, and marks only the last TLWE of the full
batch. This aligns the FPT Blind Rotate output boundary with HOGE. A nonzero
end-to-end regression now drives the C++ Blind Rotate oracle through the
folded-bitwise replicated context store and this automatic extraction path
under output backpressure. It returns all 231 words of seven index-zero TLWEs
in order; context zero is exact and the maximum wrapped error is the existing
guarded-format bound of `2^19` Torus units.

`BootstrappingKeyPingPongBuffer` adds the paper-shaped two-coefficient key
cache. A narrow ordered stream loads 16 complex Q8.19 values per cycle while a
synchronous wide read supplies all 256 operands consumed by one External
Product beat. Loading coefficient `i + 1` overlaps all 16 contexts' use of
coefficient `i`, including chained load descriptors and same-cycle bank
retirement/reuse. `BufferedBlindRotateAccelerator` is the corresponding
host-facing top. Because the generated `fptdft` and `fptidft` cores integrate
their tangent twist and untwist, it removes those unused legacy ports and all
internal key/transform diagnostics. Only the raw-TLWE load, 864-bit key load,
run control, and sample-extracted result streams remain.

## RTL source policy

All handwritten synthesizable FPT RTL is Chisel under `chisel/src/main`.
Verilog is used for generated SGen transform BlackBoxes, including the three
checked-in small regression fixtures, and for one Chisel-inline generated
width-control BlackBox around the DSP-sized signed External Product
multipliers. Chisel-emitted SystemVerilog and paper-sized SGen output remain
generated build artifacts rather than a second handwritten implementation.
The key-cache emitter adds only a guarded block-RAM style attribute to the
generated memory declaration; it does not add handwritten RTL behavior.
`tools/check_rtl_source_policy.sh` enforces this boundary over tracked HDL
files and runs with the CTest reference suite.

The deterministic C++ vectors named `rtl_*.txt` are retained because the
Chisel arithmetic, transform, CMUX, and Blind Rotate regressions consume them;
they no longer feed a separate Verilog testbench.

## SGen streaming transforms

For the continuous-flow comparison path, the `fpt` branch of
`virtualsecureplatform/SGen` carries the Gauss complex multiplier, narrower
twiddle profile, and generator-level tangent twist/untwist operators.
`tools/generate_sgen_fpt.sh` generates configurable full-throughput tangent
FFT/IFFT Verilog with stable `FptSGenForward` and `FptSGenInverse` module
names. The defaults reproduce the paper's 512-point, 128-lane forward and
64-lane inverse interface shapes, using a validated radix-8 forward and
radix-2 inverse. Set `INTEGRATED_TANGENT=0` for the cyclic comparison cores.
See `sgen/README.md` for the numerical regression, radix controls, and the
remaining differences from the unpublished FPT generator extensions.

The opt-in full-size numerical check regenerates both cores, four nonzero
frames per direction, and a dense nonzero Set-II CMUX vector:

```sh
tools/test_paper_sgen_numerics.sh ../SGen build/paper-sgen-numerics
```

Set `FPT_PAPER_BITWISE_CMUX_NUMERICS=1` on the same command to validate the
full-size 2-bit folded coefficient frontend instead of the barrel frontend.
It completes in 224 cycles and produces the identical error histogram and
1,780 changed-output count, so the 17-cycle frontend tradeoff introduces no
measurable distribution change on this vector.

It currently bounds the forward core to 518 raw Q18.12 units and the inverse
core to 8 raw Q27.3 units versus a quantized double-precision oracle. The
complete 207-cycle CMUX changes 1,780 of 2,048 Torus coefficients. Against the
independent fixed radix-2 C++ model, 731 coefficients are exact and 1,495 are
within one Q27.3 raw unit; the cyclic error histogram for zero through four
raw units is `[731,764,386,138,29]`. One Q27.3 raw unit is `2^29` Torus units,
so this test records the coarse Set-II phase precision rather than hiding it
behind a permissive raw-Torus tolerance.

The companion physical-boundary regression retains the complete Set-II
datapath and 16-context batch but uses one Blind Rotate key coefficient. It
streams a dense nonzero key through the accelerator's 864-bit loader, applies
one generated-FTT CMUX to every independently initialized context, and checks
all 16,400 sample-extracted TLWE words under result backpressure:

```sh
tools/test_paper_buffered_blind_rotate_numerics.sh \
  ../SGen build/paper-buffered-blind-rotate-numerics
```

The deterministic C++ oracle changes 14,341 output words. The RTL has 5,551
bit-exact words, 11,978 within one Q27.3 unit, and the zero-through-four-unit
histogram `[5551,6427,3166,1041,215]`; no output exceeds the same four-unit
bound as the single-CMUX regression. This closes the nonzero composition path
through raw-TLWE loading, corrected modulus switching, the key cache, folded
coefficient frontend, integrated SGen transforms, and sample extraction. The
full `n=630` regression remains the separate zero-data throughput test because
one nonzero coefficient is sufficient to exercise the arithmetic datapath
without multiplying simulation time by 630.

The paper-shaped Chisel top uses Set II's `N=1024`, two components, two
decomposition levels, 128 forward lanes, and 64 inverse lanes.  Generate the
SGen sources and emit/lint the composed design with:

```sh
tools/generate_sgen_fpt.sh ../SGen build/sgen-fpt
tools/emit_paper_cmux.sh build/sgen-fpt/forward.v \
  build/sgen-fpt/inverse.v build/chisel-paper
tools/emit_paper_bitwise_cmux.sh build/sgen-fpt/forward.v \
  build/sgen-fpt/inverse.v build/chisel-paper-bitwise-cmux
tools/emit_paper_bitwise_batched_cmux.sh build/sgen-fpt/forward.v \
  build/sgen-fpt/inverse.v build/chisel-paper-bitwise-batched
tools/emit_paper_batched_cmux.sh build/sgen-fpt/forward.v \
  build/sgen-fpt/inverse.v build/chisel-paper-batched
tools/emit_paper_batched_blind_rotate.sh build/sgen-fpt/forward.v \
  build/sgen-fpt/inverse.v build/chisel-paper-blind-rotate
tools/emit_paper_bitwise_batched_blind_rotate.sh \
  build/sgen-fpt/forward.v build/sgen-fpt/inverse.v \
  build/chisel-paper-bitwise-blind-rotate
tools/emit_paper_bitwise_batched_blind_rotate_sample_extract.sh \
  build/sgen-fpt/forward.v build/sgen-fpt/inverse.v \
  build/chisel-paper-bitwise-blind-rotate-sample-extract
tools/emit_paper_buffered_bitwise_batched_blind_rotate_sample_extract.sh
tools/emit_paper_buffered_blind_rotate_accelerator.sh
```

The Blind Rotate tops load raw TLWE mask coefficients and body, implement
TFHEpp's corrected modulus switch, initialize each accumulator with the
rotated test vector, and schedule one CMUX per bootstrapping-key index. Their
external key address includes context, dimension, decomposition row, spectral
point, switched exponent, and first-beat tags. The default input dimension is
TFHEpp `lvl0param::n=630`; set `FPT_BLIND_ROTATE_DIMENSION` to elaborate a
different parameter without changing the Chisel source. The
`sample_extract` variant automatically returns index-zero TLWEs and is the top
used for the historical complete FPT-versus-HOGE route comparison. The
buffered accelerator is the physical-boundary successor: its complete
16-result zero schedule is 190,646 cycles (11,915.4 cycles/result), including
all 630 key coefficients and 16,400 output beats. Reproduce that schedule and
the cache and complete physical maps with:

```sh
tools/measure_fpt_buffered_blind_rotate_accelerator_schedule.sh
tools/synthesize_fpt_bootstrapping_key_buffer.sh
tools/synthesize_fpt_buffered_blind_rotate_accelerator.sh
```

The physical top has 1,012 port bits, compared with 18,956 on the buffered
verification top and 31,869 on the original direct-key top. Its two cache
banks hold 442,368 logical bits and map standalone to 192 `RAMB36E2`, 14,065
LUTs, 965 FFs, and no DSP or distributed RAM in the local UltraScale+ flow.
The flattened accelerator maps to 620,387 estimated logic cells, 1,013,285
LUTs, 1,348,958 FFs, 457 BRAMs, 5,100 distributed-RAM primitives, and 5,408
DSP48E2s. Relative to the direct-key map, this adds exactly the cache's 192
BRAMs and no DSP or distributed RAM; whole-design packing adds only 457 LUTs
and 8,461 FFs.

SGen remains a separate Verilog BlackBox in synthesis; CIRCT does not append
the generated sources or its resource file list to `CmuxEngine.sv`.  The
physical batch accumulator is 64 banks of 112 packed 128-bit words, duplicated
to provide independent prefetch and inverse-update reads while writes are
mirrored.  CIRCT emits 128 synchronous arrays rather than the register
version's context storage as flip-flops.  Two 1024-coefficient buffers feed
one shared ten-stage negacyclic barrel rotator.  The External Product
accumulator is also lane-banked as 128 banks by four spectral points and
repacks directly to the 64-lane inverse stream.

`BitwiseNegacyclicReorder` is the standalone replacement path for the current
full-width barrel rotator. At the Set II shape it loads 64 32-bit coefficients
per cycle, transposes them into 2-bit banks, and emits all 1024 rotated
positions over 16 bitwise cycles. Wrapped coefficients use serial
two's-complement carry, so every exponent in `[0, 2N)` remains exact. The
paper-scale reorder emits as 3.02 MB. `BitwiseCmuxDecompositionFrontend` adds
bit-serial subtraction, the fixed decomposition bias, and both centered
base-10 digit levels; it emits as 5.01 MB and passes complete Verilator lint.
`BitwiseCmuxForwardFrontend` runs both TRLWE components in parallel and streams
all four component/level rows in the forward FFT's folded 128-lane order. Its
two digit buffers overlap the 16-cycle bitwise pass with the 16-cycle row
stream, and the sustained test accepts one exponent every 16 cycles while the
downstream remains ready. The paper-scale output is 7.51 MB and passes lint
across 7.17 MB of sources before accumulator writeback. The complete stateful
version adds lane-local inverse-update adders and a drain path, emits as 10.22
MB, and passes lint across 9.75 MB of sources. It retains a standalone emitter,
and `CmuxEngine` can now select it for a complete SGen forward/external-product/
inverse/update/drain path. The paper bitwise engine emits as 11.57 MB, lints
with the real SGen sources across 37.34 MB in 19 modules, and contains no
`NegacyclicBarrelRotator` module. Its control schedule is 224 cycles after
command acceptance (225 launch-inclusive), 17 more than the barrel path; the
end-to-end small model verifies the same 17-cycle offset. The opt-in Set-II
executable now uses bounded Verilator translation units instead of the former
monolithic C++ output. It has the same 224-cycle latency and exactly
preserves all 2,048 nonzero Torus words with a zero external product. The
barrel engine's separate full-size numerical executable drives a dense
nonzero four-row external product through the real generated transforms and
checks its complete Q27.3 error distribution against the C++ model. Selecting
the folded frontend gives the identical distribution at its expected
224-cycle latency. The bitwise batched top alternates two transposed working
sets over the replicated accumulator banks and shares one inverse FTT between
both output components. Its paper shape uses 16 contexts, emits as 11.36 MB,
lints with the real SGen sources across 39.17 MB in 34 modules, preserves the
`128 x 128` memory arrays, and contains no full-width barrel module. The
executable accepts and completes 17 commands at II=16, has 237-cycle
first-command latency, reuses context zero, and exactly preserves all 32,768
nonzero accumulator words. Thus 16 contexts cover sustained 16-cycle reuse.
Reproduce the standalone blocks with:

```sh
tools/emit_paper_bitwise_reorder.sh build/chisel-paper-bitwise
tools/emit_paper_bitwise_frontend.sh build/chisel-paper-bitwise-frontend
tools/emit_paper_bitwise_forward_frontend.sh \
  build/chisel-paper-bitwise-forward
```

Map the paper-scale barrel and bitwise coefficient frontends to the local
Yosys UltraScale+ library and print comparable LUT/FF/mux counts with:

```sh
tools/synthesize_coefficient_frontends.sh
```

Pass `barrel-batched bitwise-batched` to run the larger replicated-memory
frontends as well. Results and full logs are written below
`build/yosys-coeff/`; see `docs/hardware-comparison.md` for the measured
single and sustained-throughput batched comparisons and their limitations.

Vivado scripts run the transform alone or the complete CMUX out of context on
the U280. The reproducible comparison runner regenerates both SGen transforms
and both sustained-II=16 Chisel tops, records commits and source hashes, then
routes the 14-context barrel and 16-context bitwise designs sequentially with
identical constraints:

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
tools/run_u280_comparison.sh ../SGen build/vivado-u280-comparison
```

Use the same flow on the complete Blind Rotate wrapper with:

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
tools/run_u280_blind_rotate_comparison.sh ../SGen \
  build/vivado-u280-blind-rotate-comparison
```

The fixed-point FTT versus HOGE modular-NTT comparison uses both checkouts and
routes matched forward, inverse, and Blind Rotate boundaries. Its default FPT
Blind Rotate top includes the physical two-coefficient key cache and 864-bit
load port:

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
tools/run_u280_fpt_hoge_comparison.sh third_party/HOGE ../SGen \
  build/vivado-u280-fpt-hoge-comparison
```

For the other machine, package the exact already-generated RTL and route it
without SGen, HOGE, JDK, or sbt:

```sh
tools/package_u280_fpt_hoge_handoff.sh \
  build/vivado-u280-fpt-hoge-prepared \
  build/u280-fpt-hoge-portable

FPT_PREPARED_VERIFY_ONLY=1 \
  build/u280-fpt-hoge-portable/tools/run_prepared_u280_fpt_hoge_comparison.sh \
  build/u280-fpt-hoge-portable
```

The portable bundle checks clean Git provenance plus all seven RTL inputs and
every flow checksum before Vivado can start. Remove
`FPT_PREPARED_VERIFY_ONLY=1` on the route machine; the standard period, job,
design-selection, and reuse controls still apply. The former direct-key FPT
top remains available as the explicit `fpt-blind-rotate` design.

With Verilator available, preparation validates and measures the direct-key
FPT, physical buffered FPT, and HOGE full Blind Rotate wrappers. The physical
FPT batch completes 16 raw-TLWE inputs and sample-extracted outputs in 190,646
cycles, or 11,915.4 cycles/result, including all 630 narrow key-coefficient
loads. The compiled schedule models use content signatures, so
`FPT_SCHEDULE_BUILD_DIR`, `FPT_BUFFERED_SCHEDULE_BUILD_DIR`, and
`HOGE_SCHEDULE_BUILD_DIR` caches remain reusable when the same RTL is
regenerated in a different handoff directory.

The HOGE reference is already physically buffered: eight 512-bit key streams
feed two internal double-bank TRGSW caches. Its schedule accepts exactly
122,112 beats per stream, not the former ready-cycle count of 122,512. The
two boundary checkers lock FPT's 1,012 port/442,368 cache bits and HOGE's 4,663
port/1,572,864 cache bits before a handoff manifest is written.

When Yosys is installed, the same preparation independently checks the full
Chisel/SGen synthesis hierarchy and the expected coefficient-accumulator,
External Product, exponent, and sample-extraction memory shapes. The External
Product contributes two `4 x 15,360` synchronous arrays (122,880 logical
bits). The audit also maps the other memories through their
real single-clock parent contexts to the UltraScale+ primitive library. The
current result is 256 `RAMB36E2` for the replicated accumulators, 9
`RAMB18E2` for the exponent store, and 150 `RAM32M16` distributed-RAM
primitives for the complete sample-extraction context. These are local Yosys
mapping estimates; the common Vivado route remains the hardware result.

See [the FPT/HOGE comparison guide](docs/fpt-hoge-comparison.md) for the
precise boundaries, throughput normalization, source-only preparation, and
interpretation limits.

Set `FPT_VIVADO_PREPARE_ONLY=1` to validate source generation on a machine
without Vivado, or `FPT_VIVADO_REUSE=1` to retain runs that already have
machine-readable metrics. See [the U280 handoff](docs/u280-handoff.md) for
prerequisites, output layout, primitive-count definitions, and the standalone
transform flow.

The lower-level commands remain available. The default 3.425 ns constraint
matches HOGE's reported 292 MHz; pass `5.0` to reproduce FPT's 200 MHz
operating point:

```sh
vivado -mode batch -source chisel/scripts/synth_sgen_u280.tcl \
  -tclargs build/sgen-fpt/forward.v FptSGenForward \
  build/vivado-sgen-forward 3.425
vivado -mode batch -source chisel/scripts/synth_paper_cmux_u280.tcl \
  -tclargs build/chisel-paper/CmuxEngine.sv \
  build/sgen-fpt/forward.v build/sgen-fpt/inverse.v \
  build/vivado-paper-cmux 3.425 CmuxEngine
vivado -mode batch -source chisel/scripts/synth_paper_cmux_u280.tcl \
  -tclargs build/chisel-paper-batched/BatchedCmuxEngine.sv \
  build/sgen-fpt/forward.v build/sgen-fpt/inverse.v \
  build/vivado-paper-batched-cmux 5.0 BatchedCmuxEngine
vivado -mode batch -source chisel/scripts/synth_paper_cmux_u280.tcl \
  -tclargs build/chisel-paper-bitwise-cmux/CmuxEngine.sv \
  build/sgen-fpt/forward.v build/sgen-fpt/inverse.v \
  build/vivado-paper-bitwise-cmux 5.0 CmuxEngine
vivado -mode batch -source chisel/scripts/synth_paper_cmux_u280.tcl \
  -tclargs build/chisel-paper-bitwise-batched/BatchedCmuxEngine.sv \
  build/sgen-fpt/forward.v build/sgen-fpt/inverse.v \
  build/vivado-paper-bitwise-batched-cmux 5.0 BatchedCmuxEngine
```

These scripts produce pre- and post-route utilization and timing, route
status, DRC, vectorless power, routed checkpoints, and compact TSV metrics.
The clock constraint is loaded before synthesis and verified afterward. Runs
and report regeneration reject missing constraints, incomplete/error routes,
and Fatal, Error, Critical Warning, or unclassified post-route DRC violations.
Vivado is not installed in this workspace, so the checked regression stops at
Chisel tests, complete-design Verilator lint, mocked acceptance-flow tests,
source-only handoff generation, and open-source UltraScale+ mapping;
placed-and-routed hardware benefit claims must wait for those U280 reports.

See `docs/hardware-comparison.md` for the reproduced 211/220-cycle Set-II CMUX
schedule, generated multiplier-expression comparison, and the remaining
U280 measurement checklist.

For a pre-route comparison against HOGE, run
`tools/synthesize_fpt_hoge_transforms.sh` after preparing the common-U280
handoff. The current Yosys map finds 3.973x/2.458x forward/inverse throughput
per estimated logic cell and 1.664x/1.378x throughput per mapped DSP. The
DSP48E2-sized SGen lowering maps each split fixed-point product to exactly one
DSP, reproducing the intended pre-route multiplier-efficiency advantage. Raw
counts, bit-exact validation, and the remaining placement/timing caveats are
in `docs/fpt-hoge-comparison.md`.

The numerically stable `tfhepp-hardware` profile is intentionally not in the
first U280 bundle. Its Q27.24 inverse FTT alone maps to 6,687 DSP48E2s, and the
wide External Product adds 3,072, exceeding the card's 9,024 DSPs before the
forward FTT is included. The first route therefore uses paper Set-II widths;
see `docs/u280-handoff.md` for the numerical and resource fit gate.

Map the complete raw-TLWE-through-sample-extraction wrappers with:

```sh
tools/synthesize_fpt_hoge_blind_rotate.sh
```

At an equal clock, the current measured physical-wrapper schedule gives FPT
13.287x the HOGE result rate. Its cache-inclusive map gives
3.361x/3.693x/7.641x/4.992x throughput per logic cell/LUT/FF/DSP. The older
`66c8dc1` map used 14,574 DSPs, exposing CIRCT's widened External Product
multiplications and two parallel inverse cores. The exact Gauss MAC maps the
standalone 256-lane External Product to 1,536 DSPs instead of 9,216, and the
throughput top serializes both components through one inverse FTT. Inferred
accumulator memory and the physical key cache produce a complete-wrapper map
of 620,387 estimated logic cells, 1,013,285 LUTs, 1,348,958 FFs, and 457 BRAMs
while retaining exactly 5,408 DSPs. Run
`tools/synthesize_fpt_external_product.sh` to isolate the MAC and storage
changes. These are local technology maps without timing or routing, so the
U280 bundle remains the final comparison. The raw counts and normalization
are in
[the FPT/HOGE comparison guide](docs/fpt-hoge-comparison.md).

## TFHEpp fixed-point Blind Rotate

The integration currently supports native 32-bit Torus parameters.  Its
bootstrapping key is normalized to real Torus units before being quantized to
the paper's BK format; this is why it is a distinct key type rather than a
drop-in replacement for TFHEpp's double-valued `BootstrappingKeyFFT`.

The test deliberately uses TFHEpp's existing `lvl01param` cryptographic
parameters (N=1024), not a paper-specific parameter class.  Offline key
preparation creates genuine noisy TRGSW encryptions with uniform Torus masks,
uses the double tangent FFT to form each coefficient-domain mask product, and
then stores only the quantized FPT spectra.  Blind Rotate itself uses the
fixed-point forward FFT, pointwise multiply-accumulate, inverse FFT, and exact
integer conversion from the normalized inverse format back to the Torus.
The integration test also rejects any output coefficient with nonzero bits
below that format's Torus quantum, so a future floating-point shortcut cannot
silently restore precision that the RTL does not have.

Because TFHEpp's decomposition parameters differ from the paper's, the adapter
defaults to a high-precision reference profile (BK Q8.24, FFT Q18.28, IFFT
Q27.24). The narrower guarded profile (Q8.24, Q18.20, Q27.14) and paper formats
remain selectable compile-time profiles. In the deterministic 16-input sweep,
the high-precision reference, guarded profile, and guarded profile with only
the BK narrowed to Q8.19 each decrypted 16/16. Narrowing only the FFT to
Q18.12 gave 10/16, narrowing only the IFFT to Q27.3 gave 8/16, and exact Set II
gave 9/16; every overflow counter remained zero. A separate randomized test
also exposed a guarded-profile failure, which is why it is not the default.
This small diagnostic is not a failure-probability or security study, but it
establishes that directly applying Set II to TFHEpp's default parameters is
not a valid cryptographic configuration. Format or TFHE parameters must be
retuned before making that claim.

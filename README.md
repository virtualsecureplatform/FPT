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
cmake --build build-tfhepp -j --target fpt_tfhepp_blind_rotate_test
ctest --test-dir build-tfhepp -R fpt_tfhepp --output-on-failure
```

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
coefficient storage. With the integrated transforms, the register schedule is
203 cycles and needs 13
contexts for sustained 16-cycle reuse.  The emitted physical top instead uses
replicated synchronous memory banks and two polynomial prefetch buffers.  Its
eight-cycle prefetch overlaps the prior 16-cycle decomposition stream,
retaining the 16-cycle interval with a 212-cycle latency and 14 contexts.  In
the bitwise variant, two transposed working sets replace those buffers and the
barrel. The Set-II executable measures a 229-cycle latency and 16-cycle
completion interval; 15 contexts sustain reuse at that rate. In all cases,
the double-buffered External Product PISO drains at the 64-lane inverse width
while the next transaction accumulates at the
128-lane forward width. `CmuxEngine` remains the simpler single-command
correctness top.

`SampleExtractIndexZero` converts the natural-order TRLWE drain to TFHEpp's
index-zero TLWE order using synchronous mask memory. It sustains one Torus
coefficient per cycle, including the reversed/negated mask, and preserves the
output across arbitrary backpressure. `BatchedBlindRotateSampleExtractEngine`
automatically drains every completed context through that stage, tags each
result coefficient with its context, and marks only the last TLWE of the full
batch. This aligns the FPT Blind Rotate output boundary with HOGE.

## RTL source policy

All handwritten synthesizable FPT RTL is Chisel under `chisel/src/main`.
Verilog is used only for generated SGen transform BlackBoxes, including the
three checked-in small regression fixtures. Chisel-emitted SystemVerilog and
paper-sized SGen output remain generated build artifacts rather than a second
handwritten implementation. `tools/check_rtl_source_policy.sh` enforces this
boundary over tracked HDL files and runs with the CTest reference suite.

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
64-lane inverse transform shapes; set `INTEGRATED_TANGENT=0` for the cyclic
comparison cores. See `sgen/README.md` for the remaining differences from the
unpublished FPT generator extensions.

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
```

The Blind Rotate tops load raw TLWE mask coefficients and body, implement
TFHEpp's corrected modulus switch, initialize each accumulator with the
rotated test vector, and schedule one CMUX per bootstrapping-key index. Their
external key address includes context, dimension, decomposition row, spectral
point, switched exponent, and first-beat tags. The default input dimension is
TFHEpp `lvl0param::n=630`; set `FPT_BLIND_ROTATE_DIMENSION` to elaborate a
different parameter without changing the Chisel source. The
`sample_extract` variant automatically returns index-zero TLWEs and is the top
used for the complete FPT-versus-HOGE route comparison.

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
`NegacyclicBarrelRotator` module. Its control schedule is 219 cycles after
command acceptance (220 launch-inclusive), 17 more than the barrel path; the
end-to-end small model verifies the same 17-cycle offset. The opt-in Set-II
executable now uses bounded Verilator translation units instead of the former
monolithic C++ output. It measures the same 219-cycle latency and exactly
preserves all 2,048 nonzero Torus words with a zero external product. The
bitwise batched top alternates two transposed working sets over the replicated
accumulator banks. Its paper shape uses 15 contexts, emits as 11.09 MB, lints
across 35.97 MB in 25 modules, preserves the `120 x 128` memory arrays, and
contains no full-width barrel module. The executable accepts and completes 16
commands at II=16, measures 229-cycle first-command latency, reuses context
zero, and exactly preserves all 30,720 nonzero accumulator words. Thus 15
contexts cover sustained 16-cycle reuse.
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
routes the 14-context barrel and 15-context bitwise designs sequentially with
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

The direct fixed-point FTT versus HOGE modular-NTT comparison uses both
checkouts and routes matched forward, inverse, and Blind Rotate boundaries:

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
tools/run_u280_fpt_hoge_comparison.sh ../HOGE ../SGen \
  build/vivado-u280-fpt-hoge-comparison
```

With Verilator available, preparation also validates and measures both full
Blind Rotate wrappers. The current FPT batch completes 15 raw-TLWE inputs and
sample-extracted outputs in 180,736 cycles, or 12,049.1 cycles/result. The
compiled schedule models use content signatures, so
`FPT_SCHEDULE_BUILD_DIR` and `HOGE_SCHEDULE_BUILD_DIR` caches remain reusable
when the same RTL is regenerated in a different handoff directory.
When Yosys is installed, the same preparation independently checks the full
Chisel/SGen synthesis hierarchy and the expected accumulator, exponent, and
sample-extraction memory shapes. It also maps those memories through their
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
and source-only handoff generation; hardware benefit claims must wait for
those U280 reports.

See `docs/hardware-comparison.md` for the reproduced 203/212-cycle Set-II CMUX
schedule, generated multiplier-expression comparison, and the remaining
U280 measurement checklist.

The integration currently supports native 32-bit Torus parameters.  Its
bootstrapping key is normalized to real Torus units before being quantized to
the paper's BK format; this is why it is a distinct key type rather than a
drop-in replacement for TFHEpp's double-valued `BootstrappingKeyFFT`.

The test deliberately uses TFHEpp's existing `lvl01param` cryptographic
parameters (N=1024), not a paper-specific parameter class.  Offline key
preparation creates genuine noisy TRGSW encryptions with uniform Torus masks,
uses the double tangent FFT to form each coefficient-domain mask product, and
then stores only the quantized FPT spectra.  Blind Rotate itself uses the
fixed-point forward FFT, pointwise multiply-accumulate, and inverse FFT.

Because TFHEpp's decomposition parameters differ from the paper's, the adapter
currently uses a guarded profile (BK Q8.24, FFT Q18.20, IFFT Q27.14).  The
paper's narrower formats remain available through `ArithmeticProfile` and are
covered by standalone arithmetic tests; directly applying Set II to TFHEpp's
default parameters was empirically unreliable even without overflow.  Format
narrowing is therefore an explicit evaluation step rather than an assumed
equivalence.

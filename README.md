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
the C++ CMUX model and takes 133 cycles.  The integrated-tangent SGen backend
overlaps the six forward decomposition rows at one frame every four cycles,
takes 149 cycles, and differs by at most one Q27.14-to-Torus quantum (`2^18`)
from the radix-2 C++ result.  The small case is dominated by pipeline fill;
the paper-sized throughput/resource comparison is generated separately.

`BatchedCmuxEngine` supports two coefficient-storage implementations.  With
the integrated transforms, the register schedule is 203 cycles and needs 13
contexts for sustained 16-cycle reuse.  The emitted physical top instead uses
replicated synchronous memory banks and two polynomial prefetch buffers.  Its
eight-cycle prefetch overlaps the prior 16-cycle decomposition stream,
retaining the 16-cycle interval with a 212-cycle latency and 14 contexts.  In
both cases, the double-buffered External Product PISO drains at the 64-lane
inverse width while the next transaction accumulates at the 128-lane forward
width. `CmuxEngine` remains the simpler single-command correctness top.

## Legacy scheduling prototype

The default build also generates deterministic vectors from the C++ model and
checks an earlier SystemVerilog scheduling prototype with Icarus Verilog:

```sh
cmake -S . -B build -DFPT_BUILD_RTL_TESTS=ON
cmake --build build -j
ctest --test-dir build -R fpt_rtl --output-on-failure
```

This prototype regression covers scalar and four-lane cyclic FFTs, forward and inverse
tangent wrappers, the Gauss butterfly, and scalar and four-lane mixed-format
External Product MACs.  The twiddle multiply uses the paper's three-real-
multiplier Gauss form.  The External Product MAC uses four real multipliers
and performs a single full-precision requantization before the wrapped
accumulator addition.

It remains as a temporary cross-check while equivalent Chisel modules are
added; it is not the implementation intended for hardware evaluation.
`rtl/fpt_fft_core.sv` is a one-butterfly iterative reference core, not yet the
paper's fully overlapped radix-2^4 streaming architecture.  It accepts natural-order
complex frames, stores them in bit-reversed order, and produces natural-order
FFT frames.  `rtl/fpt_fft_wide_core.sv` evaluates a configurable number of
butterflies per cycle and loads/emits the same number of samples per cycle.
For `M = N/2` complex points and `L` lanes, its non-overlapped frame cost is
`M/L + log2(M)*M/(2L) + M/L` cycles.  Its register-array memory is deliberately
transparent for verification; a high-throughput implementation still needs
banked RAM or an SGen permutation network and overlap between frames.

Twist and FFT coefficients use external ROM interfaces.  The U280 synthesis
script in `rtl/scripts/synth_u280.tcl` targets HOGE's
`xcu280-fsvh2892-2L-e` part and 292 MHz clock and accepts parameter overrides
after its output-directory argument.  For example:

```sh
vivado -mode batch -source rtl/scripts/synth_u280.tcl \
  -tclargs fpt_tangent_fft_wide_core build/vivado-fft-l8 \
  POINTS=512 LANES=8 DATA_WIDTH=38 TWIDDLE_WIDTH=34 TWIDDLE_FRAC=32
```

Vivado 2023.2 (or a compatible installation) is required and is not present
in this workspace.  Yosys is used only for structural checks because its
UltraScale+ DSP mapping is not representative of Vivado's signed asymmetric
DSP48E2 mapping.

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
tools/emit_paper_batched_cmux.sh build/sgen-fpt/forward.v \
  build/sgen-fpt/inverse.v build/chisel-paper-batched
```

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
MB, and passes lint across 9.75 MB of sources. It is still standalone; the live
CMUX coefficient store continues to use the ten-stage 32-bit barrel network
until this frontend is connected to its forward and inverse transforms.
Reproduce the standalone blocks with:

```sh
tools/emit_paper_bitwise_reorder.sh build/chisel-paper-bitwise
tools/emit_paper_bitwise_frontend.sh build/chisel-paper-bitwise-frontend
tools/emit_paper_bitwise_forward_frontend.sh \
  build/chisel-paper-bitwise-forward
```

Vivado scripts run the transform alone or the complete CMUX out of context on
the U280.  The default 3.425 ns constraint matches HOGE's reported 292 MHz;
pass `5.0` as the final argument to reproduce FPT's 200 MHz operating point:

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
```

These scripts produce hierarchical utilization, timing, power, and routed
checkpoint reports.  Vivado is not installed in this workspace, so the
checked regression stops at Chisel tests plus complete-design Verilator lint;
hardware benefit claims must wait for those U280 reports.

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

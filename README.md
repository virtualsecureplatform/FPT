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
owns the fixed-point arithmetic, tangent boundaries, External Product, CMUX
control, and top-level interfaces.  Generated SGen Verilog may be instantiated
as a black box for its continuous-flow cyclic FFT permutation network.

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
the C++ CMUX model and takes 133 cycles.  The SGen backend overlaps the six
forward decomposition rows at one frame every four cycles, takes 138 cycles,
and differs by at most one Q27.14-to-Torus quantum (`2^18`) from the radix-2
C++ result.  The small case is dominated by pipeline fill; the paper-sized
throughput/resource comparison is generated separately.

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

For the continuous-flow comparison path, `sgen/fpt-sgen.patch` adapts the
current upstream SGen complex multiplier and twiddle widths, and
`tools/generate_sgen_fpt.sh` generates configurable full-throughput cyclic
FFT/IFFT Verilog with stable `FptSGenForward` and `FptSGenInverse` module
names.  The defaults reproduce the paper's 512-point, 128-lane forward and
64-lane inverse transform shapes.  See `sgen/README.md` for the remaining
differences from the unpublished FPT generator extensions.

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

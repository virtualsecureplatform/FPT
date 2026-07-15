# SGen streaming baseline

The paper used a private extension of SGen for continuous-flow negacyclic
FFTs. Upstream SGen provides the essential full-throughput streamed
permutation network, but not all FPT changes. The `fpt` branch of
[`virtualsecureplatform/SGen`](https://github.com/virtualsecureplatform/SGen/tree/fpt)
carries the reproducible FPT transform changes:

- Gauss/Karatsuba complex multiplication with three real products;
- Q2 twiddles whose total width is four bits shorter than the data path;
- fixed forward-twist and inverse-untwist operators for the tangent FFT.

The workspace also requires SGen commit `5c0680a`, which sign-extends
fractional power-of-two stage scaling. That commit is local until the FPT SGen
fork is published; `tools/generate_sgen_fpt.sh` rejects a checkout without it.

Clone or switch to that branch and build it:

```sh
git clone -b fpt https://github.com/virtualsecureplatform/SGen.git ../SGen
(cd ../SGen && sbt assembly)
```

Then run `tools/generate_sgen_fpt.sh`. Its defaults generate 512-point tangent
forward and inverse transforms at the paper's 128- and 64-lane widths. The
validated baseline uses radix 8 forward and radix 2 inverse; `RADIX_LOG`
controls the forward radix and `IFFT_RADIX_LOG` controls the inverse radix.
All point, lane, and fixed-point parameters can also be overridden, and
`INTEGRATED_TANGENT=0` selects cyclic comparison cores. The wrapper gives the
generated designs stable module names for Chisel BlackBox elaboration.

Run the full-size nonzero numerical regression with:

```sh
tools/test_paper_sgen_numerics.sh ../SGen build/paper-sgen-numerics
```

Prefix the command with `FPT_PAPER_BITWISE_CMUX_NUMERICS=1` to run the same
nonzero oracle through the full-size 2-bit folded coefficient frontend. That
path takes 224 cycles and produces the same histogram as the 207-cycle barrel
frontend.

It compares four 512-point frames against both the fixed radix-2 C++ model and
a quantized double-precision tangent-transform oracle, then drives one dense
nonzero Set-II CMUX through coefficient decomposition, all four forward
transforms, the external product, both inverse transforms, and Torus update.
The current core maximum errors are 518 raw Q18.12 units forward and 8 raw
Q27.3 units inverse. The CMUX matches the C++ model exactly on 731 of 2,048
coefficients and is within one inverse raw unit on 1,495; its complete cyclic
error histogram is `[731,764,386,138,29]` for zero through four raw units.
Because Q27.3 has only eight Torus phase bins, the regression checks the
distribution as well as the inherently bounded cyclic maximum. SGen's
radix-8 inverse with `0.5` scaling at every stage still corrupts some nonzero
512-point vectors; set `IFFT_RADIX_LOG=3` only for investigating that known
generator limitation. The radix choice is deliberately not presented as a
paper match.

The checked-in 16-point forward fixture used by the Chisel regression can be
regenerated with:

```sh
INTEGRATED_TANGENT=0 \
FFT_LOG_POINTS=4 FFT_LOG_LANES=2 IFFT_LOG_LANES=2 \
RADIX_LOG=2 IFFT_RADIX_LOG=2 \
  tools/generate_sgen_fpt.sh ../SGen build/sgen-blackbox
```

The guarded-format asymmetric fixtures used by the end-to-end CMUX regression
use an unscaled inverse transform followed by the Chisel normalization stage:

```sh
FFT_LOG_POINTS=4 FFT_LOG_LANES=2 IFFT_LOG_LANES=1 \
RADIX_LOG=2 IFFT_RADIX_LOG=2 \
FFT_INTEGER_BITS=18 FFT_FRACTIONAL_BITS=20 \
IFFT_INTEGER_BITS=27 IFFT_FRACTIONAL_BITS=14 IFFT_STAGE_SCALE=1.0 \
FORWARD_MODULE=FptSGenForwardGuarded16x4 \
INVERSE_MODULE=FptSGenInverseGuarded16x2 \
  tools/generate_sgen_fpt.sh ../SGen build/sgen-cmux
```

SGen reports the required `next`-to-input lead in each generated header.  The
Chisel adapter makes this explicit per BlackBox (one cycle for the fixture's
forward core and four cycles for its inverse core).

This is a streaming architecture baseline, not a claim of exact paper RTL.
The fork now specializes tangent constants inside SGen, so the Chisel paper
top no longer implements runtime twist multipliers around a cyclic core. It
still lacks FPT's unpublished per-stage width/scaling schedule and specialized
radix-2^k structures. All handwritten FPT RTL around the generated SGen
BlackBoxes is Chisel.

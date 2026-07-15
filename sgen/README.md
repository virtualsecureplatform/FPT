# SGen streaming baseline

The paper used a private extension of SGen for continuous-flow negacyclic
FFTs. Upstream SGen provides the essential full-throughput streamed
permutation network, but not all FPT changes. The `fpt` branch of
[`virtualsecureplatform/SGen`](https://github.com/virtualsecureplatform/SGen/tree/fpt)
carries the reproducible FPT transform changes:

- Gauss/Karatsuba complex multiplication with three real products;
- Q2 twiddles whose total width is four bits shorter than the data path;
- fixed forward-twist and inverse-untwist operators for the tangent FFT.

Clone or switch to that branch and build it:

```sh
git clone -b fpt https://github.com/virtualsecureplatform/SGen.git ../SGen
(cd ../SGen && sbt assembly)
```

Then run `tools/generate_sgen_fpt.sh`. Its defaults generate the paper-sized
512-point tangent forward and inverse transforms at 128 and 64 lanes. All
point, lane, radix, and fixed-point parameters can be overridden with
environment variables, and `INTEGRATED_TANGENT=0` selects cyclic comparison
cores. The wrapper also gives the generated designs stable module names for
Chisel BlackBox elaboration.

The checked-in 16-point forward fixture used by the Chisel regression can be
regenerated with:

```sh
INTEGRATED_TANGENT=0 \
FFT_LOG_POINTS=4 FFT_LOG_LANES=2 IFFT_LOG_LANES=2 RADIX_LOG=2 \
  tools/generate_sgen_fpt.sh ../SGen build/sgen-blackbox
```

The guarded-format asymmetric fixtures used by the end-to-end CMUX regression
use an unscaled inverse transform followed by the Chisel normalization stage:

```sh
FFT_LOG_POINTS=4 FFT_LOG_LANES=2 IFFT_LOG_LANES=1 RADIX_LOG=2 \
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
radix-2^k structures. The handwritten SystemVerilog under `rtl/` is retained
only as an earlier scheduling oracle.

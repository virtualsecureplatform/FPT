# SGen streaming baseline

The paper used a private extension of SGen for continuous-flow negacyclic
FFTs.  Upstream SGen provides the essential full-throughput streamed
permutation network, but not all FPT changes.  `fpt-sgen.patch` carries the two
arithmetic changes that can be reproduced cleanly on the current upstream
SGen checkout:

- Gauss/Karatsuba complex multiplication with three real products;
- Q2 twiddles whose total width is four bits shorter than the data path.

Apply and build it from the SGen checkout:

```sh
git -C ../SGen apply ../FPT/sgen/fpt-sgen.patch
(cd ../SGen && sbt assembly)
```

Then run `tools/generate_sgen_fpt.sh`.  Its defaults generate the paper-sized
512-point cyclic forward and inverse transforms at 128 and 64 lanes, but all
point, lane, radix, and fixed-point parameters can be overridden with
environment variables.  The wrapper also gives the generated designs stable
module names for Chisel BlackBox elaboration.

The checked-in 16-point forward fixture used by the Chisel regression can be
regenerated with:

```sh
FFT_LOG_POINTS=4 FFT_LOG_LANES=2 IFFT_LOG_LANES=2 RADIX_LOG=2 \
  tools/generate_sgen_fpt.sh ../SGen build/sgen-blackbox
```

This is a streaming architecture baseline, not a claim of exact paper RTL.
The public SGen release still lacks FPT's tangent fold/twist operators,
per-stage width/scaling schedule, and specialized radix-2^k structures.  The
Chisel implementation supplies tangent and fixed-point arithmetic around the
generated cyclic FFT while those generator extensions are added.  The
handwritten SystemVerilog under `rtl/` is retained only as an earlier
scheduling oracle.

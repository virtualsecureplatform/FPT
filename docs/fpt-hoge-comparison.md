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
| Blind Rotate | `n=630`, 15 contexts, base-10 level 2, index-zero sample extraction, 12,049.1 measured wrapper cycles/result | `n=636`, 2 contexts, base-6 level 3, index-zero sample extraction, 158,318.5 measured wrapper cycles/result |

Both transform frames represent one 1024-coefficient negacyclic polynomial.
The FPT transforms come from the tracked SGen `fpt` branch. The HOGE wrappers
are compiled in a temporary copy of the current `chisel/HomGate` project; the
HOGE checkout is not patched. Structural guards require 31 modular
multipliers in HOGE's forward INTT, 32 in its inverse NTT, and 127 in its
Blind Rotate path.

The Blind Rotate tops exclude IKS, Vitis DataMover IP, and on-chip
bootstrapping-key storage. Both receive bootstrapping-key data externally.
Both now retain index-zero sample extraction: HOGE returns two TLWEs and FPT
returns fifteen TLWEs. FPT's Chisel wrapper drains each completed TRLWE into
synchronous mask memory, emits `a(0), -a(N-1), ..., -a(1), b(0)`, and marks
only the last coefficient of the full batch. Complete-top resource totals
must still be reported with batch size and throughput. The transform-only
pairs remain the cleaner measurement of the FTT-versus-NTT arithmetic
representation.

The parameters are intentionally not identical at this stage, as requested.
They are recorded in `manifest.tsv` so a later parameter-alignment experiment
does not overwrite or get confused with this baseline.

## Prepare without Vivado

```sh
FPT_VIVADO_PREPARE_ONLY=1 \
tools/run_u280_fpt_hoge_comparison.sh ../HOGE ../SGen \
  build/vivado-u280-fpt-hoge-prepared
```

This regenerates and lints all six RTL inputs, records all three Git commits
and worktree states, checks HOGE's multiplier structure, and hashes every
synthesis source and Tcl flow. When Verilator is available it also measures
both complete Blind Rotate schedules with zero data and continuously
available bootstrapping keys.

FPT loads 9,465 raw TLWE coefficients, issues 9,450 key transactions, and
returns 15,375 sample-extracted TLWE beats. The full batch takes 180,736
cycles (12,049.1 cycles/result): 9,750 input cycles, 155,190 cycles from run
launch through the end of CMUX computation, and a 15,796-cycle drain tail.
Sample extraction of earlier contexts overlaps the computation. HOGE's final
`TLAST` occurs after 316,637 cycles (158,318.5 cycles/result). Each of its
eight BK streams consumes 122,512 beats, with a maximum inter-port skew of
eight beats. At an equal clock, these wrapper schedules imply 13.139 times
the result rate for FPT, before accounting for routed frequency or resources.

The first FPT Verilator build is large. Matching source and tool signatures
reuse the compiled model on later runs. Set `FPT_SCHEDULE_BUILD_DIR` to keep
that cache outside the handoff directory. Set `FPT_SKIP_FPT_SCHEDULE=1` or
`FPT_SKIP_HOGE_SCHEDULE=1` to omit the corresponding measurement; its
throughput fields are then recorded as `unmeasured`.

When Yosys is installed, preparation also independently elaborates the exact
paper-sized Chisel top together with both generated SGen transforms. This
check rejects missing hierarchy, unexpected synthesis warnings, CIRCT
block-local declarations, or changes to the 128 replicated `120 x 128`
accumulator memories, the `9450 x 11` exponent memory, and the `16 x 2048`
sample-extraction memory. The first elaboration takes roughly a minute and is
signature-cached. Set `FPT_YOSYS_BOUNDARY_DIR` to place its cache elsewhere,
or `FPT_SKIP_YOSYS_BOUNDARY=1` to skip it explicitly. These generic-cell
statistics validate the synthesis boundary; only the Vivado runs provide
U280 resource and timing results.

## Route on the Vivado machine

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
FPT_VIVADO_JOBS=8 \
tools/run_u280_fpt_hoge_comparison.sh ../HOGE ../SGen \
  build/vivado-u280-fpt-hoge-comparison
```

The default design list is:

```text
fpt-forward hoge-forward fpt-inverse hoge-inverse
fpt-blind-rotate hoge-blind-rotate
```

Set `FPT_HOGE_DESIGNS` to a space-separated subset for a shorter run. For
example, route only the arithmetic kernels first:

```sh
FPT_HOGE_DESIGNS='fpt-forward hoge-forward fpt-inverse hoge-inverse' \
tools/run_u280_fpt_hoge_comparison.sh ../HOGE ../SGen \
  build/vivado-u280-fpt-hoge-transforms
```

Runs are sequential. `FPT_VIVADO_REUSE=1` reuses a completed result only when
its source, flow, top, part, clock, tool version, and job-count signature still
matches.

## Result interpretation

`summary.tsv` reports routed timing, primitive counts, power, frame II,
frames/s, frames/s/LUT, and frames/s/DSP. `comparison.tsv` reports FPT minus
HOGE for each matched role and period. The normalized transform metrics are
the primary benefit indicators:

- frame rate shows the benefit of FPT's wider streaming transforms;
- frame rate per LUT measures logic efficiency;
- frame rate per DSP measures multiplier efficiency;
- routed WNS and power show whether the extra parallelism remains physically
  usable.

For Blind Rotate, both throughput values now cover wrapper input through the
final sample-extracted TLWE. The comparison still includes intentionally
different parameter sets, batch sizes, and host-side input/key interfaces, so
it is an architectural upper-level view rather than a controlled arithmetic
microbenchmark. The transform-only pairs remain the controlled evidence for
the fixed-point FTT datapath itself. Vivado vectorless power is an estimate,
not board power.

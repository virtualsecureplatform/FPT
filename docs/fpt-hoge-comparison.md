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
| Blind Rotate | `n=630`, 15 contexts, base-10 level 2, index-zero sample extraction, 10,080 scheduled CMUX cycles/result | `n=636`, 2 contexts, base-6 level 3, index-zero sample extraction, 158,318.5 measured wrapper cycles/result |

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
synthesis source and Tcl flow. When Verilator is available it also drives two
zero TLWEs and continuous zero BK streams through the HOGE wrapper. The final
`TLAST` occurs after 316,637 cycles (158,318.5 cycles/result). Each of the
eight BK streams consumes 122,512 beats, with a maximum inter-port skew of
eight beats. Set `FPT_SKIP_HOGE_SCHEDULE=1` to skip that measurement on a
route host that lacks Verilator.

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

For Blind Rotate, the resource boundaries now both end in sample-extracted
TLWEs, but the normalization remains asymmetric: FPT's 10,080 value covers
its sustained CMUX schedule, while HOGE's 158,318.5 value covers TLWE load
through the final result. The resulting normalization is useful as an
architectural upper-level view, but is less controlled than the
transform-only comparison and must retain that qualification. Vivado
vectorless power is an estimate, not board power.

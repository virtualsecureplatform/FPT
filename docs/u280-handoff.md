# U280 Vivado handoff

This flow routes the two sustained-throughput Set-II CMUX implementations
with identical generated transforms, FPGA part, clock constraints, and
implementation commands:

- `barrel-batched`: fourteen coefficient contexts, a shared 32-bit
  negacyclic barrel, 212-cycle latency, and initiation interval 16;
- `bitwise-batched`: fifteen contexts, two transposed 2-bit working sets,
  229-cycle latency, and initiation interval 16.

The comparison therefore includes the extra state needed by the bitwise
design to preserve throughput. It does not compare a single working core
against a throughput-matched barrel design.

The same runner can instead route the complete Blind Rotate wrapper. That
scope adds raw-TLWE loading, corrected modulus switching, the exponent store,
rotated test-vector initialization, and the full bootstrapping-key scheduler
to both designs. The default domain dimension is TFHEpp's 630. At II=16 this
is 10,080 scheduled CMUX cycles per Blind Rotate. The manifest records the
command count and first-acceptance-to-last-completion span for each batch
shape; these are schedule-derived values, not post-route timing measurements.

## Prerequisites

Use the `fpt` branch of
[`virtualsecureplatform/SGen`](https://github.com/virtualsecureplatform/SGen)
as a sibling of this checkout. The runner assembles `sgen.bat` from that
checkout on every invocation and removes the generated binary afterward when
it was not already present. It then regenerates both transforms, the direct
and physical FPT tops, and the HOGE baselines, so files left in an older
`build/` directory are never synthesis inputs.

The route machine needs:

- a JDK and `sbt` for SGen and Chisel generation;
- Vivado with support for `xcu280-fsvh2892-2L-e`;
- `verilator` for complete-source lint; emitters skip lint when it is
  unavailable or explicitly disabled;
- `yosys` and `jq` optionally provide an independent full-hierarchy
  synthesis-frontend and UltraScale+ memory-mapping check in the direct
  FPT/HOGE flow;
- standard `bash`, `git`, `awk`, `realpath`, and `sha256sum` utilities.

The generator prerequisites above apply when regenerating RTL on the route
machine. A portable prepared bundle avoids that source of drift and needs only
Vivado plus standard shell utilities. Create it on the validated development
machine with:

```sh
tools/package_u280_fpt_hoge_handoff.sh \
  build/vivado-u280-fpt-hoge-prepared \
  build/u280-fpt-hoge-portable
```

The approximately 31 MB directory contains only the seven synthesis RTL files,
the exact three route Tcl files, the route-acceptance/report tools, provenance
manifest, documentation, and `bundle.sha256`. It excludes generated C++
schedule objects, Scala build products, and unrelated HOGE FIRRTL artifacts.
Every source and flow is checked against `manifest.tsv` while packaging, and
every packaged file is checked again before a route starts. The runner also
rejects a manifest prepared from any tracked-dirty checkout.

After copying that directory to the Vivado host, verify it without starting a
route:

```sh
FPT_PREPARED_VERIFY_ONLY=1 \
  ./u280-fpt-hoge-portable/tools/run_prepared_u280_fpt_hoge_comparison.sh \
  ./u280-fpt-hoge-portable
```

No FPT, SGen, HOGE, JDK, sbt, Verilator, or Yosys installation is required for
this prepared-input mode.

## Run the comparison

From the FPT checkout:

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
FPT_VIVADO_JOBS=8 \
tools/run_u280_comparison.sh ../SGen build/vivado-u280-comparison
```

For the complete Blind Rotate scope:

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
FPT_VIVADO_JOBS=8 \
tools/run_u280_blind_rotate_comparison.sh ../SGen \
  build/vivado-u280-blind-rotate-comparison
```

The two periods reproduce the paper's 200 MHz point and HOGE's reported
292 MHz point. Runs are deliberately sequential because either elaborated
design can consume tens of GiB of host memory.

To route the exact portable inputs instead of regenerating them, run:

```sh
FPT_VIVADO_CLOCK_PERIODS='5.0 3.425' \
FPT_VIVADO_JOBS=8 \
  ./u280-fpt-hoge-portable/tools/run_prepared_u280_fpt_hoge_comparison.sh \
  ./u280-fpt-hoge-portable
```

The part, periods, job count, and six-design selection default to the prepared
manifest and accept the same environment overrides as the direct runner. The
default Blind Rotate pair is `fpt-buffered-blind-rotate` and
`hoge-blind-rotate`; `fpt-blind-rotate` selects the historical wide direct-key
FPT boundary explicitly.
Routes and reports are written back under the bundle. `route-manifest.tsv`
records the actual Vivado version and route-time selections, while each run's
content signature includes the source, flow, part, period, job count, top,
clock port, and Vivado version. `FPT_VIVADO_REUSE=1` therefore cannot reuse a
result produced from a different tool or input contract.

Useful controls are:

| Variable | Default | Meaning |
| --- | --- | --- |
| `FPT_U280_PART` | `xcu280-fsvh2892-2L-e` | Vivado target part |
| `FPT_VIVADO_CLOCK_PERIODS` | `5.0 3.425` | Space-separated periods in ns |
| `FPT_VIVADO_JOBS` | `8` | Vivado maximum thread count |
| `FPT_VIVADO_REUSE` | `0` | Reuse completed metrics only when the input signature also matches |
| `FPT_VIVADO_PREPARE_ONLY` | `0` | Generate and hash sources without invoking Vivado |
| `FPT_SKIP_LINT` | `0` | Skip Verilator lint during Chisel emission |
| `FPT_CHISEL_HEAP` | `12G` | Heap used by the bitwise Chisel emitter |
| `FPT_VIVADO_SCOPE` | `cmux` | Select `cmux` or `blind-rotate`; the wrapper script sets the latter |
| `FPT_BLIND_ROTATE_DIMENSION` | `630` | Raw TLWE mask dimension for the Blind Rotate scope |

The direct FPT/HOGE runner additionally accepts
`FPT_SKIP_YOSYS_BOUNDARY=1` and `FPT_YOSYS_BOUNDARY_DIR=...` to control its
cached independent synthesis-boundary check. Yosys generic-cell totals are
recorded as hierarchy-wide values, while the accumulator, exponent, and
sample-extraction primitive counts come from smaller real-clock mapping
contexts. Neither is a substitute for routed U280 utilization.

`FPT_SCHEDULE_BUILD_DIR=...`, `FPT_BUFFERED_SCHEDULE_BUILD_DIR=...`, and
`HOGE_SCHEDULE_BUILD_DIR=...` keep the three Verilator schedule models outside
a handoff directory. Their signatures are content-based, so an identical
regenerated source tree reuses the compiled model even when its absolute path
changes.

The preparation path can be checked on a machine without Vivado:

```sh
FPT_VIVADO_PREPARE_ONLY=1 FPT_SKIP_LINT=1 \
tools/run_u280_comparison.sh ../SGen build/vivado-u280-prepared

# Exercise the clock, route, DRC, reuse, and report acceptance contract.
tests/u280_route_contract_test.sh

# Exercise portable packaging, checksum rejection, mocked routing, and reuse.
tests/prepared_u280_handoff_test.sh
```

## Outputs

`manifest.tsv` records all three Git commits and tracked-worktree states, the
Vivado version, the part and clocks, and SHA-256 hashes of every synthesis
input and both the top-level and shared acceptance-flow Tcl. Each run
directory contains pre- and post-route utilization/timing reports, route
status, DRC, vectorless power, checkpoints, the exact clock XDC, the Vivado
log/journal, and `metrics.tsv`.

The flow reads the clock XDC before `synth_design`; synthesis, placement, and
routing therefore see the same target. It verifies the named clock and period
immediately after synthesis. After routing, it records the route-status
Boolean checks, problematic-net categories, and DRC severity counts. A run is
rejected unless it is fully routed, has no route errors or problematic nets,
and has no Fatal, Error, Critical Warning, or unclassified DRC violations.
Ordinary Warning and Advisory DRCs remain visible in the reports and compact
metrics. A negative routed WNS is retained and printed as a warning instead of
silently treating the target clock as achieved.

After all runs, `summary.tsv` contains one validated row per design and
period, including the route and DRC acceptance fields.
`comparison.tsv` contains the bitwise-minus-barrel difference and percentage
for frequency, primitive counts, and estimated power. Recreate both tables
from existing run directories with:

```sh
tools/report_vivado_u280_comparison.sh build/vivado-u280-comparison
```

The report command independently enforces the same acceptance contract. It
therefore refuses incomplete, invalid, or pre-contract `metrics.tsv` files
even if they were retained after a failed Vivado invocation.

The compact LUT field counts placed leaf primitives whose reference name
starts with `LUT`, including dual-output LUT primitives. FF, DSP48E2,
RAMB18E2, RAMB36E2, URAM288, distributed RAM, SRL, and CARRY8 counts are also
taken directly from the routed cell netlist. Retain `utilization.rpt` when
reporting results because Vivado's utilization categories and packing view
are more detailed than these compact primitive counts. Power is Vivado's
default vectorless estimate unless switching activity is supplied separately,
so it should not be presented as measured board power.

## Standalone transform routes

The forward and inverse SGen blocks can be routed independently with the same
pre-synthesis constraint and machine-readable metrics:

```sh
vivado -mode batch -source chisel/scripts/synth_sgen_u280.tcl \
  -tclargs build/sgen-fpt/forward.v FptSGenForward \
  build/vivado-sgen-forward 5.0 xcu280-fsvh2892-2L-e 8

vivado -mode batch -source chisel/scripts/synth_sgen_u280.tcl \
  -tclargs build/sgen-fpt/inverse.v FptSGenInverse \
  build/vivado-sgen-inverse 5.0 xcu280-fsvh2892-2L-e 8
```

These transform-only results isolate fixed-point FTT DSP packing and timing;
the batched CMUX comparison remains the end-to-end throughput-matched result.

For the direct FPT-versus-HOGE route, including source-faithful HOGE transform
and Blind Rotate tops, use the separate
[`fpt-hoge-comparison.md`](fpt-hoge-comparison.md) flow.

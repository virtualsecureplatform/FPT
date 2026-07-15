# Fixed-point FTT hardware comparison status

The paper-shaped implementation now reproduces FPT's transform schedule in
real generated RTL.  Its Chisel CMUX uses `N=1024`, four decomposition rows,
a 512-point/128-lane forward transform, and two parallel 512-point/64-lane
inverse transforms.  The opt-in Verilator regression observes `done` 191
cycles after command acceptance, or 192 cycles when the launch cycle is
included.  That matches the 192-cycle CMUX latency reported for FPT Set II.

Run the schedule check after generating the paper-size SGen sources:

```sh
cd chisel
FPT_PAPER_SCHEDULE=1 \
FPT_SGEN_FORWARD=../build/sgen-fpt/forward.v \
FPT_SGEN_INVERSE=../build/sgen-fpt/inverse.v \
sbt 'testOnly fpt.PaperCmuxScheduleSpec'
```

## Locally measurable transform tradeoff

The following counts are source-level real multiply expressions in SGen's
generated RTL.  They are useful before technology mapping, but they are not
DSP48 counts: Vivado may decompose one wide expression into multiple DSPs or
implement a constant multiply in LUTs.

| Transform | Stock SGen multiplies | FPT-adapted | Reduction | Stock/FPT latency | Launch interval |
| --- | ---: | ---: | ---: | ---: | ---: |
| 512-point, 128-lane forward | 1006 | 808 | 19.7% | 59 / 64 | 4 / 4 |
| 512-point, 64-lane inverse | 506 | 411 | 18.8% | 80 / 102 | 8 / 8 |

The adapted design uses the three-real-multiply Gauss form and changes
twiddle literals from 30 to 26 bits.  Pipeline depth increases, especially in
the inverse with its per-stage scaling, but frame throughput is unchanged.
CMUX exploits that distinction: all four forward decomposition rows enter at
the four-cycle launch interval, and both inverse components run concurrently.
The result is the launch-inclusive 192-cycle schedule even though the forward
and inverse pipelines themselves are 64 and 102 cycles deep.

Regenerate the table from local SGen outputs with:

```sh
tools/report_sgen_structure.sh \
  build/sgen/dft512_l128.v build/sgen/dft512_l128_fpt.v \
  build/sgen/idft512_l64.v build/sgen-fpt/inverse.v
```

## What remains before claiming a real U280 benefit

The exact U280 result must come from Vivado because generic synthesis does not
model DSP48E2 packing for the signed asymmetric fixed-point products.  Run
both generated transforms and the complete CMUX through the scripts in
`chisel/scripts/`, at 5.0 ns for the paper point and 3.425 ns for a direct
HOGE-frequency comparison.  Record DSP, LUT, FF, BRAM/URAM, achieved timing,
and power from the routed reports.

HOGE's checked-in tree reports about 1.55 ms per gate at 292 MHz, but contains
no utilization report to normalize against.  Its transform structure uses
32-lane, 64-bit modular NTT/INTT datapaths with 32 `INTorusMUL` instances per
transform; each `INTorusMUL` contains a 64-by-64 multiply and modular
reduction.  Comparing that source-level count directly with FPT's narrower
real products would be misleading, so the common U280 post-route flow is the
measurement boundary.

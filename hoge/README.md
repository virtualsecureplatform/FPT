# HOGE comparison baseline

`BaselineEmit.scala` is compiled in a temporary copy of HOGE's current
`chisel/HomGate` project. The reference checkout is never patched. Generate
and lint the baselines with:

```sh
tools/generate_hoge_baselines.sh ../HOGE build/hoge-baselines
```

The generated tops are:

- `HOGEForwardINTTBaseline`: HOGE's 32-lane, 64-bit coefficient-to-NTT
  transform;
- `HOGEInverseNTTBaseline`: HOGE's 32-lane, 64-bit NTT-to-coefficient
  transform;
- `HOGEBlindRotateBaseline`: the current two-context Blind Rotate path,
  including polynomial rotation, decomposition, INTT, external-product MAC,
  NTT, feedback, and sample extraction, but excluding IKS and Vitis DataMover
  IP.

The wrappers use HOGE's own `Config` (`N=1024`, `n=636`, three base-6
decomposition levels) and arithmetic without copying or translating it into
FPT. The emitted transform sources contain 31 and 32 `INTorusMUL` instances;
the Blind Rotate source contains 127 (31 INTT, 64 external product, and 32
NTT). This makes source revisions and Vivado inference attributable to the
checked-out HOGE commit.

The full Blind Rotate comparison is architectural rather than
parameter-identical. HOGE returns sample-extracted TLWEs for two contexts,
whereas the current FPT top drains TRLWE accumulators for 14 or 15 contexts.
Transform-only results isolate the arithmetic representation; complete-top
results must also report throughput and batch size instead of comparing raw
resource totals alone.

Measure the current two-context wrapper schedule with:

```sh
tools/measure_hoge_blind_rotate_schedule.sh ../HOGE \
  build/hoge-baselines build/hoge-blind-rotate-sim
```

With zero TLWEs and continuous zero BK streams, the checked wrapper consumes
40 input beats and 122,512 beats per BK bus, with a maximum inter-port skew of
eight beats, then emits 2,050 result beats. The final `TLAST` occurs at
316,637 cycles, or 158,318.5 cycles per result.

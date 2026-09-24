# Optional overlapped input preprocessing

`FPT_U280_PREPROCESSED_INPUT=1` requires `FPT_U280_CHAINED_INTERFACE=1`.
Both default to disabled. The released chained image and raw-buffer fallback
remain available; host arguments and packed input/output formats do not change.

The new path replaces the 632-by-512-bit raw FIFO and single exponent memory
with a two-beat input queue and two prepared exponent banks. Each bank holds
16-by-630 11-bit exponents and 16 test-vector/initial-exponent descriptors.
The next batch is modulus-switched at one 32-bit word per cycle while the
previous batch computes. Signed correction, rounding and wrapping match the
scalar loader. No coefficient scratch, accumulator, FFT or key cache is duplicated.

A bank is activated only after complete preparation and successful input DMA.
Activation initializes the existing accumulators, then computation waits for
initialization and the first key block. Banks are released at successful
execution/output-DMA completion, independently of host completion acknowledgment.
On a fault, bank ownership is frozen until reset so an abort cannot invalidate
live core reads. Accepted invocations retire in order with the existing fail-stop
semantics. Only ownership/control state is reset; RAM contents are not reset.

## Counters

Existing offsets and run-cycle semantics are unchanged. Offset `0x5c` counts
next batches fully prepared and DMA-validated while an invocation is active.
Five additional 32-bit counters are snapshotted with ordered completions:

| Offset | Cycles |
| --- | --- |
| `0x64` | Preparation start to last input word, including input stalls |
| `0x68` | Dispatch to core run start, including initialization/key readiness |
| `0x6c` | Run start to compute completion |
| `0x70` | Compute completion to final output-stream handshake |
| `0x74` | Final output handshake to invocation completion |

Counter values wrap modulo 2^32. With no pending completion, live sequencer
counters are visible; software should read after completion. The new offsets
are zero when the feature is disabled. Hardware clock remains 200 MHz.

`vitis/host/chained.cpp` accepts `FPT_CHAINED_RAW_INPUTS=1` to exercise raw
non-rounded inputs and their corrected initial rotation with zero keys.
`FPT_REQUIRE_PREPROCESSED_INPUT=1` requires preparation-cycle evidence,
dispatch-to-run below 2048 cycles, and overlap on all but the first batch.
These fixtures complement, but do not replace, the nonzero-key numerical tests.

## Validation and rollout

Unit tests compare scalar arithmetic and exercise full-size batches, bank
reuse, backpressure, snapshots, reset and DMA faults. The nonzero Verilator
test also prepares a distinct batch concurrently with computation, preserving
the baseline result hash and 5833-cycle compute/output schedule. Accumulator
initialization takes 319 cycles in that fixture.

Isolated synthesis reports 322 LUTs, 175 FFs and 8 BRAM tiles for prepared
storage/arithmetic; the frontend uses 1085 LUTs, 181 FFs and no BRAM. These
component numbers are not the integrated resource delta. The old 14.5-tile
raw FIFO and 4-tile exponent store are replaced by the 8-tile prepared store.

The separate trial is `build/preprocessed-input/`. Four queued hardware-emulation
batches passed all 65,600 words with raw inputs and three overlapped preparations.
Measured stages were preparation=10125, setup=322, compute=161499,
output-tail=4449, DMA-wait=75 cycles. The disabled numerical regression passed.

Integrated synthesis uses 530909 LUTs, 758160 FFs and 472 BRAM tiles, with
DSP/URAM unchanged. Structural checks passed. The initial flow stopped at its
530170-LUT cap. After review, the user approved a trial-specific increase to
530909 LUTs; the original failed run/evidence is preserved, and
`resume-implementation.sh` resumes the existing synthesis without RTL changes.
That implementation finished routing but failed timing on September 19:
WNS=-0.168 ns, TNS=-3.554 ns, 351 setup endpoints, and one HBM hold endpoint
at -0.001 ns. Global/short congestion was level 6 and timing congestion level 7.
The worst visible path ran from the shallow input queue's read address through
word selection, across SLR1 to SLR0, into the preprocessing correction register;
83% of its 5.067 ns data delay was routing. Vivado subsequently crashed in the
binary timing-report writer, before the routed-checkpoint hook. No new hardware
image was accepted. Current gates remain LUT<=530909, FF<=758967, BRAM<=482.5,
DSP=5624, URAM=236, fully routed clean DRC
and nonnegative setup/hold slack at 200 MHz. Hardware throughput target is
at least 19.0 results/ms, measured using the same 64-batch method as the release.
Stop on failed acceptance; do not relax gates or retry placement automatically.

## Registered-word trial

`build/preprocessed-input-registered/` preserves the failed trial and released
chained image. The optional preparation path now has a 32-bit, two-stage physical
link: serializer/TX in SLR1, RX/preparation in SLR0. Payload registers capture
unconditionally without reset; only valid tokens reset. After bank-start
acceptance, preparation must remain ready through the final word. This asserted
fixed-rate contract removes the return ready path without adding a wide FIFO.
The frontend counts issued words and waits for delayed destination completion
plus successful DMA status before launch. Faults stop new issues, tolerate two
in-flight words draining into the reserved inactive bank, ignore late completion,
and freeze ownership until reset. No host interface or register offsets change.

Rounding computes only the 11-bit exponent increment. The correction residual
is the sign-extended low 21 bits, including ties and torus wraparound. This
removes the exponent carry/reconstruction/subtraction chain from the correction
recurrence; the correction sum and body calculation remain 32-bit and bit-exact.
The link adds two preparation cycles without changing the compute/output schedule.

Validation covers arithmetic equivalence, link latency/readiness, full-size
prepared banks, integrated delayed starts/stalls/bank reuse, active-bank reads,
faults/reset with live tokens, and legacy frontend/sequencer behavior. The 25-test
unit regression and both AXI control modes pass. The nonzero numerical regression
also passes with the same result hash and 5833-cycle compute/output schedule.
Isolated synthesis verifies all 32 direct TX/RX connections: the link uses 66 FFs,
zero LUTs and no RAM. Preparation uses 330 LUTs versus 322 previously, but 22
CARRY8s versus 24; fewer arithmetic dependencies do not imply fewer LUTs. The
frontend uses 1087 LUTs versus 1085. Integrated resource acceptance, hardware
emulation and full implementation remain pending; no congestion, timing or
throughput improvement is claimed yet.

The registered trial subsequently passed four queued hardware-emulation batches
(65,600 matching words, three overlapped preparations). Preparation takes 10127
cycles, exactly two more; setup=322, compute=161499, output-tail=4449 and
DMA-wait=75 are unchanged. Integrated synthesis and structural checks passed,
but the original resource gate stopped at 530913 LUTs versus its 530909 limit.
On September 20 the user approved raising only this trial's LUT cap to 530913.
The separate resume driver preserves the original failure and verifies unchanged
source/XO/RTL hashes before implementation. FF=758226, BRAM=472, DSP=5624 and
URAM=236 remain within their unchanged limits. P&R and hardware acceptance
remain pending; no RTL was changed for this resource-gate adjustment.

The trial disables only the automatic binary route timing summary, verifies the
generated implementation script, and saves the routed checkpoint and strict
all-clock timing/route/DRC metrics before detailed text reports. Negative HBM hold
slack is still a failure. Monitoring snapshots run every 30 minutes. Hardware
tests execute only after full acceptance, with the same six nonzero fixtures and
five 64-batch throughput measurements. Congestion reduction is a secondary goal;
an unchanged coarse level 7 does not override clean timing or replace it.

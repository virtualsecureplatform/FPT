# Frame-local transform and EP controls

This trial follows the fully routed local-control-islands failure: WNS
-3.531 ns, kernel TNS -18093.942 ns, 26839 failing kernel endpoints. The worst
path is reset distribution from SLR0 to FFT-front in SLR2. The independent EP
path from `pendingRow_r_reg` to a receiving replica has 8.328 ns routing delay
and zero logic levels. Preserve both that diagnostic checkpoint and the better
`coefficient-slr0-narrow` comparator (WNS -0.787 ns, TNS -3324.694 ns, 10044
failing kernel endpoints).

## Switches and contracts

`FPT_SGEN_FRAME_CONTROL=legacy|token` defaults to legacy. Token mode is scoped
to the current 512-point, 128-lane, stage2spill commutator forward profile.
The generated top-level interface and data latency do not change.

Forward commutators initialize their phase FFs from the early token, one edge
before the first data beat. Only registered phase drives the payload muxes;
there is no combinational first-beat phase override. Phase advances without global reset.
Selector delays keep shifting through gaps and frame boundaries, so the tail
of the preceding frame is not erased. The early token may reach only local
admission and phase FFs, never payload FFs through combinational logic. Its
aggregate net fanout remains bounded at 256 loads.

Token mode duplicates the high phase bit in one preserved FF per tile. Both
copies receive the same next-phase value on the same edge; each drives one
two-lane payload group. This adds 96 control FFs, with no data pipeline stage
or throughput change. The structural gate checks all six control FFs per tile
and retains the 256-load bound for each copy.

EP row controls use a dedicated unreset two-bit register module with preserved
hierarchy and module-level DONT_TOUCH, in addition to register preservation.
The generic physical-cut register allowed equivalent row-control instances to
collapse to 2 root, 8 regional, and 24 leaf FFs in focused synthesis. The gate
continues to require the intended 8/24/88 FF tree; its limits are not relaxed.

The next leaf split retains 8 root and 24 regional FFs, but uses 132 leaf FFs:
each six-lane tile has two first-row selectors (three lanes each) and one
last-row selector for its existing bank. This adds 44 FFs without adding a
pipeline edge or changing memory organization. Regional first-row fanout is
now at most eight (four tiles times two copies); the leaf limit stays 512.

The IFFT's four late dataset counters have periods 2, 3, 4, and 6. A four-bit
modulo-12 ingress ordinal travels through unreset descriptor delays matching
the existing tokens. Each local counter loads its frame value at admission
and its incremented value at the original late trigger. This preserves the
non-power-of-two bank schedules; simply resetting all selectors to zero on
each frame would not. Only the four ingress ordinal FFs use global reset.
Existing IFFT selector replication remains enabled (284 copies).

Generated RTL includes `FRAME_MODE` and counter alignment manifests.
`SGenFrameRecovery` reads the actual latency and allows startup-only flushing
for latency + input lead + frame beats + one cycles: 62 forward and 101 inverse
for this profile. Kernel launch waits the maximum interval without acknowledging
a pending AXI-Lite start. Transform output validity is masked during recovery.
The input token uses registered recovery state, not a combinational reset gate.
Normal-operation CMUX latency, batch cycles and II must remain unchanged.

`FPT_U280_EP_ROW_CONTROL_TILE_LANES=0|6` defaults to zero. Six-lane row controls
require the existing six-lane final-bank mode. First/last-row bits branch across
the multiplier's existing three stages: 4 roots, 12 regions and 44 leaves at
full width. Each region drives at most four leaves. First-row flags control
local accumulation lanes; last-row flags qualify local final-bank writes. The
input row counter still addresses the key, and row alignment assertions remain.
No product, feedback or memory stage is added.

## Validation and physical gate

`build/frame-local-controls/configuration.sh` selects the combined candidate.
The driver freezes sources and generated RTL and runs independent EP and
transform equivalence, four-state XSIM tests, numerical/schedule regression,
focused synthesis, hw_emu, then one hardware synthesis/implementation.

The four-state candidate receives a one-cycle reset and starts from unknown
internal state. The legacy golden reference is held in reset while its
unreset trigger pipeline flushes; otherwise the reference's counters can be
permanently poisoned by X triggers. Every valid candidate word must be known
and match that reference. Tests cover frame-ordinal wraps and aborted frames.

Physical checks retain all SLR-link and memory contracts, check that global
reset no longer reaches commutator controls, allow only ingress ordinal reset
loads inside IFFT, and check the preserved EP branching topology and fanout.
DSP/BRAM/URAM allocation must stay 5624/468/236. LUT/FF usage is reported rather
than used as an arbitrary aggregate gate.

Full acceptance is clean setup/hold and legal routing at 200 MHz. A partial
result must have clean hold, unchanged performance, kernel WNS >= -0.787 ns,
TNS > -3324.694 ns and fewer than 10044 failing endpoints. Preserve reports
before rejection. Log status every 30 minutes; no physical retry or automatic
commit. Passing the old failed trial is not sufficient to replace the better
routed comparator.

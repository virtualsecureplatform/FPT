# MSB-first window plus selective metadata reset

This opt-in trial is prepared in u280-window-msb-first, separate from the
previous u280-compressed-coefficients implementation. The previous trial
completed with clean routing/hold and all structural gates passing, but failed
setup at WNS -0.176 ns, kernel TNS -18.289 ns, 282 failing endpoints.

## Options and behavior

- FPT_U280_WINDOW_MSB_FIRST=1 selects descending alignment shifts. Default 0.
- FPT_U280_MINIMAL_METADATA_RESET=1 makes selected metadata unreset. Default 0.
- PhysicalControlRegister accepts optional resetMask; None resets every bit.
  On reset, only masked bits clear; other bits continue sampling inputData.
- Public U280 configuration restricts minimal metadata reset to the current
  four-lane coefficient/key implementation, with bank-local initialization.
  Leaf-level tests also exercise streamed initialization with local controls.

Window latency remains four enabled cycles; throughput is one beat/cycle.
Coefficient precision, sign correction, RAM organization, clock, floorplan,
key-write commit latency, and SGen RTL are unchanged.

392 bits lose reset at the U280 shape: 224 scratch selectors/head choices/tail
write selectors, 128 coefficient write-address/isLoad bits, 40 key-write
address bits. Coefficient masks (64 bits) and key masks (64 bits) remain reset.
All other valid bits, rolling commands, ownership and bank initialization
controls retain their existing reset behavior. Address/isLoad/mask packing is
explicit. Protected control-register identity and width remain unchanged.

## Validation and launch

Combined focused synthesis passed lane-local and metadata-reset checks:
coefficient unreset=352/reset=64; key unreset=40/reset=64. Coefficient resources
are 69122 LUT, 111326 FF, 36 BRAM, 128 URAM; key resources are 138 LUT, 1933 FF,
192 BRAM. These are isolated frontend/cache measurements, not full-kernel
resource predictions. Initial checker failures involved OOC top-level reset
ports and root-level memory paths; both were corrected before full launch.

Initial preflight passed 25 tests: selective reset (4), key writes (3), bank
initialization (2), coefficient equivalence (16). The updated coefficient
suite passed another 16 cases, including reset during partial load and an
in-flight window command followed immediately by reloading.

The frozen runner performs common tests, then baseline/shift-only/reset-only/
combined numerical, coefficient, key and initialization tests plus focused
synthesis. It repeats coefficient equivalence at eight lanes, runs regressions,
and requires the combined 16400-word hw_emu smoke test before full synthesis.
All four configurations retain key-write pipelining. Numerical gates require
CMUX 239/II16, batch computation 20852, preload 128 beats/129 cycles, accounted
latency 20853 and unchanged checksum.

The metadata checker verifies exact counts, preserved FFs, unreset reset pins
tied low, constant enables, and retained dynamic write-mask resets. Existing
lane, memory, frame, inverse and twiddle structural gates remain enabled.

One new full P&R uses a fresh hw_A_window_reset_trial directory. Hook binding
is audited in the generated Vivado project after synthesis; the runtime
pre-placement guard requires actual execution of the enable-net protection
hook and all 64 enable nets still protected. No cached project is reused.

Resource caps: LUT 528916, FF 758967; DSP/BRAM/URAM 5624/468/236 unchanged.
Full success means clean route/DRC/hold and setup >=0 at 200 MHz. Partial
improvement requires no regression in whole/kernel WNS, kernel TNS/endpoints,
and >=0.05 ns WNS gain or >=10% kernel TNS improvement. Resource improvement
is claimed only if both LUT and FF decrease; congestion level is reported
independently (target timing level <=6).

Source/RTL hashes are checked at every stage. Baseline is passed explicitly
to the review tool, together with the actual route log path. The global run
lock is shared with the previous worktree. Monitor interval is 1800 seconds.
No automatic retry, gate relaxation, commit, push, or hardware programming.

Artifacts and entry point: build/window-reset-trial/launch-monitored.sh.
Tests and synth logs report their own status; a launched runner is not proof
that later numerical, hw_emu or P&R stages have passed.
# Validation dependency repair (2026-09-15)

The first validation stopped at 18:43 JST: 13 common tests passed, and
22 of 24 baseline tests passed. The two numerical tests could not access
reference vectors in the sibling worktree, which is not mounted into the
Apptainer test environment. This was an input-path failure, not a numerical
mismatch. Both existing vectors were copied byte-for-byte into the trial's
`vectors/` directory and their configuration paths updated. A container-side
readability/nonempty check now runs before the test suites. The vectors are
included in the refreshed source manifest. No RTL, expected checksum, latency
limit, or implementation gate changed. Original logs, configuration, manifest,
and source archive are preserved under `failed-validation-20260915-184348/`.
Validation restarts from the beginning; the single full P&R remains gated.

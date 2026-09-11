# Local-twiddle trial: equivalence harness repair

The 2026-09-11 run stopped in regression, not hardware implementation.
`BankedShuffleEquivalenceSpec` read the selected raw reference's 56-cycle
latency, but instantiated the hard-coded `FptSGenReference` module. Both new
input paths had the basename `forward.v`; ChiselTest's resource list therefore
contained only one staged path. Verilator found an older `FptSGenReference.v`
in the reused test directory, whose actual latency was 72 cycles. The first
new-candidate output consequently failed the comparison at simulation step 188.

The test now stages two distinct filenames, explicitly namespaces every module
and instance in both inputs, and measures latency from those same staged
sources. Raw `FptSGenForward` and older renamed `FptSGenReference` sources are
supported; missing/ambiguous tops fail before simulation. Unit tests cover
same-basename files, stale artifacts, helper naming, and invalid tops. The
existing timing/data comparison is unchanged, including frame gaps and reset.

Resume driver: `build/fixed-link-local-twiddles-test-fix/run-hw.sh`.
It verifies every previous source hash except the repaired test, verifies the
unchanged generated RTL and all completed gates, then reruns all 35 regression
tests before hardware emulation and the one combined physical trial. New
sources and artifacts are frozen separately; original failure evidence is
preserved. No production RTL, clocks, performance budgets, or gates change.
Monitoring remains every 1800 seconds; no automatic physical retry or commit.

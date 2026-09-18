# Host-HBM Laguna placement trial

The wide-output candidate completed routing, but failed full-design timing:
overall WNS -0.100 ns, TNS -0.683 ns, hold +0.001 ns. Its 200 MHz kernel
has +0.004 ns setup slack and no failing setup endpoints. All 18 reported
setup failures are in the 450 MHz host-HBM path (`hmss_0/path_6/slice0_6`),
not the kernel-output port (`path_5`). The worst direct FF-to-FF crossing
uses ordinary slices and has 2.088 ns routed data-net delay.

This opt-in physical-only experiment reserves paired Laguna TX/RX locations
for those exact 18 existing crossings. It does not add pipeline stages,
change RTL, clocks, timing exceptions, vendor IP, or the host ABI. Setting
`FPT_HOST_HBM_LAGUNA_PLACEMENT=1` enables the new hook; it is off by default.

Before any implementation, a routed-checkpoint preflight must validate all
endpoint identities, direct single-load connectivity, clock and control
compatibility, adjacent SLR ownership, available sites within the dynamic
region and applicable pblocks, and physical TX/RX connectivity. A frozen
allocation manifest records the requested sites and BELs. Missing cells,
physical-opt substitutions, unavailable sites, and unsupported control sets
are blockers, not reasons to change the target set or relax checks.

All artifacts are isolated in `build/host-link-laguna`. The prior candidate,
source hashes, and released baseline remain untouched. The new source library
is `vitis/scripts/fpt_host_hbm_laguna.tcl`; its negative checks are exercised
by `tclsh tests/host_hbm_laguna_test.tcl`.

Only after preflight passes may one implementation attempt reuse the existing
synthesized inputs. Both post-place and post-route hooks verify the allocated
locations and unchanged connectivity. All existing resource, structural,
full-route, DRC, setup, and hold gates remain mandatory; congestion levels
remain diagnostics. Monitor every 30 minutes, without automatic retries,
hardware programming, commits, or publication.

## Preflight result: stopped before implementation

The real routed reference passes all 18 endpoint-pair identity, connectivity,
clock, control, and adjacent-SLR checks. All 36 endpoint registers already
have USER_SLL_REG=1 and have neither fixed LOC nor fixed BEL constraints.

The outer pblock_dynamic_region exposes 13440 Laguna sites, but each of
pblock_dynamic_SLR0, pblock_dynamic_SLR1, and pblock_dynamic_SLR2 exposes zero
effective Laguna sites. The failing registers are members of these hard
platform sub-pblocks. Their GRID_RANGES mention Laguna ranges, but the
DERIVED_RANGES omit them; this is not simply a shortage of free device sites.

The allocator therefore stopped without producing an allocation manifest.
No P&R was launched, no register was moved, and no RTL or clock was changed.
The hook and runner remain disabled/gated. The checker now detects this
condition before the expensive free-site scan, with a regression test.

## Authorized narrow pblock repair

The user subsequently authorized proceeding with the constraint repair.
`repair_fpt_host_hbm_pblocks.tcl` moves only the 36 endpoint primitives out of
the restrictive platform sub-pblocks, into three hard Laguna-only pblocks
parented by the unchanged dynamic region. Each new pblock is exactly the
intersection of dynamic-region Laguna sites and the endpoint's original SLR.
The original platform pblocks retain their geometry, snapping, hardness,
routing policy, and every unrelated primitive member. All checks are fail-closed.

The real routed-checkpoint repair passes: SLR0 has 2 target registers and
3360 available region sites; SLR1 has 18 and 6720; SLR2 has 16 and 3360.
These are region site counts, not counts of unoccupied physical pairs.
Changing GRIDTYPES alone was tested in memory, did not expose Laguna sites,
and was reverted. The successful repair changes leaf membership only.

The repair and original placement checks have 10 and 39 passing unit checks,
respectively. Allocation and actual placement legality remain mandatory before
launching implementation. The repair log is `repair-inspect.log`; the initial
blocked preflight log remains preserved separately. No reference checkpoint
is overwritten.

The repaired preflight subsequently passed all 18 allocations and actual
`place_cell` operations, followed by exact location/BEL, direct connectivity,
clock, control, SLR, and repaired-pblock checks. The frozen allocation is
`build/host-link-laguna/allocation.manifest`. The allocator now fills its
clock-region cache in bulk, avoiding thousands of individual database queries.
This proves placement feasibility, not routed timing closure; full P&R still
must pass every original acceptance gate.

## Single-edge pblock correction

The first implementation stopped at placer feasibility with `Place 30-779`:
the Laguna-only SLR1 pblock covered both edges. Individual `place_cell`
acceptance did not check this full-placer restriction. There is no routed
timing result for that attempt.

The correction uses four pblocks, derived from adjacent-SLR direction and
the device's outermost clock-region rows: SLR0 upper (2 registers), SLR1
lower (2), SLR1 upper (16), and SLR2 lower (16). Each contains only the
dynamic-region Laguna sites of that one edge. Every frozen allocated site
must belong to its selected edge; none of the 36 LOC/BEL assignments changes.

The restart is isolated in `build/host-link-laguna-edge`. Its runner requires
a fresh checkpoint preflight for the corrected geometry, and only then starts
one implementation attempt. The failed run and its logs are retained in
`build/host-link-laguna`; copied historical startup logs in the new directory
are separated under `previous-attempt`. No RTL, clock, or timing-gate change.

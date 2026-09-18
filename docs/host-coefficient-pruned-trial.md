# Local queue payload pruning trial

This follow-up to `host-coefficient-locality` removes only the queue tile's
module-wide `KEEP_HIERARCHY`. Pointer/full state and local control preservation
remain. No queue handshake, payload format, latency, arithmetic precision,
cryptographic parameter, or clock changes are intended.

The preceding trial passed 29 simulations and hardware emulation but used
544362 LUTs. Controlled synthesis showed that the hierarchy boundary prevented
unused payload pruning: a 512-bit tile with 256 used output bits occupied
827 LUTs with hierarchy preservation, versus 423 without; both retained seven
control FFs. The full-kernel resource/timing benefit remains to be measured.

The structural checker uses primitive register ownership, not module REF_NAME
or existence. It still requires 16 update tiles and 9 drain tiles, all 139 local
state FFs, DONT_TOUCH, bounded physical RAM-address and logic fanout, and no
direct state loads in another tile.
A 25-tile synthesis probe using freshly emitted RTL tests these requirements
with identical input traffic and half-used payloads, so equivalent control
states must remain separate while unused data can disappear.

Artifacts live in `build/host-coefficient-pruned`. The full host-HBM allocation
is byte-identical to the previous trial; its successful physical preflight is
reused with a checksum, while fresh functional regressions, hardware emulation,
XO packaging, and synthesis are required for the modified RTL. The runner
freezes source hashes and checks allocation/RTL hashes before implementation.

The original resource gates are retained: 530000 LUTs, 758967 FFs, 5624 DSPs,
468 BRAM tiles, 236 URAMs. Existing 200 MHz kernel, host-clock setup/hold, route,
DRC, locality and full host-payload coverage checks remain mandatory.
Functional gates retain the nonzero digest and 5833/4923 completion cycles,
computeDone=474, CMUX latency239/II16, and 16400-word hw_emu smoke.

One attempt with 30-minute status logging; stop on failure. No automatic retry,
board programming, commit, push, or release.

## Physical fanout correction

The first full synthesis used 529147 LUTs and passed functional/hw_emu tests,
but the initial 576-load heuristic rejected a 596-load drain pointer. Inspection
of all 139 state registers found no loads outside their owning tile. The worst
pointer drives 518 RAMD32 address pins, 74 RAMS32 address pins, and four LUT pins.
The inferred RAM32M16 groups use 16 leaf address pins per 14 payload bits;
therefore a 512-bit tile legitimately uses ceil(512/14)*16 = 592 address pins.

The corrected gate separately bounds RAM pins by ceil(tileWidth/14)*16 and
non-RAM logic pins by 64. It requires all RAM loads to be local address pins,
retains cross-tile rejection, and applies smaller bounds to partial tiles.
No production RTL, clocks, resource limits, or timing acceptance changed.
Resume uses the same checksummed synthesis checkpoint and rechecks all queue
tiles before hook audit and P&R; prior successful checks/reports are frozen.

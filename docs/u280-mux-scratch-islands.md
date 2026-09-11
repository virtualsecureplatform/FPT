# Registered IFFT mux islands and grouped coefficient scratch

Comparator: the completed `frame-local-controls-leaf-split` RTL and its routed
diagnostics in `build/frame-local-controls-reset-trace`. Kernel WNS -0.539 ns,
TNS -1796.531 ns, 9020 failing endpoints, clean routing and hold. Kernel-only
utilization is 529876 LUTs / 742178 FFs / 5624 DSP / 468 BRAM / 236 URAM.

## Opt-in architecture

`FPT_SGEN_INVERSE_MUX_ISLAND_BITS=120` enables SGen registered mux islands;
default 0 retains existing emission. Each island owns a same-edge selector
copy, at most 120 capture bits, their muxes and the existing first capture
edge. Register delays beyond that edge remain outside. Only register consumers
(including aliases) are absorbed. Emission is deterministic and the manifest
records selectors, bit selections, muxes and captures. Unsupported consumers
retain existing selector replication. Forward generation is unchanged.

The predecessor revision also clones the already-existing edge immediately
before selector admission, in preserved one-bit modules serving at most 32
islands each. It samples the prior stage, not the current predecessor output,
so no latency is added. Combinational predecessors without an existing edge
retain the original connection and remain subject to the 256-load gate.
Both regional inputs (<=256) and outputs (<=32) are checked: pressure must not
simply move one stage upstream. This addresses the measured 316-load SRL tail
feeding mux_island_405 in the first focused IFFT synthesis.

`FPT_COEFFICIENT_SCRATCH_CONTROL=grouped` now works with coefficient locality
and wide readout. Eight four-bank hierarchy-preserved islands stage controls
and one shared write-data slice, then feed the existing bank cuts. Four read
replicas, memory contents and arithmetic are unchanged. Read latency is three
cycles, with matching metadata/prime/write/source-release alignment. Selected
readout remains incompatible with coefficient locality.

## Validation and gates

The driver in `build/mux-scratch-islands` freezes sources, tests baseline,
IFFT-only, scratch-only and combined configurations, and runs only one full
combined physical trial. IFFT cycle equivalence includes gaps, rollover and
reset/restart in Verilator and four-state XSim. Raw scratch tests include wide,
selected, normalized and grouped profiles; frontend/numerical tests cover
exponent wrap, overlapping work and source reuse. Existing data checksum must
remain b02dc10d50e4111bd5e4b79993b355942fd58b2d6d758d40bc544901f38f372e.

Performance budget: CMUX <=239 cycles, II16, batch <=21059 cycles. Combined
preflight measured 239/20852 with the unchanged batch checksum; the driver
requires those exact measured counts. No clock or precision changes, no
400 MHz pumping.

Focused and full synthesis require manifest-exact island counts, selector
fanout <=128, predecessor fanout <=256, scratch group input/output fanout <=8/16,
preserved EP row controls, frame-local resets and existing registered links.
DSP/BRAM/URAM counts stay fixed; LUT and FF growth may not exceed 2%. Save
checkpoints before structural checks so failures remain inspectable. Keep
existing SLR assignments; do not add fixed slice locations or hard sub-SLR boxes.

Full acceptance requires legal routing and setup/hold closure at 200 MHz.
Partial improvement requires clean routing/hold, no regression in WNS/TNS/
endpoint count, and >=0.05 ns WNS improvement or >=10% TNS magnitude reduction.
Inspect physical spread, control-net delays and congestion by SLR as well as
the top paths. No automatic retries, default changes or commits. Monitor every
1800 seconds and stop at a failing gate. Preserve all previous attempts.

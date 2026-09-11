# Fixed-rate inverse link and local forward twiddles

This opt-in trial retains `mux-scratch-predecessors` RTL, with two changes.
The latest comparator fully routed: WNS -0.696 ns, TNS -1601.269 ns,
8995 failing endpoints, WHS +0.001 ns. The earlier best WNS is -0.539 ns.

## Architecture

`FPT_U280_FIXED_RATE_INVERSE_INPUT_LINK=1` replaces the existing source
elastic register and two-entry inverse boundary with two unconditional
register stages. TX remains in SLR1; RX belongs to SLR0. Each stage carries
3840 data bits, four tag bits and first, with separate resettable start/valid.
There is no payload reset/enable or backward readiness path. Readiness is an
assertion-only fixed-rate contract at the receiver. No pipeline edge is added.
The option requires serialized rotating synchronous EP and the narrow-link
floorplan; its default is zero.

`FPT_SGEN_FORWARD_TWIDDLE_ISLAND_CONSUMERS=4` enables local coefficient-table
generation in the forward front partition only; default zero leaves emission
unchanged. The SGen backend recognizes registered full-domain two-/four-entry
ROM expressions, folding aligned coefficient add/subtract at every original
fixed width. Taps and concatenation retain their exact bit semantics. Dynamic,
deep, differently addressed, or differently delayed operands are not folded.
An N-cycle coefficient path becomes N-1 narrow address stages and the final
local coefficient FF. No coefficient precision, multiplier, or data latency
changes. Islands serve at most four scalar multipliers; identical FFs cannot
merge across their preserved hierarchies. The current profile produces 719
islands, each with a two-cycle schedule.
Within each island, identical/complementary Boolean table columns share one
stored bit, and constant columns are wires. A four-entry table needs at most
seven such FFs, rather than 26 independent FFs; reconstruction uses wires or
inversion only. The manifest records the exact stored-bit count. Sharing never
crosses island boundaries, and the same Q-fanout gate applies to stored bits.

An existing address-register edge is copied, without another cycle, into
regions serving at most 32 islands. The structural checker bounds coefficient
Q fanout at 64, region Q at 32 and shared D-side address loads at 256. The
original coefficient cones become dead and are left for normal synthesis
cleanup. Tiny constant tables are combinational logic, not forced BRAM.

## Validation and execution

The source-frozen driver is `build/fixed-link-local-twiddles/run-hw.sh`.
It runs SGen unit tests, link comparison/negative tests, full forward
bit-and-cycle equivalence, four-state equivalence, focused structure checks,
baseline/link-only/twiddle-only/combined schedule and numerical checks, and
hardware emulation before one full synthesis/implementation. The existing
container is selected by the inherited configuration.

Performance must remain CMUX 239 cycles, II16, batch 20852 cycles, checksum
`b02dc10d50e4111bd5e4b79993b355942fd58b2d6d758d40bc544901f38f372e`.
DSP/BRAM/URAM must remain 5624/468/236. LUT and FF limits remain 2% above
529876 and 742178 respectively. Existing reset, scratch, EP and SLL gates
remain active. Checkpoints precede structural/timing checks.

Full acceptance requires legal complete routing and nonnegative setup/hold
at 200 MHz. Partial improvement requires WNS >= -0.539 ns, TNS >= -1601.269 ns,
at most 8995 failing endpoints, clean routing/hold, and either WNS >= -0.489 ns
or TNS >= -1441.142 ns. Report congestion and attribution as well as WNS.
Monitor every 1800 seconds. Stop at a failed gate; no automatic physical retry,
commit, default changes, or changes to clock/precision/memory organization.

## Retained result (2026-09-11)

The resumed `fixed-link-local-twiddles-test-fix` trial finished at 15:09 JST.
All nets routed with zero routing errors; minimum hold slack was 0.000 ns.
The strict post-route gate correctly rejected setup timing: this is a retained
partial improvement, **not a timing-closed 200 MHz hardware image**.

| Metric | Previous trial | Retained trial |
| --- | ---: | ---: |
| Overall WNS (ns) | -0.696 | -0.424 |
| Kernel WNS (ns) | -0.696 | -0.349 |
| Kernel TNS (ns) | -1601.269 | -176.029 |
| Kernel failing endpoints | 8995 | 1141 |

The overall worst path is an HBM subsystem SLR-crossing reset path. The worst
kernel path is IFFT `mux_island_25/select_local_reg` to `captured_reg[6]`:
5.358 ns data delay, including 5.133 ns routing (95.8%) and one LUT level.
Router estimates still reached timing congestion level 7 and global/short
congestion level 6; congestion is not claimed resolved.

Full-kernel synthesis used 527169 LUT, 752112 FF, 5624 DSP, 468 BRAM and
236 URAM. All 49 SGen unit tests, three link/twiddle equivalence tests,
forward/inverse XSim comparisons, four numerical/schedule variants, and the
resumed 35-test regression passed. Hardware emulation passed 16400 words.
CMUX 239, II16, batch 20852 and the checksum above were unchanged.
No board test was performed. Structural checks passed at kernel, placement
and route stages. The equivalence harness repair is documented separately in
`u280-fixed-link-test-fix.md`.

The committed checkpoint includes the accumulated scratch/frame/control
dependencies of this trial. Options remain opt-in. Its effective architecture
settings (in addition to the two options above) were:

```sh
export FPT_ARITHMETIC_PROFILE=paper-set-ii
export FPT_ACCUMULATOR_ARCH=buffered_single
export FPT_EXTERNAL_PRODUCT_MULTIPLIER=schoolbook
export FPT_COEFFICIENT_PREPROCESS_GUARD_BITS=4
export FPT_COEFFICIENT_SCRATCH_CONTROL=grouped
export FPT_COEFFICIENT_SCRATCH_READOUT=wide
export FPT_FLOORPLAN=A
export FPT_HARD_FLOORPLAN_MODE=coefficient_slr0_narrow
export FPT_U280_COEFFICIENT_LOCALITY=1
export FPT_U280_COEFFICIENT_SLR0_NARROW_LINK=1
export FPT_U280_EP_FINAL_BANK_TILE_LANES=6
export FPT_U280_EP_ROW_CONTROL_TILE_LANES=6
export FPT_U280_PAIRED_INVERSE_SLR_OUTPUT=0
export FPT_SGEN_FORWARD_BOUNDARY_REGISTERS=2
export FPT_SGEN_FORWARD_MUX_CONTROL_MAX_BITS=0
export FPT_SGEN_FORWARD_PARTITION=stage2spill
export FPT_SGEN_FORWARD_PERMUTATION_ARCH=commutator_tiles
export FPT_SGEN_FORWARD_PRECOMPUTE_ROM_ADD_SUB=0
export FPT_SGEN_FORWARD_RADIX2K_MDC=1
export FPT_SGEN_FRAME_CONTROL=token
export FPT_SGEN_INVERSE_MUX_CONTROL_MAX_BITS=240
export FPT_SGEN_INVERSE_MUX_ISLAND_BITS=120
export FPT_SGEN_INVERSE_RADIX2K_MDC=0
export FPT_SGEN_PRESERVE_PARTITION_REGISTERS=1
```

Large generated RTL, logs, source archives and checkpoints remain local under
`build/fixed-link-local-twiddles-test-fix/`; they are not committed.

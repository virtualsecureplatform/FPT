# IFFT selector / EP local final-bank trial

Comparator: the fully routed `build/coefficient-slr0-narrow/routed.dcp`, from
the checker-only recovery completed 2026-09-09. Kernel setup: WNS -0.787 ns,
TNS -3324.694 ns, 10044 failing endpoints; hold clean. The routed source is
preserved in `build/local-control-islands/routed-baseline-source.tar.gz`.
Do not overwrite that run or work in the root FPT worktree.

## Switches and unchanged behavior

- `FPT_SGEN_INVERSE_MUX_CONTROL_MAX_BITS=240` enables bounded IFFT selector
  copies; default 0 retains old emission. The existing forward switch stays 0.
  Registered bit selections and aliases are supported. Copies sample the
  predecessor at the original final edge; no selector/data stage is added.
  Only narrow control FFs receive KEEP/DONT_TOUCH/SHREG_EXTRACT attributes.
- `FPT_U280_EP_FINAL_BANK_TILE_LANES=6` selects
  `externalProductFinalBankTileLanes=6`; default 0 keeps the old final banks.
  It requires the rotating serialized synchronous accumulator. Each group and
  component has ten six-lane slices and one four-lane remainder at U280 shape.
  Group zero stays UltraRAM (108 blocks), group one stays distributed RAM.
  Each of the 44 banks owns its write/read controls and distributed read-address
  registers. Write controls sample pending-product values at the existing commit
  edge; read controls sample drain next-state values at the existing drain edge.
- The payload commit/feedback pipeline, multipliers, two-lane output selectors,
  200 MHz clock, II16, arithmetic precision, SLR assignments, commutators, digit
  links, and all external interfaces are unchanged.

Forward RTL is byte-identical to the routed comparator. Disabled inverse RTL
has an identical body; only two daily SGen version-banner comments differ
(`26.9.8` versus `26.9.9`). Neither version comment is a functional change.

## Validation and run

Source `build/local-control-islands/configuration.sh` in the compressed-coefficients
worktree. SGen generation uses host Java 21; Verilator uses the existing 5.050
Apptainer image. `ExternalProductLocalBankSpec` compares every observable cycle
against the old accumulator for six-lane slices, two/four-lane remainders,
overlapping transactions, gaps, and reset/restart. `InverseControlEquivalenceSpec`
compares full-width IFFT outputs and latency on/off, with signed corner/random
inputs, continuous frames, gaps, and reset. Existing numerical/schedule tests
must retain CMUX 238 cycles, II16, batch 20851 cycles, and SHA-256
`b02dc10d50e4111bd5e4b79993b355942fd58b2d6d758d40bc544901f38f372e`.

Focused Vivado synthesis checks four physical bank shapes and the complete
IFFT. The initial check preserved all 284 IFFT copies, with maximum selector
fanout 240 and maximum predecessor fanout 128. The six-lane distributed bank
has maximum control fanout 416; UltraRAM slices use five/four blocks for
360/240-bit widths respectively.

`launch-monitored.sh` in the trial directory runs one frozen regression,
hw_emu, synthesis and implementation sequence. It preserves source/RTL hashes,
requires the 16400-word hw_emu pass, and rechecks controls, 96 commutator tiles,
registered links, and unchanged 5624 DSP / 468 BRAM / 236 URAM allocation on the
full kernel. LUT/FF counts are diagnostics, not an arbitrary aggregate gate.
Placed/routed checkpoints are saved before strict checks; all failing setup
endpoints (up to 30000, with reported count) are captured before timing rejection.
Status logging runs every 1800 seconds and stops at driver completion.

Full acceptance requires legal routing and clean setup/hold at 200 MHz. A
partial result requires legal routing, clean hold, unchanged cycle performance,
kernel WNS >= -0.787 ns, TNS > -3324.694 ns, and fewer than 10044 failing kernel
endpoints. Compare endpoint categories to the routed baseline, not merely the
single worst path. No automatic retries or commits; retain only a qualifying,
reviewed result. FFT-front, coefficient and reset bottlenecks remain outside
this trial, so closing 200 MHz is not assumed.

# FPT Set I reproduction: evidence and remaining work

The reference is the local `../2022-1635.pdf`, Tables 1–4 and Sections 5.1–5.2.
The selected implementation is the opt-in backend described in
[`vitis/paper_streaming/README.md`](../vitis/paper_streaming/README.md), with
profile `paper_set_i_streaming_tf26guard4acc40_raw_bram128_candidate.json`.
Legacy Chisel/source7 flows are separate experiments.

## Architecture and arithmetic

| Property | Paper Set I | Current selected backend |
| --- | --- | --- |
| Parameters | n=586, N=512, k=2, two base-256 levels | Same |
| Forward / inverse complex streaming lanes | 128 / 64 | Same |
| CMUX initiation interval / feedback budget | 12 / 144 cycles | Same schedule target; covered by RTL regressions |
| Batch size | 12 | 12 |
| Key coefficients | 26-bit Q7.19 | 28-bit Q7.21 |
| Final forward FFT format | 29-bit Q15.14 | 32-bit Q17.15 |
| Final inverse FFT format before normalization | 29-bit Q23.6 | 32-bit Q23.9 |
| External interface | XRT RTL kernel, resident HBM keys | XRT RTL kernel, four grouped key ports and separate input/output ports |
| Batch queueing | `ap_ctrl_chain` and next-batch prefetch | One bounded multi-frame job, next-input preparation overlaps compute |

The wider arithmetic is deliberate: the isolated 36-point training sweep
rejects all tested 29-bit transform geometries under the current model and
ordinary key generation. The selected 32/28/40-bit point matches the existing
model bit for bit. Ten geometries survived the finite training sweep; this
does not qualify alternative formats or establish a rare-error probability.
Any arithmetic change needs independent confirmation and new RTL evidence.

The 128-lane BRAM state implementation replaces more distributed storage than
the 112-lane candidate without changing latency. Explicit DSP pair sums and
split complex products reduce LUT pressure. The experimental fused accumulator
rounding increased scalar LUT cost and is not selected. MM2S store-and-forward
buffers are disabled after validation with the actual generated vendor IP.

## Resource evidence

| Complete kernel metric | Paper | Working admission limit | Current post-opt report |
| --- | ---: | ---: | ---: |
| Total LUTs, including LUTRAM/SRL | 526,000 | 631,200 | 618,870 |
| FFs | 916,000 | 1,099,200 | 774,396 |
| DSPs | 5,494 | 6,592 | 6,578 |
| BRAM36 equivalents | 505 | 606 | 570 |

Paper LUT/FF counts are rounded thousands. Current values are exact Vivado
counts for the complete kernel hierarchy, including DMA and control, after
platform `opt_design`. They exclude static platform logic. The independent
standalone synthesis audit reported 623,726 LUTs, 779,211 FFs, 6,578 DSPs,
and 570 BRAM equivalents. Both resource gates passed. Final routed counts
are still required. The previous 112-lane complete kernel had 633,109 LUTs
and was rejected; its platform run was cancelled, preserving checkpoints.

## Verification evidence

- `build/paper-streaming-contract/bram128-resource-audit-v1/kernel_resources.tsv`:
  complete standalone resource audit with no unresolved kernel black boxes.
- `build/paper-streaming-contract/platform-hw-bram128-nosf-v2/kernel_post_opt_resources.tsv`:
  actual optimized platform kernel resource gate passed.
- `build/paper-streaming-contract/encrypted-dma-engine586-bram128-rekey-rtl-v1/results.json`:
  full 586-step regression; 61,500 output words, two ordinary keys, 48 distinct
  inputs, stalls/reset, and consecutive jobs without a global reset.
- `build/paper-streaming-contract/geometry-screen-training-v1/results.json`:
  all 36 geometries, eight training keys, 64 cases per geometry; selected
  arithmetic retained, with no alternative RTL promotion.
- `build/paper-streaming-contract/board-distinct-fixtures-seeds81-112-v1/manifest.json`:
  32 ordinary keys and 4,224 distinct encrypted inputs passed fixed-model and
  independent reference checks, with unchanged bound inputs.
- `build/paper-streaming-contract/board-distinct-loader-all32-v1/results.json`:
  all 32 keys passed host loader/extraction/raw-input checks and 4,096-frame
  cycling checks. No board execution is implied.

## Open gates

The automatic-placement build `platform-hw-bram128-nosf-v3` failed after detail
placement because the SLR0–SLR1 cut required 25,478 nets against 23,040 SLLs.
It passed optimization and the complete-kernel resource gate; no routed timing
result was produced. An earlier warning on SLR1–SLR2 reported 16,808/23,040
SLLs and was not the terminal failure.

The next candidate uses `--floorplan pipeline-slrs` to co-locate MAC and key
cache in SLR2, final forward stage and inverse transform in SLR1, and early
forward stages plus frontend/state in SLR0. This avoids placing the wide
forward-output/MAC interface across two SLR boundaries. Capacity was queried
from the original platform pblock ranges on the actual part: 2,664/2,784/2,856
DSP sites are available in SLR0/1/2; the selected arithmetic groups use
2,192/2,082/2,304. This is a placement candidate, not proof of a legal cut or
timing closure. RTL, arithmetic formats, pipeline latency, resource caps and
strict qualification gates are unchanged.

The first coarse pipeline partition, `platform-hw-bram128-pipeline-slrs-v1`,
resolved all 11 hierarchy assignments and fit the actual platform pblocks,
but failed early placement with 34,439 required SLLs at SLR0–SLR1. Its
failed-placement checkpoint and reports were saved, and the original error
was propagated unchanged. The small SLL counts in that checkpoint's SLR
utilization report cover only logic already placed before failure; they do
not supersede the placer's required-cut count.

An existing alignment register bank under `arithmetic/synchronous_key` is
outside the FFT and MAC submodules. Netlist inspection found exactly 8,197
FDREs: 8,192 spectrum payload bits, four context bits, and one valid bit.
The second candidate additionally placed this bank beside the MAC in SLR2
to prevent a wide detour through an unconstrained SLR. Full-platform-checkpoint
validation passed, with all 8,197 existing registers assigned and all groups
within the platform pblock capacities. Full placement nevertheless failed with
34,440 required SLLs at SLR0–SLR1 versus 23,040 available, essentially unchanged.
Its failed checkpoint is preserved. A read-only checkpoint property audit
(`pipeline-slr-constraint-audit-v1`) confirmed all three SLR pblocks were hard
and the selected FFT, inverse and MAC hierarchies had the intended assignments.

The next candidate keeps all four forward FFT stages and their gather buffer
in SLR0, puts the input twist, frontend, state and inverse FFT in SLR1, and
retains MAC, key cache and the existing alignment bank in SLR2. This avoids
splitting the FFT internally. DSP totals are 2,250 / 2,024 / 2,304 before
platform occupancy; all fit the existing pblock capacities. These totals do
not prove the physical cut. Checkpoint assignment validation v5 passed with
all 11 hierarchy groups and 8,197 alignment registers, unchanged input hashes,
and a frozen hook matching the full build. Actual pblock LUT totals including
platform occupants are 143,571 / 190,276 / 295,076, all within capacity.
The full `platform-hw-bram128-pipeline-slrs-v3` build passed optimization and
the complete resource gate, but placement required 25,984 SLLs at SLR0–SLR1
versus 23,040 available. The reduction of 8,456 from v2 is real, but v3 still
does not fit. Failed-placement diagnostics are being preserved.

The current `platform-hw-bram128-pipeline-slrs-v4` candidate swaps those first
two groups: twist/frontend/state/inverse in SLR0, all forward FFT stages and
their gather buffer in SLR1, and MAC/cache/alignment in SLR2. The forward FFT
is then adjacent to both twist and MAC, avoiding its former two-boundary path
to the MAC. DSP assignments are 2,024 / 2,250 / 2,304 and fit the unchanged
platform capacities. Actual floorplanning now reports 21,522 required SLLs
at SLR0–SLR1 out of 23,040 available (93.4%), a reduction of 4,462 from v3.
Vivado advanced into partition-driven placement. The tool warns that this
high connectivity may affect performance; complete placement, legal routing
and timing remain unproved. This build uses
unchanged RTL and the same verified XO; no latency or arithmetic change is
introduced.

V4 subsequently completed legal placement, but its saved pre-route timing
report has kernel-clock setup slack -20.826 ns. The worst path is reset through
the nested ready/fault/start network to sample capture: 25.450 ns data delay,
of which 24.793 ns is estimated routing, with repeated SLR crossings. All 50
worst setup paths in the independent placed-checkpoint diagnosis originate at
the platform reset register. The congestion report also identifies MAC-heavy
windows, so fixing launch control alone does not establish routability.

V4's subsequent physical optimization completed, with its last reported
estimated setup slack -8.357 ns, but routing failed initialization with
`Route 35-5`: vertical wire utilization was 94.00%. The router also reported
at least 3,216 CLBs with high pin utilization. This attempt has no legal
routed result; the improved pre-route estimate does not establish timing
closure. Preserve its placed checkpoint and congestion reports as the
comparison for the registered-launch candidate.

The next RTL candidate registers cache readiness beside the cache in SLR2,
then registers the core launch credit and its output-bank selection in SLR0.
Credits are consumed on start and remain fault-gated. The DMA engine enables
this option; legacy direct raw/keyed instantiations retain their default
combinational interface. Arithmetic and its internal pipeline are unchanged,
but launch latency/spacing changes. The full 586-step encrypted two-key
stall/reset regression passed in `encrypted-dma-engine586-launch-credit-rekey-rtl-v1`:
61,500 exact output words, 48 distinct inputs, two keys, and five completed
batches. Steady launch spacing is 84,480 cycles, one cycle above the prior
design, giving about 28.409 PBS/ms at an assumed 200 MHz. This is simulation,
not routed or board throughput. `kernel-launch-credit-xo-v1` packaged the
new RTL successfully. The fresh full-platform build
`platform-hw-bram128-launch-credit-v1` has completed `synth_design` with zero
errors and zero critical warnings and saved its synthesized checkpoint.
Its complete kernel IP synthesis report has 620,388 LUTs including memory,
776,003 registers, 6,578 DSPs, and 570 BRAM equivalents (310 RAMB36 plus
520 RAMB18), with no black boxes. These fit the agreed limits at synthesis;
complete post-optimization resource limits and physical timing remain
unverified. The new
full-platform build also places the sequencer explicitly in SLR0 to avoid
the reset detour observed through SLR1. Original physical/timing gates remain
required before any board execution.

The launch-credit v1 complete post-optimization resource gate subsequently
passed: 618,864 LUTs, 774,396 registers, 6,578 DSPs, and 570 BRAM equivalents.
Its placement hook stopped because it searched for a slash hierarchy at the
launch generate block; Vivado instead uses dotted leaf names. The corrected
selector filters FD flip-flops and still requires exactly three bits. A
Vivado query against the synthesized checkpoint passed for cache_ready,
ready_bank, and ready. `platform-hw-bram128-launch-credit-v2` now relinks the
same verified RTL/XO with this corrected hook. V1 did not reach placement;
v2's complete post-optimization resource gate also passed with the same
counts. Its corrected full-platform hook selected all three launch registers:
cache_ready in SLR2, ready_bank and ready in SLR0. The assignment reports
contain all 12 hierarchy groups and 8,197 alignment registers. These are
constraint assignments; legal placement and routed timing remain unverified.

Launch-credit v2 subsequently completed `place_design` successfully and saved
`kernel_floorplan_placed.dcp`. The completed 5 ns kernel-clock report has
setup WNS -9.056 ns and hold WHS -0.309 ns; congestion remains high. Independent
read-only diagnosis completed with unchanged inputs. The worst setup path
starts at MAC fault and ends at keyed protocol_fault, traversing SLR2 to SLR0
to SLR2 to SLR1, with 13.518 ns data delay (12.853 ns estimated routing).
The next RTL candidate omits the keyed rejected-start check only when the raw
wrapper already gates its child start with readiness. Raw start_ready includes
keyed start_ready, so a rejected child start is unreachable in that connection.
The raw wrapper's public rejected-start check and the keyed stream-error
checks remain; direct keyed users keep the check by default. Arithmetic and
latency are unchanged. Full encrypted regression is running in
`encrypted-dma-engine586-qualified-start-rekey-rtl-v1`; this candidate is not
yet physically qualified. That full encrypted regression subsequently passed
with unchanged inputs. The frozen v2 implementation continues separately.

The completed congestion diagnosis still identifies MAC-heavy windows. An
isolated U280 synthesis comparison in `mac-accumulator-lutram-probe-v1` measured
721 LUTs / 829 registers for one register-backed MAC lane versus 734 LUTs /
509 registers for distributed-RAM accumulator slots, both using six DSPs.
Synthesis setup slack at 5 ns was 1.923 versus 1.924 ns; neither result is
routed timing. The measured per-lane change is +13 LUTs and -320 registers.
A linear 384-lane projection is +4,992 LUTs and -122,880 registers, not a
complete-kernel resource result. The RTL now requests distributed RAM for
these four slots, preserving read/write behavior, first-row initialization,
arithmetic, and latency. The combined candidate's full encrypted regression
passed in `encrypted-dma-engine586-lutram-accum-rekey-rtl-v1`, with unchanged
source snapshots. Fresh XO packaging in `kernel-lutram-accum-xo-v1` also
passed. Full-platform implementation is now running in
`platform-hw-bram128-lutram-accum-v1`, reusing that source-verified XO and
the corrected pipeline-SLR placement hook. Complete resources, congestion
improvement, and routed timing must be measured again.

The earlier frozen launch-credit v2 candidate completed post-placement
physical optimization with estimated setup WNS -5.645 ns and entered
`route_design`. This estimate still fails 5 ns timing; it is not a routed
result. Its source snapshot predates the redundant-check removal and
distributed-RAM accumulator changes. The router subsequently rejected this
candidate with `Route 35-5`: vertical-wire utilization was 85.35%, with at
least 1,882 CLBs having high pin utilization. This is lower than V4's 94%
and 3,216 CLBs, but both candidates failed routing. The combined distributed-RAM
candidate remains in synthesis; its routability is unverified.
Its synthesis optimization completed with zero errors and zero critical
warnings. The primitive summary reports 30,720 RAM16X1D cells for the
accumulator storage and 653,110 FFs (652,806 FDRE plus 304 FDSE), compared
with 776,003 FFs in the earlier launch-credit synthesis. Final utilization
and the complete post-optimization resource gate are still pending; this
primitive summary does not establish total LUT usage or routed timing.
The subsequent complete kernel synthesis utilization report measures 624,212
total LUTs (558,932 logic, 64,032 distributed RAM, 1,248 shift-register LUTs),
653,110 FFs, 6,578 DSPs, and 570 BRAM equivalents. These fit the agreed caps
at synthesis, with 6,988 LUTs of margin. Relative to launch-credit synthesis,
this is +3,824 LUTs and -122,893 FFs. The full-platform post-opt resource gate,
placement, routing, and timing remain unverified for this candidate.
The full-platform `opt_design` subsequently completed successfully and its
complete kernel resource gate passed: 622,689 total LUTs (557,514 logic,
64,032 distributed RAM, 1,143 shift-register LUTs), 651,503 FFs, 6,578 DSPs,
and 570 BRAM equivalents. This leaves 8,511 LUTs below the cap. Relative to
the frozen launch-credit v2 post-opt result, the change is +3,825 LUTs and
-122,893 FFs. Placement setup has started; congestion and timing benefits
remain unverified. Factory-default unwaived DRC is still required after route.
This candidate subsequently completed `place_design` with zero errors.
Its completed 5 ns kernel-clock report has setup WNS -17.155 ns and hold
WHS -0.303 ns. The worst setup path runs from MAC fault to an overlapped
exponent-bank ready register: 21.905 ns data delay, including 21.338 ns
estimated routing (97.4%), through 12 LUT levels. The placer still reports
high congestion. The FF reduction therefore does not establish improved
placed timing or routability. A separate read-only diagnosis is running in
`placed-timing-diagnosis-lutram-accum-v1`, bound to the completed placed
checkpoint. Full-platform implementation continues separately.

Tracing that placed path shows the remaining combinational return through
the DMA sequencer's compute_ready-to-compute_start connection. The next RTL
candidate registers a single pending compute request in the sequencer.
The launch counter and bank stay unchanged until the request fires; a pending
request suppresses another grant, and reset/non-RUN state clears it. Raw-engine
readiness and fault checks remain on the actual launch edge. Direct sequencer
users retain the combinational default; the DMA engine selects the registered
mode. This introduces one cycle between observing readiness and launching;
the actual encrypted launch period and correctness are being checked in
`encrypted-dma-engine586-registered-request-rekey-rtl-v1`. Fresh packaging is
running in `kernel-registered-request-xo-v1`. Neither functional validation
nor a physical timing improvement is established yet for this new candidate.
The registered-request full encrypted regression subsequently passed all
61,500 words across two keys and five frames, including stalls, reset,
restart and status draining, with unchanged source snapshots. Measured steady
launch spacing is 84,481 cycles, one above the previous 84,480-cycle period;
the cycle-derived rate at an assumed 200 MHz remains about 28.409 PBS/ms.
XO packaging also passed. Full-platform implementation is now running in
`platform-hw-bram128-registered-request-v1`, using that verified XO and
regression. Its complete resources and physical timing remain unverified.
Its complete synthesis utilization report subsequently measured 624,159
total LUTs (558,879 logic, 64,032 distributed RAM, 1,248 SRLs), 653,111 FFs,
6,578 DSPs, and 570 BRAM equivalents, with no black boxes. All synthesis
resource caps fit, leaving 7,041 LUTs of margin. Relative to the preceding
distributed-RAM synthesis this is -53 LUTs and +1 FF. Full-platform post-opt
resources and physical timing still require this candidate's own gates.
Its full-platform post-opt resource gate subsequently passed at 622,636
total LUTs (557,461 logic, 64,032 distributed RAM, 1,143 SRLs), 651,504 FFs,
6,578 DSPs, and 570 BRAM equivalents, leaving 8,564 LUTs below the cap.
Placement setup is running; legal placement, routing and timing remain
unverified for the registered-request candidate.

The distributed-RAM candidate's read-only diagnosis completed successfully
with unchanged checkpoint inputs. Of the 50 worst kernel setup paths, 49
start at MAC fault and one at forward-twist fault. Congestion remains
MAC-dominated: a North Global level-5 window reports 88% LUTRAM, 8% FF,
100% BRAM and 100% DSP occupancy; a larger North Global level-7 window is
also reported. Lower FF usage has not removed the congestion. The frozen
distributed-RAM implementation continues in post-placement physical
optimization, separately from the new registered-request implementation.
That physical optimization completed with estimated WNS -10.041 ns, still
failing. Routing then passed its initial checks and reached global-clock
routing, reporting 483 CLBs with high pin utilization versus 1,882 for the
register-backed launch-credit v2 attempt. Global-clock routing completed
with zero overlapping nodes. This is evidence of reduced pin congestion
and progress beyond the previous early route rejection, not a completed
route or timing qualification. The router is updating timing; the new
registered-request candidate remains in synthesis.

The host rebooted at 08:26 on November 5, interrupting automatic-floorplan
`platform-hw-bram128-nosf-v2` during placement
initialization. Its post-opt resource evidence remains valid, but it produced
no routed timing result. The interruption record is preserved alongside the
unchanged v2 manifest. V3 reuses the same verified XO and records launcher PID,
boot ID, and build phase before linking.
The first 128-lane platform run completed optimization but stopped because
the old report parser expected the standalone table layout. The repaired
named-column parser accepts the platform PR table and still rejects malformed
totals and excess resources. The second run reuses the identical source-verified
XO, with all 36 HDL members checked; arithmetic did not change.

Completion requires a legal full-platform route at 5 ns with nonnegative
setup and hold slack, the original route/DRC gate, independent factory-default
unwaived DRC, complete routed resource counts, and bound image/checkpoint
hashes. The generated Vitis pre-place script lowers HDPR-5 severity; the
independent audit explicitly restores its factory enabled Error setting.
Platform-default severity changes cannot establish qualification.

Then the actual U280 must match all encrypted golden outputs with the keys
resident in HBM and sustain at least 28.4 PBS/ms. The simulated compute-start
spacing is 84,481 cycles, compared with the ideal 586×144=84,384 cycles.
At 200 MHz its cycle-derived rate is about 28.409 PBS/ms, leaving little
margin. Actual kernel cycles, launch time, transfer time, and repeated versus
distinct input coverage must be reported separately. Neither routing nor board
throughput is proved by this cycle calculation.

Latest observation (2026-11-06 JST): the registered-request build is in
partition-driven placement. The placer requires 21,530 of 23,040 SLLs at
the SLR0–SLR1 boundary; this fits the net-count capacity check but does not
prove routing or timing. Its complete post-opt kernel resource gate passed
with 622,636 LUTs, 651,504 FFs, 6,578 DSPs and 570 BRAM equivalents. The
earlier distributed-RAM build remains live in routing Phase 3.4 Initial
Net Routing after a congestion warning. Neither build has a completed
qualifying route or board result. See progress-checkpoint-v140.json.

The distributed-RAM candidate subsequently failed initial routing with
`Route 35-3303`: very high congestion, after entering Phase 3.8. This is
a routing failure, despite the reduced high-pin CLB count. The current
registered-request candidate remains live in detail placement; its route
and timing outcome are still unproved. Failure observation is preserved
in progress-checkpoint-v141.json.

The registered-request candidate reached post-placement optimization with
intermediate estimated WNS -10.039 ns and TNS -132,208.428 ns. This
still fails setup timing and is neither completed placed timing nor routed
timing. The preceding distributed-RAM build is now closed with link exit
code 1 and unchanged snapshot inputs.

The registered-request placed diagnosis completed with unchanged inputs.
All 50 worst kernel setup paths originate at forward-twist fault. Completed
placed WNS is -10.039 ns and WHS -0.344 ns. The worst path reaches the
context-index enable through 11 logic levels, with 14.466 ns data delay
(13.856 ns estimated routing). A further accepted-launch register and bank
register have been implemented in KeyedBlindRotate under REGISTER_LAUNCH;
pending suppresses launch-credit replenishment and contributes to busy.
Full encrypted regression is running in accepted-launch-rekey-rtl-v2; this
change is unvalidated and is absent from the frozen registered-request
hardware build, which continues physical optimization.

The initial accepted-launch version passed correctness but used the previous
captured bank for idle readiness, increasing launch spacing to about 97,000
cycles; it was not promoted. Selecting the incoming bank while idle and the
captured bank while pending restores spacing to 84,482 cycles. The corrected
full encrypted DMA regression passed all 61,500 words across two ordinary
keys, 48 distinct inputs and five repeated frames, including stalls, reset
and rekey, with unchanged frozen inputs. Its cycle-derived rate at assumed
200 MHz is about 28.408 PBS/ms; this is not board throughput. XO packaging
is running in kernel-accepted-launch-bank-xo-v1. The prior registered-request
build remains live in routing and does not contain this change.

The corrected accepted-launch XO package passed with unchanged snapshot
inputs. Full-platform implementation platform-hw-bram128-accepted-launch-bank-v1
started with launcher PID 1144133 (session 96800), reusing the source-verified
XO and corrected encrypted regression. The new floorplan requires exactly
two accepted-launch registers in SLR0. Complete resources, actual inferred
register selection, placement and routing remain unverified for this version.
The preceding registered-request router reports 582 high-pin CLBs and zero
overlapping nodes during global-clock routing; these are intermediate metrics.

The accepted-launch-bank candidate completed kernel synthesis with 624,163
total LUTs (558,883 logic, 64,032 LUTRAM, 1,248 SRLs), 653,113 FFs,
6,578 DSPs and 570 BRAM equivalents. This leaves 7,037 LUTs of synthesis
margin; post-opt resources and physical qualification remain pending.
Implementation PID 1157911 is live loading platform constraints. The prior
registered-request router PID 953353 remains live in Phase 3.8 Initial Net
Routing. Neither observation establishes routed timing or board throughput.

Read-only placed congestion analysis of the registered-request candidate
shows MAC-dominated global level-5 windows at 96–99% LUTRAM use, with
100% DSP or BRAM use in selected windows. A separate inverse-input
reordering window has 87% FF, 100% BRAM and 92% DSP use. These local
placement metrics show that reduced total FF count has not removed local
resource pressure; they are not final routed usage or evidence for the
newer accepted-launch placement. Source hash and selected rows are in
registered-request-congestion-observation-v1.json.

The accepted-launch-bank full-platform post-opt kernel resource gate passed:
622,640 LUTs (557,465 logic + 64,032 LUTRAM + 1,143 SRLs), 651,506 FFs,
6,578 DSPs and 570 BRAM equivalents. LUT margin is 8,560. The preplace
hook found exactly accepted_launch_pipeline.bank_reg and pending_reg and
assigned both to SLR0. These are actual optimized-netlist observations;
placement, routing, strict unwaived DRC and board qualification remain
pending. Hash-bound evidence: accepted-launch-bank-post-opt-observation-v1.json.

The registered-request candidate subsequently failed routing after Phase
3.11: Route 35-3 reports global congestion level 7 and Route 35-4445
terminates initial routing. It progressed further than the prior LUTRAM
run but did not achieve a routable implementation. Failure cleanup and
checkpoint output are still in progress. The newer accepted-launch-bank
candidate remains live in placer initialization, with routing unproven.
Evidence: registered-request-route-failure-observation-v1.json.

The registered-request build is now closed: link exit code 1, passed=false,
snapshot_inputs_unchanged=true. Its failed routed checkpoint passes archive
CRC validation (25 entries, 895,130,830 bytes), SHA-256
0ba9baca7c46c07ef3ca9c858915c9886dfbda4388ea3251f489ff4b13aea151.
Bound evidence: registered-request-failed-checkpoint-v1.json. The latest
accepted-launch-bank implementation remains live in placement.

The accepted-launch-bank placer reports 21,535/23,040 SLLs (93.47%)
for the SLR0–SLR1 crossing and has entered partition-driven placement.
This passes the crossing net-count capacity check, but is not final router
demand, legal routing or timing closure. Evidence:
accepted-launch-bank-slr-capacity-observation-v1.json.

The accepted-launch-bank candidate entered post-placement physical
optimization with intermediate estimated WNS -7.965 ns and TNS
-157,847.287 ns. Setup timing still fails. This is not completed placed
or routed timing, and the remaining worst path is not yet diagnosed.
Evidence: accepted-launch-bank-placement-intermediate-v1.json.

The accepted-launch-bank completed placed report has WNS -7.965 ns,
TNS -157,812.531 ns, WHS -0.308 ns and THS -471.382 ns. Its worst setup
path is MAC fault to the input DataMover skid-buffer enable: 12.532 ns
data delay, 0.675 ns logic and 11.857 ns estimated route, through ten LUT
levels. The placed checkpoint archive is validated, SHA-256
a111eec05b0a63dfd46ba389e5bbdbcbd9e3814ccad63dfdcf7d699122344549.
Read-only diagnosis is running in placed-timing-diagnosis-accepted-launch-bank-v1.

A subsequent RTL candidate disables decoder same-cycle refill only in the
DMA engine (InputDecoder REFILL_ON_POP=0; default remains 1). This removes
the downstream pop/readiness path from DMA input_ready, using the existing
registered holding flag instead. It adds one cycle between input beats,
without changing payload storage or arithmetic. Full encrypted regression
is running in encrypted-dma-engine586-input-ready-cut-rekey-rtl-v1
(session 53366); correctness and steady spacing are not yet validated.
This change is absent from the frozen accepted-launch-bank physical build.

The input-ready-cut encrypted regression passed with unchanged frozen
inputs: 61,500 exact words, 48 distinct inputs, two keys and five repeated
frames under stalls/reset/restart/status drain. Launch spacing remains
84,482 cycles (first launch 14,229/14,230 versus 13,756 before the input
refill change). XO packaging started in kernel-input-ready-cut-xo-v1.
The accepted-launch-bank placed diagnosis closed successfully with
unchanged inputs; all 50 worst setup paths are MAC fault to input
DataMover skid-buffer enables. The frozen physical build continues.

Input-ready-cut XO packaging passed (closed, exit 0, unchanged snapshot
inputs). Full-platform implementation started in
platform-hw-bram128-input-ready-cut-v1, reusing the source-verified XO and
full encrypted regression. Its resource/placement/timing/route gates remain
pending. The preceding accepted-launch-bank build continues physical
optimization. Its completed congestion diagnosis still shows global
level-5 MAC windows with 100% DSP and up to 92% LUTRAM use; the input
ready cut addresses the critical control path, not those local capacities.

The input-ready-cut complete kernel synthesis uses 624,164 total LUTs
(558,884 logic + 64,032 LUTRAM + 1,248 SRLs), 653,112 FFs, 6,578 DSPs
and 570 BRAM equivalents (310 RAMB36 + 520 RAMB18; no URAM). It fits
the synthesis caps with 7,036 LUTs margin. Post-opt and routed resources
and timing remain pending. Source-bound evidence:
input-ready-cut-synthesis-resources-v1.json.

The input-ready-cut full-platform post-opt resource gate now passes:
622,641 total LUTs (557,466 logic + 64,032 LUTRAM + 1,143 SRLs),
651,505 FFs, 6,578 DSPs and 570 BRAM equivalents. LUT margin is 8,559.
The placement hook also identifies both accepted-launch registers in SLR0.
Placement and routed timing remain pending; the preceding accepted-launch-bank
build is still actively routing under congestion warnings. Source hashes and
counts are recorded in input-ready-cut-post-opt-observation-v1.json.

The input-ready-cut placer estimates 21,535 / 23,040 SLLs (93.47%)
across SLR0–SLR1, matching accepted-launch-bank. This fits crossing capacity
but remains highly utilized; it is not a routed-demand or timing result.
Partition-driven placement is running. Evidence: input-ready-cut-slr-capacity-observation-v1.json.

The preceding accepted-launch-bank build has now failed initial routing in
Phase 3.8: Route 35-3303 reports very high congestion and Route 35-3 reports
global congestion level 7 (not routable). Vivado cleanup/checkpoint export
is still pending at this observation. The input-ready-cut build remains
live in detailed placement; its route outcome is unknown. Evidence:
accepted-launch-bank-route-failure-observation-v1.json.

The input-ready-cut build completed place_design with zero errors. Its fully
placed report fails 5 ns: WNS -9.137 ns, TNS -194485.094 ns (139546 endpoints),
WHS -0.326 ns, THS -463.701 ns (8963 endpoints). The worst setup path is
MAC fault to prepare lookup-memory BRAM WEBWE, 13.315 ns data delay
(0.416 ns logic + 12.899 ns estimated route), seven LUT levels. Source-bound
evidence: input-ready-cut-placed-timing-observation-v1.json.

A new DMA-only REGISTER_PREP_FAULT option registers the global fault
observation used by automatic preparation start. Public fault remains immediate;
fault-free handshakes and preparation timing are unchanged. A fatal fault may
permit one last internal preparation start before the local stop takes effect.
The option defaults off outside DMA. Full encrypted regression
encrypted-dma-engine586-prep-fault-cut-rekey-rtl-v1 is running; this change is
not yet validated or physically qualified. The preceding physical build remains
frozen and continues implementation.

The preparation-fault cut passed full encrypted DMA regression: 61,500 words,
48 distinct inputs, two keys, five frames, stalls/reset/restart/status drain;
source and frozen snapshot hashes are unchanged. Both job cycle logs match
input-ready-cut exactly: 280210/195766 total cycles, first outputs 14229/14230,
steady batch period 84482. XO package kernel-prep-fault-cut-xo-v1 passed.
Full-platform platform-hw-bram128-prep-fault-cut-v1 is launched with a strict
selector requiring exactly one preparation-fault FD register in SLR0.
Its synthesis/resources/placement/timing/route gates remain unproved.
The input-ready-cut read-only diagnosis closed PASS with unchanged inputs;
all 50 worst setup paths originate at MAC fault and reach preparation/decoder
controls. Physical implementation of that frozen preceding build continues.

Completed input-ready-cut congestion diagnosis shows MAC-dominated global
windows through level 7. Level-5 north/south windows use 100% DSP and BRAM,
with 78%/74% LUTRAM respectively. The preparation-fault register addresses
control delay, not this local arithmetic capacity pressure. Evidence:
input-ready-cut-congestion-observation-v1.json.

Input-ready-cut physical optimization completed with zero errors and routing
is live. Intermediate router WNS is -6.544 ns. Estimated SLL routing demand
is 41016/23040 (178.02%) across SLR1–2 and 28634/23040 (124.28%) across
SLR0–1, with a 291% peak column. These are router demand estimates, not
final routed usage or a terminal result. Evidence:
input-ready-cut-route-demand-observation-v1.json.

Preparation-fault-cut complete kernel synthesis fits: 624162 total LUTs
(558882 logic + 64032 LUTRAM + 1248 SRLs), 653117 FFs, 6578 DSPs,
310 RAMB36 + 520 RAMB18 = 570 BRAM equivalents, no URAM. LUT margin
is 7038. synth_design completed with zero errors/critical warnings.
Post-opt resources, the new SLR0 register selector and routed timing remain
pending. Evidence: prep-fault-cut-synthesis-resources-v1.json.

Preparation-fault-cut opt_design completed successfully. The full-platform
post-opt kernel resource gate passed: 622639 total LUTs (557464 logic +
64032 LUTRAM + 1143 SRLs), 651510 FFs, 6578 DSPs, and 570 BRAM
equivalents. LUT margin is 8561. The strict preparation-fault selector
found exactly one stopped_reg FD and assigned it to SLR0; this is a
floorplan assignment, not completed placement or timing evidence.
Placement, routing, strict independent DRC, and board qualification remain
pending. The preceding input-ready-cut run is live in route phase 3.4
after a congestion warning.

Preparation-fault-cut global placement reports 21532/23040 SLLs
(93.45%) across SLR0–1, within the placer crossing capacity, with a
high-connectivity warning. Partition-driven placement is live. This
is not final routing usage or timing closure. Evidence:
prep-fault-cut-slr-capacity-observation-v1.json.

Input-ready-cut router emitted ERROR Route 35-3303 after routing pass 3.8:
initial routing failed due to very high congestion. Worker cleanup and
terminal link outcome remain pending; preserve this run and its checkpoints.
Evidence: input-ready-cut-initial-routing-failure-observation-v1.json.
The preparation-fault-cut build remains live in detail placement.

Input-ready-cut route_design is now confirmed failed: Route 35-3 reports
global congestion level 7 and Route 35-4445 terminates routing. Initial
routing elapsed about two hours. Worker cleanup and failed-checkpoint
validation are pending. Evidence: input-ready-cut-route-failure-observation-v1.json.

Input-ready-cut worker and launcher have exited; link.log confirms kernel
link failure at 15:30:34 JST. The manifest remains stale at linking.
Independent terminal evidence is input-ready-cut-terminal-link-observation-v1.json.
The failed checkpoint is CRC-verified with SHA256
042ad65b34a4d6f666b2d8cdb6658847e63e81d3361427ab4c90fc514d7d724c
(895172783 bytes); see input-ready-cut-failed-checkpoint-v1.json.
Do not restart this failed run.

## Physical restructuring (2026-11-11)

`platform-hw-bram128-prep-fault-cut-v1` placed at WNS -8.199 ns and then failed
routing with Route 35-3 (global congestion level 7), like its predecessors.
The owner lifted the latency, throughput and resource-cap constraints, so the
RTL now carries an opt-in `PHYSICAL` mode (default on in the DMA engine;
helpers in `PaperStreamingPhysical.sv`):

- a registered fault tree at every hierarchy level and a uniform three-cycle
  reset fan-out, replacing the global combinational fault/reset nets;
- registered crossing banks: twist to first forward stage (2), spectrum to
  MAC (2), MAC rows to inverse (4), plus a per-component operand bank and a
  registered drain select inside the MAC;
- fully registered stream slices on the input (1), key (4 each) and output (1)
  DataMover streams, and a registered stream-start request in the sequencer.

Arithmetic is unchanged. The full encrypted DMA regression passed in
`encrypted-dma-engine586-physical-rtl-v2` (61,500 words, two keys, stalls,
reset, rekey); steady launch spacing is 84,492 cycles (28.405 PBS/ms at an
assumed 200 MHz, cycle-derived only). The LUT admission cap was raised from
631,200 to 680,000 in `check_kernel_resources.tcl` and the board script.

The DSP budget fixes the SLR grouping (forward stages 2,250; MAC 2,304; twist
768 + inverse 1,256 against 2,664 / 2,784 / 2,856 sites), so
`--floorplan physical-slrs` keeps twist/frontend/state/inverse in SLR0, the
forward stages in SLR1 and MAC/key cache in SLR2, and assigns each crossing
bank to its side of the boundary. Build `platform-hw-physical-v1` is running;
no placement, routing or board result exists yet.

### Physical builds v1 and v2 (2026-11-12)

Both builds use `--floorplan physical-slrs` and route completely; neither meets 5 ns, so no
xclbin exists yet and nothing has run on the card.

| Build | RTL regression | Placed WNS (after phys_opt) | Routed WNS | Routed TNS | Failing endpoints | WHS |
|---|---|---|---|---|---|---|
| `platform-hw-physical-v1` | `encrypted-dma-engine586-physical-rtl-v2` | +0.002 ns | -0.994 ns | -7,718 ns | 31,255 | +0.002 ns |
| `platform-hw-physical-v2` | `encrypted-dma-engine586-physical-rtl-v5` | -0.628 ns | -6.296 ns | -179,335 ns | 105,280 | +0.001 ns |

- v1 is the first build of this kernel that the router completed (earlier builds aborted with
  Route 35-3). SLL use: 93% on SLR0-SLR1, 85% on SLR1-SLR2; the base platform alone uses
  roughly 5-7K SLLs per boundary.
- v2 added the key-cache write/read pipelines (`WRITE_STAGES`, `READ_STAGES`,
  `KEY_READ_LATENCY=2`), registers around the forward/inverse gather permutation, replicated
  reset fan-out and registered cache status. Its routed result is far worse than v1 although
  it entered routing at -0.63 ns: the failures are spread over MAC-lane-internal paths
  (38K endpoints), the inverse permutations and the state store. The outcome is governed by
  routing congestion, not by individual paths.
- Congestion: placer level 5-7 windows almost entirely inside the MAC (SLR2 at 79% LUT, 81% DSP);
  long-wire level 7 in all three SLRs.
- Moving a whole MAC component to SLR1 does not fit: DSPs (forward stages 2,250 + 768 > 2,784)
  and SLLs on SLR1-SLR2 (about 25.7K needed, 23,040 available).
- Directive trials on v2's pre-place checkpoint are in
  `build/paper-streaming-contract/physical-v2-directive-trials/` (`trial.tcl`).

## Directive trials on v2 and the near MAC group (2026-11-12)

Two place/route directive trials on `platform-hw-physical-v2`'s pre-place
checkpoint (`build/paper-streaming-contract/physical-v2-directive-trials`):

| Trial | Place directive | Route directive | Placed WNS (post phys_opt) | Routed WNS |
|---|---|---|---|---|
| v2 official | default | default | -0.628 ns | -6.296 ns |
| `altspread` | AltSpreadLogic_high | Default | -0.170 ns | -3.245 ns |
| `ssispread` | SSI_SpreadLogic_high | AlternateCLBRouting | -0.628 ns | about -5.2 ns mid-route (not finished when recorded) |

Placement nearly meets timing but routing loses 3 ns or more in every case:
directives alone do not close this netlist. Router congestion stays at level
6 (global/short) and 7 (timing).

Structural change, regression `encrypted-dma-engine586-physical-rtl-v7` (PASS),
build `platform-hw-physical-v3` (launched with `--place-directive AltSpreadLogic_high`,
result pending):

- `PaperStreamingMacNear` (in `PaperStreamingMacTile.sv`): component 0, lanes
  0..62 (378 DSP) with a private schedule copy, drain register and rounding,
  placed in SLR1 beside the forward stages. Its spectrum operands come from a
  private delay line in SLR1, and its rounded rows are merged half way down
  the MAC output pipeline, so no new wide bus crosses an SLR boundary.
- Key groups 0..6 (49 RAMB36, exactly the keys of those lanes) follow it to SLR1.
- Expected SLR2 load: about 66% LUT, 67% DSP (was 79% / 81%); SLR1 DSP about 94%.
- `KeyedBlindRotate` now registers the key-stream protocol fault before launch control.

Follow-up builds of the near MAC group (all `--place-directive AltSpreadLogic_high`):

| Build | Regression | Result |
|---|---|---|
| `platform-hw-physical-v3` | v7 | Placer: 28,248 SLLs needed SLR1-SLR2 (23,040 available). Synthesis merged the duplicate near delay line and the per-group key-write registers with their twins, so data zig-zagged across the boundary. |
| `platform-hw-physical-v4` | v9 | SLLs fit. Placer: 2,628 DSPs required in `pblock_dynamic_SLR1`, only 2,619 usable (165 sites blocked). |
| `platform-hw-physical-v5` | v10 | Near group reduced to 45 lanes / key groups 0..4 (SLR1 2,520 DSP, SLR2 2,034 DSP). Result pending. |

RTL changes after v3: `PaperStreamingKeptLanePipeline` (`dont_touch`) for the near
spectrum delay; `dont_touch` on the near schedule copy and cache command registers;
key cache `WRITE_STAGES=3` with one first-stage copy per port, one copy per side
(`NEAR_GROUPS`), and a per-group last stage; last key stream slice placed in SLR1.
The 63-lane figures above (378 DSP, groups 0..6) describe v3/v4 only.

### v5 result and the product pipeline (2026-11-13)

`platform-hw-physical-v5` placed with balanced SLRs (LUT 59% / 49% / 60%, DSP
2,024 / 2,520 / 2,038) but entered routing at WNS -0.607 ns and failed to
route (25,867 node overlaps, mid-route WNS -14 ns).

Across all attempts the router's outcome tracks the timing it starts from, not
the congestion estimate (which is nearly identical in every build):

| Run | WNS entering route | First-iteration overlaps | Routed WNS |
|---|---|---|---|
| v1 | +0.002 ns | 604K | -0.994 ns |
| v2 `altspread` trial | -0.170 ns | n/a | -3.245 ns |
| v2 | -0.628 ns | 977K | -6.296 ns |
| v5 | -0.607 ns | 998K | not routable |

v5's 1,157 failing placed paths are almost all in the forward FFT in SLR1:
stage-1 pair-sum DSP -> rounding -> stage-2 multiplier DSP input (-2.35 ns
before phys_opt), and the unregistered DSP multiply plus 8-13 logic levels
inside `PaperStreamingComplexProductRounded` (-0.6 ns).

Regression `encrypted-dma-engine586-physical-rtl-v11` (PASS), build
`platform-hw-physical-v6` (pending):

- `PaperStreamingSplitMultiply` `PIPELINE=1` under `PHYSICAL`: partial products
  are registered before the final add (one more cycle in every twiddle, twist
  and MAC product; matching delays in `TwiddleProduct` and `MacLane`).
- `LanePipeline` register after forward stages 0 and 1.

### v6 result: first fully routed build, WNS -0.709 ns (2026-11-13)

`platform-hw-physical-v6` placed with timing met (post-phys_opt WNS +0.016 ns)
and is the first build to route completely: 796K first-iteration overlaps,
fully routed, WHS +0.005 ns, but WNS -0.709 ns (TNS -2,689 ns), so the
post-route gate rejected it and no xclbin was produced.

10,735 kernel-clock paths fail, 8,187 of them with at most one logic level:
the delay is routing (5.3-5.8 ns on a single net), not logic. Largest groups:

| Paths | Path |
|---|---|
| 1,803 | cache `data_far` -> per-group `data_last` (SLR2, across 3 clock-region columns) |
| 3,520 | MAC reset copy / rounding valid -> accumulator LUTRAM `WE` |
| ~350 | MAC `drain_step` -> accumulator read -> half register |
| 744 | cache `fault` (SLR2) -> key stream slices `CE` (SLR1), combinational `load_ready` |
| 419 | twist lane pipeline SLR0 -> SLR1 crossing registers |
| rest | inverse FFT phase fan-out, state store commit, frontend metadata, DataMover skid buffers (SLR0, CLB 99% occupied) |

Regression `encrypted-dma-engine586-physical-rtl-v12` (PASS), build
`platform-hw-physical-v7` (pending):

- `PaperStreamingMacLane`: accumulator write is no longer gated by reset (the
  valid chain is reset; first-row writes initialise every slot).
- `PaperStreamingMacTile` / `MacNear`: `dont_touch` copies of the drain read
  address (bank, half, select) per component and block of 16 lanes, with the
  same next-state function as the originals (no latency change).
- `PaperStreamingKeyCache`: four distinct `data_far` copies per port, each
  feeding ~10 groups; `load_ready` / `load_start_ready` use a loader-side
  registered copy of the sticky fault.

In parallel, a routing-only trial on v6's placed checkpoint
(`route_design -directive AggressiveExplore` plus post-route phys_opt) checks
whether router effort alone recovers the 0.7 ns.

### Routing-only trial on v6: WNS -0.001 ns (2026-11-13)

Re-routing v6's placed checkpoint outside v++ with
`phys_opt_design`, `phys_opt_design -directive AggressiveExplore`,
`route_design -directive AggressiveExplore` gave a fully routed design at
WNS -0.001 ns, WHS +0.003 ns (default routing: -0.709 ns). Post-route
phys_opt did not recover the last picosecond. Once placement meets timing,
the router directive is worth 0.7 ns on this design.

`tools/build_paper_streaming_kernel.py` gained `--route-directive`. Build v7
(default routing) was stopped during synthesis; `platform-hw-physical-v8`
(pending) uses the v12 RTL with `--place-directive AltSpreadLogic_high
--route-directive AggressiveExplore`.

### v8 result: kernel clock closed, platform HBM clock -0.033 ns (2026-11-14)

`platform-hw-physical-v8` (v12 RTL, `AltSpreadLogic_high` placement,
`AggressiveExplore` routing): placed WNS +0.016 ns, fully routed, WHS
+0.005 ns, WNS -0.033 ns, TNS -0.135 ns. The gate rejected it; no xclbin.

All 11 failing paths are platform logic, not the kernel: `hmss_0` SLR-crossing
register pairs (`path_6/slice0_6`, `laguna_m_payload_i` ->
`laguna_s_payload_d`) on the 450 MHz `hbm_aclk` (2.222 ns), zero logic levels,
~1.7 ns of routing over one SLR boundary with -0.19 ns skew and 0.24 ns
inter-SLR compensation. The kernel's 5 ns clock has no failing path. The
v6 routing trial's last -0.001 ns was the same kind of path.

Post-route `phys_opt_design -directive AggressiveExplore` and
`route_design -tns_cleanup` on the routed checkpoint changed nothing.
Next trial: route v8's placed checkpoint with `hbm_aclk` over-constrained by
0.15 ns of setup uncertainty during phys_opt/route, removed for sign-off.

Over-constrained routing trial on v8's placed checkpoint (0.15 ns extra setup
uncertainty on `hbm_aclk` through phys_opt and route, removed for sign-off):
`hbm_aclk` WNS +0.010 ns, but the kernel clock fell to -0.141 ns because the
router relaxed everything to the unreachable HBM target. So the HBM crossings
can close, but need better placement rather than only router pressure.

`platform-hw-physical-v9` (pending), same v12 RTL and directives as v8:

- `place_physical_slrs.tcl` sets 0.150 ns setup uncertainty on `hbm_aclk`
  before placement.
- New `route_platform_margin.tcl` (route_design pre-hook, physical-slrs only)
  lowers it to 0.040 ns for routing.
- `check_fpt_post_route.tcl` sets it to 0 before any acceptance check, so
  sign-off uses the platform's own constraints.

### v9 result: HBM clock closed, kernel clock -0.245 ns (2026-11-14)

`platform-hw-physical-v9`: placed WNS +0.008 ns, fully routed, WHS +0.006 ns,
WNS -0.245 ns, TNS -37 ns. No xclbin. With the placement/routing margin the
platform `hbm_aclk` paths no longer fail; 441 kernel-clock paths do:

| Paths | Path |
|---|---|
| 68 | `loading_initial` -> 9 levels -> state-store BRAM `ENBWREN` (SLR0) |
| ~130 | forward stage `real_term`/`z` -> rounded add (9-13 levels) -> next pair-sum DSP |
| ~200 | forward stage 2 pair-sum DSP -> rounded add (10 levels) -> stage 3 multiplier DSP |
| ~40 | scattered one-level control paths |

Regression `encrypted-dma-engine586-physical-rtl-v14` (PASS; v13 was a syntax
error), build `platform-hw-physical-v10` (pending, same directives and HBM
margin as v9):

- `PaperStreamingRoundedAdd` `PIPELINE=1`: high parts and the low-part
  carry/rounding adjustment are registered before the high sum.
  `ComplexProductRounded` enables it for `PIPELINE=2`, used by the forward
  FFT stages only (`PRODUCT_PIPELINE=2`, latency 5).
- `LanePipeline` register after forward stage 2 as well.
- `PaperStreamingMixedCoefficientStore17`: the BRAM feedback read is
  unconditional when the half is not committing, removing reset, `write_valid`
  and `input_legal` from the block-RAM enable.

### v10 result: not routable after the forward-stage registers (2026-11-15)

`platform-hw-physical-v10`: placed WNS +0.004 ns, but `route_design` ended
with 102,366 unrouted signals and 68,707 node overlaps (`Route 35-162`,
`Route 35-2`). No xclbin. First-iteration overlaps were 1.35M against
843K-846K in v8/v9, and global/short congestion rose from level 6 to level 7.

Comparison of the placed checkpoints of v9 and v10:

| Dynamic region | v9 registers | v10 registers | v9 LUTs | v10 LUTs |
|---|---|---|---|---|
| SLR0 | 372,618 | 372,933 | 209,086 | 208,570 |
| SLR1 | 335,328 | 388,522 | 147,533 | 156,218 |
| SLR2 | 344,645 | 344,877 | 216,446 | 216,780 |

The only material difference is +53K registers and +8.7K LUTs in SLR1 from
the rounded-add operand registers (`PIPELINE=1`) and the stage-2 lane
register. The middle SLR carries all SLR0<->SLR2 traffic and cannot absorb
them. Next builds:

- v11: forward stages back to the v9 configuration (`PRODUCT_PIPELINE=1`,
  lane register after stages 0..1 only), keeping the state-store BRAM enable
  fix. Regression `encrypted-dma-engine586-physical-rtl-v15`.
- v12 (in parallel): a cheaper cut for the forward rounded adds.

v12 as launched: `PaperStreamingRoundedAdd` `PIPELINE=1` is now one kept
(`dont_touch`) fabric register on the result instead of two operand
registers, so the adder ends in a local flip-flop and the next register can
sit at the consuming DSP. Forward stages use it again (`PRODUCT_PIPELINE=2`);
the lane register after stage 2 stays removed. Regression
`encrypted-dma-engine586-physical-rtl-v16` (PASS). v11 and v12 run
concurrently.

### v11 and v12 results: routed, WNS -0.294 / -0.510 ns (2026-11-15)

| Build | Placed WNS | First-iteration overlaps | Routed WNS | TNS | Failing paths |
|---|---|---|---|---|---|
| `platform-hw-physical-v11` | -0.096 ns | 804K | -0.294 ns | -354 ns | 3,714 |
| `platform-hw-physical-v12` | n/a | 1.28M | -0.510 ns | -1,119 ns | 6,640 |

Both fully routed, WHS +0.006 / +0.005 ns, no xclbin. v12 shows that even the
smaller rounded-add register (and the latency change it implies for the
forward stages) raises SLR1 congestion as v10 did; the forward stages go back
to `PRODUCT_PIPELINE=1`. v11 printed `Route 35-447` (congestion takes
priority over timing), which v8/v9 did not, although its netlist differs from
v9 only by the store enable.

The failing paths are no longer a few deep datapaths: almost all have 0-2
logic levels and are spread over every block at -0.2..-0.3 ns (the router
relaxes all paths to the worst one). Largest groups in v11/v12:

- `FramePermute` storage clock enables from `input_valid`, reset and
  `write_phase` (fan-out 4,096), and output registers from `reading`/`read_phase`.
- MAC `read_copy` -> drained half registers.
- forward twiddle `phase` -> multiplier DSP `A`, SLR0->SLR1 lane crossing
  registers, key stream slice enables, key cache `data_first` -> `data_far`,
  platform `hmss_0` (HBM clock).

Changes for v13/v14, regression `encrypted-dma-engine586-physical-rtl-v18` (PASS; v17 stopped on a lint width warning):

- `PaperStreamingFramePermute`: per 8 lanes one kept one-hot write select and
  one read select. Storage is written whenever its select is set (the word
  under the write pointer is rewritten by the valid beat before the pointer
  moves), so the enable is one flip-flop with no `input_valid`/reset;
  output registers are unconditional.
- MAC `READ_BLOCK` 16 -> 8.
- v13 routes with `AggressiveExplore`, v14 with `NoTimingRelaxation`.

### v13 and v14 results: WNS -2.356 / -2.881 ns; SLL saturation (2026-11-16)

Both fully routed, no xclbin. Placed WNS +0.008 ns in both, but the router
went into congestion mode (`Route 35-447`, 1.33M first-iteration overlaps)
and lost 2.4-2.9 ns. `NoTimingRelaxation` (v14) did not help. The
`FramePermute` control copies and MAC `READ_BLOCK=8` are reverted: the
sources are again those of v11 (plus the unused result-register form of
`RoundedAdd PIPELINE=1`).

First-iteration overlaps are bimodal: about 800K (v6, v8, v9, v11: WNS
-0.03..-0.7 ns) or about 1.3M (v10, v12, v13, v14: unroutable or -0.5..-2.9 ns).
Small netlist changes flip the mode.

SLR crossing use in the placed v13 design (`kernel_floorplan_placed_slr.rpt`):
SLR0-SLR1 22,194 of 23,040 SLLs (96%), SLR1-SLR2 19,566 (85%); the router
estimates up to 237% demand in single laguna columns. By source:

| Crossing | SLLs |
|---|---|
| twist -> forward stages (S0->S1) | 8,192 |
| key stream slices (S0->S1) | 2,052 |
| MAC output, near pipeline (S1->S0) | 4,096 |
| spectrum -> far MAC (S1->S2) | 8,192 |
| key cache `data_first` (S1->S2) | 2,016 |
| MAC output, far pipeline (S2->S1) | 4,096 |
| platform (PCIe/DMA/firewalls/HBM), both boundaries | about 4-5K each |

The kernel never has cells beside the last two of the 16 laguna column pairs
(v11 placement), so about 20,160 SLLs per boundary are usable and SLR0-SLR1
is effectively full. A simulation probe on every lane pipeline showed all
wide buses use 31-32 signed bits, so they cannot simply be narrowed.

### ROM forward twist, v15 (2026-11-16)

The forward twist multiplies an 8-bit digit (sent as `digit<<23`) by a fixed
F=26 twiddle per lane and phase. `PaperStreamingTwistRomLane` (in
`PaperStreamingTwist.sv`, `ROM=1`, enabled by `PHYSICAL`) replaces the
multipliers with a 512x72 block ROM per lane holding the exact
`{digit*sin, digit*cos}` products, read once for each of the two digits,
then one add/subtract and the same round-to-nearest-even. Latency is
unchanged (5 cycles). An operand that is not a sign-extended `digit<<23`
raises the twist fault. Standalone synthesis of one lane: 2 RAMB36, 81 LUTs,
219 registers, no DSP.

- The SLR0->SLR1 crossing bank moves from the twist output (`TWIST_STAGES`
  2 -> 0) to the digits (`DIGIT_STAGES` 0 -> 2): 2,048 SLLs instead of 8,192.
- Floorplan: the twist joins the forward stages in SLR1.
- Kernel resources change by about -768 DSP and +256 RAMB36. The
  `bram_equivalents` admission cap is raised from the paper-derived 606 to
  832 in `check_kernel_resources.tcl` and
  `tools/run_paper_streaming_board_qualification.py`.
- Regression `encrypted-dma-engine586-physical-rtl-v19` (PASS, bit-exact).
  Build `platform-hw-physical-v15` (`AltSpreadLogic_high`,
  `AggressiveExplore`), result pending.

### v15 result: kernel clock closed; 7 platform HBM paths and one hold path (2026-11-16)

`platform-hw-physical-v15`: fully routed, WNS -0.066 ns, WHS -0.004 ns, no
xclbin. SLR0-SLR1 SLL use fell from 96% to 67% (15,365), SLR1-SLR2 is 85%
(19,561); first-iteration router overlaps fell from about 800K to 476K.
Every kernel-clock path meets setup. Failing: 7 `hmss_0` `path_6/slice0_6`
SLR1<->SLR2 register pairs on `hbm_aclk` (all marked `USER_SLL_REG`, but the
placer put them in SLICE sites; no laguna register is used anywhere), and one
kernel hold path at -0.004 ns.

Trial on v15's routed checkpoint: with the `hbm_aclk` implementation margin
removed, one plain incremental `route_design` gives WNS +0.016 ns, WHS
+0.006 ns, fully routed. Rerouting only the failing nets with `-delay` made
things worse (-0.473 ns) and is not used.

`check_fpt_post_route.tcl` now runs up to two incremental `route_design`
passes after restoring the sign-off constraints and before measuring
(`PAPER_POST_ROUTE_REPAIR` lines in the log). Build `platform-hw-physical-v16`:
same sources, regression (v19) and directives as v15, result pending.

### v16: first xclbin at 200 MHz; state store mis-synthesized on the board (2026-11-17)

`platform-hw-physical-v16` routed like v15 and the gate repair pass closed
it: `PAPER_POST_ROUTE_REPAIR` WNS -0.066 / WHS -0.004 ns before, WNS +0.016 /
WHS +0.006 ns after one `route_design`; fully routed, 0 DRC errors or
critical warnings in the gate. v++ produced `FptPaperStreamingKernel.xclbin`.
The manifest is still `passed=false`: v++ deletes the implementation run
after a successful link, so the builder found no routed checkpoint for the
unwaived-DRC and routed-resource audits. `build_paper_streaming_kernel.py`
now links with `--save-temps`.

Unofficial board run of the v16 xclbin (qualification script not used, since
the audits had not run): the kernel loads, runs at 200 MHz, completes with no
fault status, compute period 84,510 cycles per 12-PBS frame, but every
output word is wrong, identically on each run. Probes with zero masks showed
the test vector never reaches the output and that only coefficient points
463/479/495/511 carry data: lane 127 of
`PaperStreamingMixedCoefficientStore17`.

Cause: Vivado 2023.2 mis-inferred the store's block-RAM lanes (36-bit word,
two 18-bit partial writes, write sharing a port with the feedback read). It
split each memory into several RAMB18s and "decloned" 100 of them (389
RAMB18 instead of 512). A standalone post-synthesis functional simulation of
the store against its RTL mismatched on 74,089 of 75,328 checks. Simulation
of the RTL was never affected, so the regression could not see it.

Fix: each BRAM lane now uses four `PaperStreamingStateRam17` memories (plain
17-bit true-dual-port template: port A write-or-read, port B read). Same
test: 512 RAMB18, no decloning, 0 mismatches. No other memory in the kernel
synthesis log shows decloning. Regression
`encrypted-dma-engine586-physical-rtl-v20` passes.

The correct store uses 256 RAMB36 equivalents (the broken one was counted
as 194.5; v16 kernel total 826), so the `bram_equivalents` cap goes from 832
to 896 in `check_kernel_resources.tcl` and the board qualification script.
Builds v17 (pre-fix, stopped) and v18 (stopped before the old cap rejected
it) produced nothing. Build `platform-hw-physical-v19`: fixed store, same
floorplan and directives, result pending.

### v19: corrected store is larger; routing falls back into the congested mode (2026-11-18)

`platform-hw-physical-v19` (fixed store): fully routed, WNS -0.506 ns after
both gate repair passes (-0.776 before), WHS +0.002 ns, no xclbin. First
router iteration had 1,236,356 overlapping nodes (v16: 476,114) and
`Route 35-447`; 19,562 failing paths spread over all three SLRs at about
-0.45 to -0.5 ns, i.e. the congestion mode rather than specific paths.

With the store mis-synthesized, 127 of 128 lanes were constant zero and
synthesis had trimmed logic fed by them (mostly the 512x17 five-stage
rotation in the frontend). v19 has +17.4K LUTs and +31.1K registers, all in
SLR0 (LUT 49% -> 54%), and the placer reports a new level 5-7 window around
the store BRAMs (X20-60, Y54-180: RAMB 100%, LUT ~75%, 4.2 inputs/LUT). So
the v16 timing result was for a partly gutted design and does not carry
over.

In progress: offline place/route trials on v19's `kernel_floorplan_pre_place.dcp`
(`SSI_SpreadLogic_high`; `AltSpreadLogic_high` with `CELL_BLOAT_FACTOR high`
on the rotation and store) to find a placement that avoids the congested
mode.

Placement trials on v19's pre-place checkpoint did not help:
`SSI_SpreadLogic_high` 1,203,340 and `AltSpreadLogic_high` + cell bloat
1,215,014 first-iteration overlaps, both with the level-7 window on the store
BRAM columns.

### Store memories moved to distributed RAM; v20 (2026-11-18)

`PaperStreamingStateRam17` is now `ram_style="distributed"` (same template,
registered outputs, same timing), so the 512 store memories are no longer
pinned to RAMB columns next to the rotation network. Standalone: 17,408
LUTRAM LUTs, no block RAM; post-synthesis simulation against RTL 0
mismatches of 75,328 checks. The parameter is still called
`BRAM_STATE_LANES=128`; it now selects the registered-read store, not block
RAM. `bram_equivalents` cap lowered from 896 to 640 (expected kernel use
about 631.5). Regression `encrypted-dma-engine586-physical-rtl-v21` passes.
Build `platform-hw-physical-v20`, same floorplan and directives, result:
**failed routing**. Resource gate passed (642,801 LUTs, 81,440 LUTRAM, 631.5
BRAM equivalents) but the first routing iteration had 1,366,145 overlaps
(worse than v19's 1,236,356), `Route 35-447`, intermediate WNS -18.5 ns, and
`route_design` ended with conflicted nets. Distributed RAM is not the answer;
the store is block RAM again and the cap is back at 896.

### Rotation network halved; v22 (2026-11-18/19)

Each frontend issue uses only 256 of the 512 rotated points (one half). In
digit-reversed order the second half's points are the first half's plus two,
so the frontend now rotates by `exponent-2` for the second half and always
taps the first half's positions. `PaperStreamingRotate17 #(.LOW_PAIRS(1))`
builds only output points with `point%4<2`: all stages after the first
shift by multiples of four, so half of every stage is omitted (about 21.8K
LUTs and 21.8K registers) and the 256x17 half-select mux after the rotation
(4.4K LUTs) is gone. Function, signs and latency are unchanged
(`DIGIT_REVERSED=0` keeps the full network).

Checks: unit cases `coefficient-frontend17`, `coefficient-frontend17-reversed`,
`blind-rotate3-reversed` pass (`frontend-half-rotation-units-v3`); encrypted
regression `encrypted-dma-engine586-physical-rtl-v23` passes. Build
`platform-hw-physical-v22` (block-RAM store, `--save-temps`, same floorplan
and directives) is running. (`platform-hw-physical-v21` and
`encrypted-dma-engine586-physical-rtl-v22` are empty directories from
mis-typed launches.)

### v22 result: 200 MHz closed, board exact-output test passed (2026-11-19)

`platform-hw-physical-v22`: post-opt 603,035 LUTs / 996,354 registers /
5,810 DSP / 887.5 BRAM equivalents (v16: 608,210 / 986,870; v19: 625,643 /
1,017,973). Placement estimate WNS +0.004 ns; first routing iteration
750,521 overlaps, no `Route 35-447`; router ended at WNS -0.051 ns with the
placement margin, and the gate's two incremental repair passes gave
**WNS +0.016 ns, WHS 0.000 ns**, fully routed. Unwaived DRC audit and routed
resource audit both exit 0; manifest `passed: true`; xclbin sha256
`1efa3fea...732f3034`.

Board run `board-qualification-v22-run1`
(`tools/run_paper_streaming_board_qualification.py`, fixture set
`board-distinct-fixtures-seeds81-112-v1`, 4,096 frames per key, U280 at
0000:0a:00.1, kernel clock 200 MHz): **passed**. 32 keys, 1,572,864
bootstraps, 4,224 distinct inputs, 1,612,185,600 output words compared
exactly, none wrong.

Throughput measured on the board: 28.397 PBS/ms from the kernel's cycle
counter (11,077,748,505 cycles), 28.389 PBS/ms wall-clock over the launches
(55.40 s). The script's throughput targets are reported as not met
(`kernel_throughput_target_met`/`launch_throughput_target_met` false) and it
leaves `hardware_qualified`/`complete_kernel_qualified` false: this run is
the exact-output test only.

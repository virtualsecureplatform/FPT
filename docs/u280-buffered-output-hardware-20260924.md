# U280 buffered-output hardware result — 2026-09-24

This experimental image is an opt-in blind-rotation and sample-extraction
kernel for the Alveo U280 (`xilinx_u280_gen3x16_xdma_1_202211_1`), built with
Vitis/Vivado 2023.2 at 200 MHz. It uses input dimension 630 and processes
16 contexts per invocation. Identity key switching is not included.

The buffered output snapshot lets the next prepared invocation compute while
the previous invocation's result drains to HBM. The design retains one
accumulator and two invocation credits; it does not duplicate the compute
engine. Enable the opt-in buffered-output profile with
`FPT_U280_BUFFERED_OUTPUT=1` together with its prerequisites
`FPT_U280_PREPROCESSED_INPUT=1`, `FPT_U280_CHAINED_INTERFACE=1`, and
`FPT_U280_OUTPUT_LANES=4`. The default source profile is unchanged.

The first full route had setup WNS +0.014 ns but hold WHS -0.001 ns on a
platform HBM SLR2-to-SLR1 Laguna path. A route-only retry of the same
validated synthesis checkpoint enabled Vivado's
`route.enableGlobalHoldIter=1` before `route_design`. The release source
includes the equivalent hook in `vitis/scripts/fpt_high_effort_hold.tcl`.
No RTL changed between these two routes.

The accepted route is fully routed with zero routing errors or unrouted nets.
Post-route setup WNS is +0.016 ns; hold WHS is 0.000 ns; kernel TNS is zero
with no failing endpoints. There are no fatal, error, or critical-warning DRC
violations; ordinary DRC warnings remain. Global/short congestion has an
explicit routed-report bound of at most level 5, and estimated timing
congestion remains level 6. The small timing margin and congestion estimate
should not be interpreted as robust closure across tool seeds or revisions.

The synthesized kernel uses 529,412 LUTs, 760,694 FFs, 488 BRAM tiles,
5,624 DSPs, and 236 URAMs. The source and RTL hashes were checked against
the frozen build manifests before routing and hardware validation.

On the physical U280, smoke, six nonzero arithmetic cases, and four queued
nonzero batches passed. Five repeated 64-batch queued runs produced a median
of 19.5903 results/ms, compared with 18.1184 results/ms for the prior chained
release: an 8.1% increase and 98.7% of the 19.84 results/ms compute ceiling
for this parameter set. All 64-batch runs completed 64 batches and observed
63 prefetched while another was active. Isolated 16-context kernel invocations
had median host start/wait time 927.292 us (100 runs after five warmups), so
the improvement is in overlapped throughput, not single-invocation latency.

The throughput timing excludes host buffer allocation, initial PCIe input/key
transfers, and final output readback. Nonzero hardware vectors are synthetic
single-active-CMUX extensions rather than a dense 630-active-step cryptographic
workload; queued throughput vectors use zero keys and nonzero initialized
accumulators. This is not full cryptographic correctness validation or an
end-to-end application benchmark.

The release xclbin SHA-256 is
`a1f067203e4b76aedc6d45034b7573d49aefbb87e7c1071d448d62f9908f612d`.
Local raw reports and test logs are under `build/buffered-output-control/`.

# U280 chained hardware validation — 2026-09-19

The optional `FPT_U280_CHAINED_INTERFACE=1` kernel passed 200 MHz implementation
and real U280 tests. Dimension 630, batch size 16, and arithmetic are unchanged.

## Implementation and artifact

- Kernel and overall setup WNS +0.005 ns; hold slack 0.000 ns.
- Fully routed, no routing errors or fatal/error/critical DRC violations.
  Ordinary DRC warnings remain (9701).
- Congestion: global/short 6, timing 7.
- Synthesis: 530170 LUTs, 757886 FFs, 482.5 BRAM tiles, 5624 DSPs, 236 URAMs.
- Artifact: `build/chained-interface/vitis/xclbin/FptBlindRotateKernel_hw_A_chained_interface.xclbin`.
- SHA256: `302650bc3d449ce8d394629613a11c6edd7ce1379ecbcdb805b9ad288560c827`.
- UUID: `821D0EBD-6803-5C46-7902-4A90AA6970A4`.

## Hardware correctness

Device `0000:0a:00.1` was healthy and idle before programming. The previous
validated image remains on disk; the chained image is now loaded.

Zero-vector smoke and all six existing nonzero cases passed all 16400 output
words per invocation. Core time remains 165948 cycles (0.829740 ms), with
80640 key beats and no DMA error. These nonzero fixtures extend a single active
CMUX to 630 steps; they are not fully nontrivial cryptographic 630-step vectors.

The distinct-buffer queued test passed at 2 and 64 batches. Every 64-batch
run checked 1049600 output words and reported accepted=64, completed=64,
prefetched-while-active=63. The initial run and all five repetitions passed.
Queued tests use zero keys and nonzero initial accumulators; nonzero-key
arithmetic is covered separately by the six cases above.

## Performance

Single-invocation XRT start/wait, 5 warmups and 100 measurements:
median 920.348 us, min 915.548 us, mean 920.661 us, p95 926.039 us,
max 934.565 us. This corresponds to 17.385 results/ms.

For five repeated 64-batch queued runs, throughput ranged from 18.1135 to
18.1188 results/ms, median 18.1184. Median total submission/completion time
was 56.517 ms for 1024 results, or 883.078 us per batch on average.
This is about 4.22% higher throughput than sequential submission of the same
image, and 91.3% of the 19.84 results/ms compute ceiling for n=630.

Measurements exclude BO allocation, input/key transfers, and output readback.
Queued timing includes submission overhead and the initial/final batch;
it is not individual-request latency or an isolated steady-state interval.
Only next-batch input prefetch and dispatch overlap; arithmetic remains serial.

After 499 invocations the board was healthy and idle, FPGA 55 C, no power
warning. No release or commit is implied. Raw results, repeat logs, source
hashes, and the reproducible test driver are under
`build/chained-interface/hardware-test/`.

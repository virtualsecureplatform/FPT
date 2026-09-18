# Hardware-validated U280 wide-output baseline (2026-09-18)

The `host-coefficient-pruned` trial completed full implementation and real FPGA
validation. Input dimension remains 630, batch size 16, CMUX II16, core clock
200 MHz. This is the sequential `ap_ctrl_hs` baseline, not a chained kernel.

The trial enables `FPT_U280_OUTPUT_LANES=4`,
`FPT_U280_LOCAL_COEFFICIENT_QUEUES=1`, and
`FPT_HOST_HBM_LAGUNA_PLACEMENT=1` on the existing window-reset profile. It uses
the full 1252-pair host-HBM Laguna allocation and all existing timing, resource,
DRC, locality, and numerical gates. See the associated trial documents for
the failed attempts and the reasons for the final queue/checker changes.

## Implementation

- Kernel WNS +0.028 ns; overall WNS +0.016 ns; overall hold slack 0.000 ns.
- Fully routed, zero route errors and zero fatal/error/critical-warning DRCs.
  Ordinary DRC warnings remain; this is not a warning-free build.
- Global/short congestion level 6; timing congestion level 7.
- Synthesis: 529147 LUTs, 758120 FFs, 5624 DSPs, 468 BRAM tiles, 236 URAMs.
- 29 simulation tests and 16400-word hardware-emulation smoke passed.

## Physical FPGA

Device 0000:0a:00.1, xilinx_u280_gen3x16_xdma_base_1. Xclbin UUID
AA09C3CF-CFA5-8DA4-4CB9-7CAFC8C5AB63. Artifact SHA256:
`3c06bf8bc842534c3a106d388f6c11b57f292ab83120d37769ee584fadc71b20`.
Local artifact:
`build/host-coefficient-pruned/vitis/xclbin/FptBlindRotateKernel_hw_A_wide_output.xclbin`.
The binary is not stored in Git and no new release is implied by this document.

- Zero-vector smoke and all six nonzero cases passed all 16400 words.
- All nonzero cases consumed 80640 key beats, with no DataMover error.
- Core run counter: 165948 cycles, or 0.829740 ms at 200 MHz, versus the
  prior baseline's 178235 cycles / 0.891175 ms.
- Host XRT start/wait latency, zero vectors, 5 warmups + 100 measurements:
  min 918.494 us, median 922.682 us, mean 923.551 us, p95 931.980 us,
  max 944.293 us. Input/key transfers and output readback are excluded.
- Board healthy and idle after 113 executions, FPGA 55 C, no power warning.

The six nonzero cases are synthetic single-active-CMUX extensions to 630
steps, including dense key traffic and key-bank reuse. They do not establish
correctness for fully nontrivial 630-step cryptographically generated inputs.
The exact RTL reference digest is
`b02dc10d50e4111bd5e4b79993b355942fd58b2d6d758d40bc544901f38f372e`.

The ideal compute ceiling for these parameters is 19.84 results/ms. Dividing
16 results by measured core time gives 19.28 results/ms (97.2% of the ceiling);
host start/wait gives 17.34 results/ms. These are sequential batch-derived
rates, not measured sustained overlapped throughput. The paper uses n=500
for Set II and supports next-batch prefetch with `ap_ctrl_chain`; comparisons
must retain that distinction.

Detailed local evidence is in `build/host-coefficient-pruned/hardware-test/`.

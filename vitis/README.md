# FPT U280 RTL kernel

This directory packages the 16-context Paper Set-II blind-rotate accelerator
as one `ap_ctrl_hs` Vitis RTL kernel at 200 MHz. Its routable U280 datapath
uses a 64-lane forward FFT and 32-lane inverse FFT, with a matching 64-to-32
lane external product. It uses three 512-bit MM2S DataMovers and one
32-to-512-bit S2MM DataMover in a single clock domain.

Run `./vitis/build_hw_emu.sh` or `./vitis/build_hw.sh`. The supported
200 MHz floorplan is `FPT_FLOORPLAN=A`: forward FFT in SLR0, state and
external product in SLR1, and inverse FFT in SLR2. `--xo-only` stops after
packaging; `--test` builds and runs a zero-vector XRT smoke test after linking.

The four kernel arguments, in order, are the input, key-low, key-high, and
output buffers. The input is context-major: one test-vector word followed by
630 TLWE mask words and one TLWE body for each of 16 contexts. Each key buffer
contains 161,280 512-bit words. A logical 864-bit key beat packs sixteen lanes as
`{imag[26:0], real[26:0]}`; bits 511:0 go to key-low and bits 863:512 go in the
low 352 bits of key-high. Output is 16 context-major 1,025-word TLWE results.

AXI-Lite register `0x30` reports `{key_index[9:0], inputs_loaded,
key_zero_loaded, run_started, core_done_seen, input_status_done,
key_low_status_done, key_high_status_done, output_status_done,
key_load_active, busy, error_code[7:0], error_channel[2:0], error}`.
Error channels 1–4 identify
input, key-low, key-high, and output respectively. A DataMover error completes
the invocation with the sticky error set; reset the kernel before reuse.

`host/FptKernelLayout.hpp` defines the exact byte counts and packing helpers.
The current acceptance criterion is raw Paper Set-II fixed-point equivalence,
not TFHEpp decryption reliability.

The narrower transforms double the internal frame count relative to the
previous 128/64 implementation; they do not change buffer sizes, key packing,
or the 16-context invocation contract.

The routed U280 build closes at 200 MHz with WNS +0.016 ns, WHS +0.006 ns,
and no failed, partially routed, or unrouted nets. The coefficient-scratch and
external-product data boundaries capture unconditionally; their separate
valid pipelines qualify consumption and memory writes. This prevents valid
signals from becoming clock-enable broadcasts over 4K--16K-bit datapaths.

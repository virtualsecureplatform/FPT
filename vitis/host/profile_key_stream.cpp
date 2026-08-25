#include "FptKernelLayout.hpp"

#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>
#include <xrt/xrt_kernel.h>

#include <chrono>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <thread>

int main(int argc, char** argv) try {
  using namespace fpt::u280;
  if (argc != 2) {
    std::cerr << "usage: fpt_key_stream_profile KERNEL.xclbin\n";
    return 2;
  }
  xrt::device device{0};
  const auto uuid = device.load_xclbin(argv[1]);
  xrt::kernel kernel{device, uuid, "FptBlindRotateKernel",
                     xrt::kernel::cu_access_mode::exclusive};
  xrt::bo input{device, kInputBytes,
                static_cast<xrt::memory_group>(kernel.group_id(0))};
  xrt::bo keyLow{device, kKeyBufferBytes,
                 static_cast<xrt::memory_group>(kernel.group_id(1))};
  xrt::bo keyHigh{device, kKeyBufferBytes,
                  static_cast<xrt::memory_group>(kernel.group_id(2))};
  xrt::bo keyLow1{device, kKeyBufferBytes,
                  static_cast<xrt::memory_group>(kernel.group_id(3))};
  xrt::bo keyHigh1{device, kKeyBufferBytes,
                   static_cast<xrt::memory_group>(kernel.group_id(4))};
  xrt::bo output{device, kOutputBytes,
                 static_cast<xrt::memory_group>(kernel.group_id(5))};
  std::fill_n(input.map<std::uint32_t*>(), kInputWords, 0);
  std::fill_n(keyLow.map<std::uint64_t*>(), kKeyBufferBytes / 8, 0);
  std::fill_n(keyHigh.map<std::uint64_t*>(), kKeyBufferBytes / 8, 0);
  std::fill_n(keyLow1.map<std::uint64_t*>(), kKeyBufferBytes / 8, 0);
  std::fill_n(keyHigh1.map<std::uint64_t*>(), kKeyBufferBytes / 8, 0);
  input.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  keyLow.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  keyHigh.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  keyLow1.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  keyHigh1.sync(XCL_BO_SYNC_BO_TO_DEVICE);

  xrt::run run{kernel};
  run.set_arg(0, input);
  run.set_arg(1, keyLow);
  run.set_arg(2, keyHigh);
  run.set_arg(3, keyLow1);
  run.set_arg(4, keyHigh1);
  run.set_arg(5, output);
  const auto start = std::chrono::steady_clock::now();
  run.start();
  const auto state = run.wait(std::chrono::seconds(10));
  if (state != ERT_CMD_STATE_COMPLETED) {
    throw std::runtime_error("kernel did not complete");
  }
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
  const auto status = kernel.read_register(0x30);
  const auto keyBeats = kernel.read_register(0x34);
  const auto keyStarved = kernel.read_register(0x38);
  const auto keyBlocked = kernel.read_register(0x3c);
  const auto runCycles = kernel.read_register(0x40);
#pragma GCC diagnostic pop
  const auto stop = std::chrono::steady_clock::now();
  std::cout << std::fixed << std::setprecision(3) << "total_us="
            << std::chrono::duration<double, std::micro>(stop - start).count()
            << " status=0x" << std::hex << status << std::dec
            << " key_beats=" << keyBeats
            << " key_starved_cycles=" << keyStarved
            << " key_bank_blocked_cycles=" << keyBlocked
            << " run_cycles=" << runCycles << '\n';
  return 0;
} catch (const std::exception& error) {
  std::cerr << "FPT key-stream profile failed: " << error.what() << '\n';
  return 1;
}

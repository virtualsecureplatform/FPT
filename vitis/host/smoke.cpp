#include "FptKernelLayout.hpp"

#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>
#include <xrt/xrt_kernel.h>

#include <algorithm>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>

int main(int argc, char** argv) try {
  using namespace fpt::u280;
  if (argc != 2) {
    std::cerr << "usage: fpt_kernel_smoke KERNEL.xclbin\n";
    return 2;
  }

  xrt::device device{0};
  const auto uuid = device.load_xclbin(argv[1]);
  xrt::kernel kernel{device, uuid, "FptBlindRotateKernel",
                     xrt::kernel::cu_access_mode::exclusive};
  xrt::bo input{device, kInputBytes,
                static_cast<xrt::memory_group>(kernel.group_id(0))};
  xrt::bo key_low{device, kKeyBufferBytes,
                  static_cast<xrt::memory_group>(kernel.group_id(1))};
  xrt::bo key_high{device, kKeyBufferBytes,
                   static_cast<xrt::memory_group>(kernel.group_id(2))};
  xrt::bo output{device, kOutputBytes,
                 static_cast<xrt::memory_group>(kernel.group_id(3))};

  std::fill_n(input.map<std::uint32_t*>(), kInputWords, 0);
  std::fill_n(key_low.map<std::uint64_t*>(), kKeyBufferBytes / 8, 0);
  std::fill_n(key_high.map<std::uint64_t*>(), kKeyBufferBytes / 8, 0);
  std::fill_n(output.map<std::uint32_t*>(), kOutputWords, 0xdeadbeefu);
  input.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  key_low.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  key_high.sync(XCL_BO_SYNC_BO_TO_DEVICE);

  auto run = kernel(input, key_low, key_high, output);
  run.wait();
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
  const auto status = kernel.read_register(0x30);
#pragma GCC diagnostic pop
  if (status & 1u) {
    throw std::runtime_error("kernel DataMover error status " +
                             std::to_string(status));
  }
  output.sync(XCL_BO_SYNC_BO_FROM_DEVICE);
  const auto* result = output.map<const std::uint32_t*>();
  const auto nonzero = std::find_if(
      result, result + kOutputWords, [](std::uint32_t value) { return value != 0; });
  if (nonzero != result + kOutputWords) {
    throw std::runtime_error(
        "zero-vector mismatch at output word " +
        std::to_string(static_cast<std::size_t>(nonzero - result)));
  }
  std::cout << "FPT zero-vector smoke passed: " << kOutputWords << " words\n";
  return 0;
} catch (const std::exception& error) {
  std::cerr << "FPT smoke failed: " << error.what() << '\n';
  return 1;
}

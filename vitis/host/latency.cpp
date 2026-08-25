#include "FptKernelLayout.hpp"

#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>
#include <xrt/xrt_kernel.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

void wait_for_completion(xrt::run& run, const xrt::kernel& kernel) {
  const auto state = run.wait(std::chrono::seconds(10));
  if (state == ERT_CMD_STATE_COMPLETED) {
    return;
  }
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
  const auto ap_control = kernel.read_register(0x00);
  const auto status = kernel.read_register(0x30);
#pragma GCC diagnostic pop
  throw std::runtime_error(
      "kernel timeout: command_state=" +
      std::to_string(static_cast<unsigned>(state)) + " ap_control=" +
      std::to_string(ap_control) + " status=" + std::to_string(status));
}

std::size_t parse_count(const char* text, const char* name) {
  const std::string value{text};
  std::size_t end = 0;
  const auto count = std::stoull(value, &end);
  if (end != value.size() || count == 0) {
    throw std::invalid_argument(std::string{name} + " must be positive");
  }
  return count;
}

double percentile(const std::vector<double>& sorted, double fraction) {
  const auto index = static_cast<std::size_t>(
      std::ceil(fraction * static_cast<double>(sorted.size())) - 1.0);
  return sorted.at(std::min(index, sorted.size() - 1));
}

}  // namespace

int main(int argc, char** argv) try {
  using namespace fpt::u280;
  if (argc < 2 || argc > 4) {
    std::cerr << "usage: fpt_kernel_latency KERNEL.xclbin [iterations] [warmups]\n";
    return 2;
  }
  const std::size_t iterations = argc >= 3 ? parse_count(argv[2], "iterations") : 20;
  const std::size_t warmups = argc >= 4 ? parse_count(argv[3], "warmups") : 1;

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
  xrt::bo key_low1{device, kKeyBufferBytes,
                   static_cast<xrt::memory_group>(kernel.group_id(3))};
  xrt::bo key_high1{device, kKeyBufferBytes,
                    static_cast<xrt::memory_group>(kernel.group_id(4))};
  xrt::bo output{device, kOutputBytes,
                 static_cast<xrt::memory_group>(kernel.group_id(5))};

  std::fill_n(input.map<std::uint32_t*>(), kInputWords, 0);
  std::fill_n(key_low.map<std::uint64_t*>(), kKeyBufferBytes / 8, 0);
  std::fill_n(key_high.map<std::uint64_t*>(), kKeyBufferBytes / 8, 0);
  std::fill_n(key_low1.map<std::uint64_t*>(), kKeyBufferBytes / 8, 0);
  std::fill_n(key_high1.map<std::uint64_t*>(), kKeyBufferBytes / 8, 0);
  std::fill_n(output.map<std::uint32_t*>(), kOutputWords, 0xdeadbeefu);
  input.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  key_low.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  key_high.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  key_low1.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  key_high1.sync(XCL_BO_SYNC_BO_TO_DEVICE);

  xrt::run run{kernel};
  run.set_arg(0, input);
  run.set_arg(1, key_low);
  run.set_arg(2, key_high);
  run.set_arg(3, key_low1);
  run.set_arg(4, key_high1);
  run.set_arg(5, output);

  auto execute = [&] {
    run.start();
    wait_for_completion(run, kernel);
  };
  for (std::size_t index = 0; index < warmups; ++index) {
    execute();
  }

  std::vector<double> samples_us;
  samples_us.reserve(iterations);
  for (std::size_t index = 0; index < iterations; ++index) {
    const auto start = std::chrono::steady_clock::now();
    run.start();
    wait_for_completion(run, kernel);
    const auto stop = std::chrono::steady_clock::now();
    samples_us.push_back(
        std::chrono::duration<double, std::micro>(stop - start).count());
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

  std::sort(samples_us.begin(), samples_us.end());
  const double mean = std::accumulate(samples_us.begin(), samples_us.end(), 0.0) /
                      static_cast<double>(samples_us.size());
  std::cout << std::fixed << std::setprecision(3)
            << "FPT U280 kernel latency (16 contexts, kernel execution only)\n"
            << "iterations=" << iterations << " warmups=" << warmups << '\n'
            << "min_us=" << samples_us.front() << " median_us="
            << percentile(samples_us, 0.50) << " mean_us=" << mean
            << " p95_us=" << percentile(samples_us, 0.95)
            << " max_us=" << samples_us.back() << '\n'
            << "median_per_context_us="
            << percentile(samples_us, 0.50) / static_cast<double>(kContexts)
            << '\n';
  return 0;
} catch (const std::exception& error) {
  std::cerr << "FPT latency benchmark failed: " << error.what() << '\n';
  return 1;
}

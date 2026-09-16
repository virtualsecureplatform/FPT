#include "FptKernelLayout.hpp"
#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>
#include <xrt/xrt_kernel.h>
#include <chrono>
#include <fstream>
#include <iostream>
#include <limits>
#include <string>

// Extend the oracle-validated one-step fixture to the hardware's 630 steps.
// Zero key rows give zero updates; zero exponents give zero differences.
// Neither extension changes the single active CMUX's expected output.
int main(int argc, char** argv) try {
  using namespace fpt::u280;
  if (argc != 4) {
    std::cerr << "usage: fpt_nonzero KERNEL.xclbin ORACLE_VECTORS RTL_EXPECTED\n";
    return 2;
  }
  std::ifstream fixture(argv[2]), golden(argv[3]);
  auto read = [](std::istream& file) {
    std::int64_t value;
    if (!(file >> value)) throw std::runtime_error("missing vector value");
    return value;
  };
  auto word = [&](std::istream& file) {
    const auto value = read(file);
    if (value < 0 || value > std::numeric_limits<std::uint32_t>::max())
      throw std::runtime_error("invalid torus word");
    return static_cast<std::uint32_t>(value);
  };
  std::array<std::uint32_t, kContexts> tv{}, activeMasks{}, bodies{};
  for (std::size_t c = 0; c < kContexts; ++c) {
    tv[c] = word(fixture); activeMasks[c] = word(fixture); bodies[c] = word(fixture);
    if ((activeMasks[c] & ((1u << 21) - 1)) || (bodies[c] & ((1u << 21) - 1)))
      throw std::runtime_error("fixture must use exactly modulus-switched inputs");
  }
  std::array<std::array<ComplexKeyLane, 2>, 2048> key{};
  for (auto& point : key) for (auto& component : point) {
    auto real = read(fixture), imag = read(fixture);
    if (real < -(1 << 26) || real >= (1 << 26) || imag < -(1 << 26) || imag >= (1 << 26))
      throw std::runtime_error("key exceeds signed 27-bit format");
    component = {static_cast<std::int32_t>(real), static_cast<std::int32_t>(imag)};
  }
  std::vector<std::uint32_t> expected(kOutputWords), initial(kOutputWords, 0);
  std::size_t changed = 0;
  for (std::size_t c = 0; c < kContexts; ++c) {
    const auto exponent = (0u - (bodies[c] >> 21)) & 2047u;
    const bool negative = ((exponent >> 10) != 0) ^ ((exponent & 1023u) != 0);
    initial[c * 1025 + 1024] = negative ? 0u - tv[c] : tv[c];
  }
  for (std::size_t i = 0; i < kOutputWords; ++i) {
    word(fixture);  // Consume the C++ oracle; RTL reference passed its numerical gate.
    expected[i] = word(golden);
    changed += expected[i] != initial[i];
  }
  std::string extra;
  if ((fixture >> extra) || (golden >> extra) || changed == 0)
    throw std::runtime_error("invalid vector length or trivial reference");

  xrt::device device{0};
  const auto uuid = device.load_xclbin(argv[1]);
  xrt::kernel kernel{device, uuid, "FptBlindRotateKernel", xrt::kernel::cu_access_mode::exclusive};
  xrt::bo input{device, kInputBytes, static_cast<xrt::memory_group>(kernel.group_id(0))};
  std::vector<xrt::bo> keys;
  for (int i = 1; i <= 4; ++i)
    keys.emplace_back(device, kKeyBufferBytes, static_cast<xrt::memory_group>(kernel.group_id(i)));
  xrt::bo output{device, kOutputBytes, static_cast<xrt::memory_group>(kernel.group_id(5))};
  const std::array<int, 6> activeIndices{{-1, 0, 1, 314, 629, 629}};
  for (std::size_t test = 0; test < activeIndices.size(); ++test) {
    const int active = activeIndices[test];
    const bool dense = test == activeIndices.size() - 1;
    std::array<std::array<std::uint32_t, kDimension>, kContexts> masks{};
    for (std::size_t c = 0; c < kContexts; ++c)
      for (std::size_t i = 0; i < kDimension; ++i)
        masks[c][i] = static_cast<int>(i) == active ? activeMasks[c] :
          (dense ? 0u : static_cast<std::uint32_t>((37 + 113*c + 17*i) & 2047) << 21);
    const auto packed = pack_input_batch(tv, masks, bodies);
    std::copy(packed.begin(), packed.end(), input.map<std::uint32_t*>());
    for (auto& buffer : keys) std::fill_n(buffer.map<std::uint64_t*>(), kKeyBufferBytes/8, 0);
    for (std::size_t index = 0; index < kDimension; ++index) {
      if (!dense && static_cast<int>(index) != active) continue;
      for (std::size_t beat = 0; beat < kKeyBeatsPerCoefficient; ++beat) {
        for (std::size_t stream = 0; stream < 2; ++stream) {
          std::array<ComplexKeyLane, 16> lanes{};
          for (std::size_t lane = 0; lane < 16; ++lane) {
            const auto scalar = stream * 16 + lane;
            const auto point = (beat / 32) * 512 + (beat % 32) * 16 + scalar / 2;
            lanes[lane] = key[point][scalar % 2];
          }
          const auto pair = pack_key_beat(lanes);
          keys[stream*2].map<Axi512Word*>()[index*128+beat] = pair.first;
          keys[stream*2+1].map<Axi512Word*>()[index*128+beat] = pair.second;
        }
      }
    }
    std::fill_n(output.map<std::uint32_t*>(), kOutputWords, 0xdeadbeefu);
    input.sync(XCL_BO_SYNC_BO_TO_DEVICE);
    for (auto& buffer : keys) buffer.sync(XCL_BO_SYNC_BO_TO_DEVICE);
    output.sync(XCL_BO_SYNC_BO_TO_DEVICE);
    auto run = kernel(input, keys[0], keys[1], keys[2], keys[3], output);
    if (run.wait(std::chrono::seconds(10)) != ERT_CMD_STATE_COMPLETED)
      throw std::runtime_error("kernel completion timeout");
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
    const auto status = kernel.read_register(0x30);
    const auto beats = kernel.read_register(0x34);
    const auto starved = kernel.read_register(0x38);
    const auto blocked = kernel.read_register(0x3c);
    const auto cycles = kernel.read_register(0x40);
#pragma GCC diagnostic pop
    if ((status & 1u) || beats != kKeyWords) throw std::runtime_error("DataMover status/key beat count mismatch");
    output.sync(XCL_BO_SYNC_BO_FROM_DEVICE);
    const auto actual = output.map<const std::uint32_t*>();
    const auto& reference = active < 0 ? initial : expected;
    for (std::size_t i = 0; i < kOutputWords; ++i)
      if (actual[i] != reference[i]) throw std::runtime_error(
        "test=" + std::to_string(test) + " index=" + std::to_string(i) +
        " actual=" + std::to_string(actual[i]) + " expected=" + std::to_string(reference[i]));
    std::cout << "NONZERO_HARDWARE_PASS test=" << test << " active_key=" << active
      << " dense_key=" << dense << " words=" << kOutputWords << " key_beats=" << beats
      << " key_starved_cycles=" << starved << " key_bank_blocked_cycles=" << blocked
      << " run_cycles=" << cycles << std::endl;
  }
  std::cout << "All 6 nonzero hardware cases passed; exact RTL reference changed " << changed << " words\n";
} catch (const std::exception& error) {
  std::cerr << "Nonzero hardware test failed: " << error.what() << '\n';
  return 1;
}

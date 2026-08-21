#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <utility>
#include <vector>

namespace fpt::u280 {

inline constexpr std::size_t kContexts = 16;
inline constexpr std::size_t kDimension = 630;
inline constexpr std::size_t kPolynomialSize = 1024;
inline constexpr std::size_t kInputWordsPerContext = kDimension + 2;
inline constexpr std::size_t kInputWords = kContexts * kInputWordsPerContext;
inline constexpr std::size_t kOutputWords =
    kContexts * (kPolynomialSize + 1);
inline constexpr std::size_t kKeyBeatsPerCoefficient = 256;
inline constexpr std::size_t kKeyWords =
    kDimension * kKeyBeatsPerCoefficient;
inline constexpr std::size_t kInputBytes = kInputWords * sizeof(std::uint32_t);
inline constexpr std::size_t kOutputBytes = kOutputWords * sizeof(std::uint32_t);
inline constexpr std::size_t kKeyBufferBytes = kKeyWords * 64;
inline constexpr std::uint32_t kKeyMask = (1u << 27) - 1;

struct alignas(64) Axi512Word {
  std::array<std::uint64_t, 8> words{};
};

struct ComplexKeyLane {
  std::int32_t real;
  std::int32_t imag;
};

inline std::vector<std::uint32_t> pack_input_batch(
    const std::array<std::uint32_t, kContexts>& test_vectors,
    const std::array<std::array<std::uint32_t, kDimension>, kContexts>& masks,
    const std::array<std::uint32_t, kContexts>& bodies) {
  std::vector<std::uint32_t> result;
  result.reserve(kInputWords);
  for (std::size_t context = 0; context < kContexts; ++context) {
    result.push_back(test_vectors[context]);
    result.insert(result.end(), masks[context].begin(), masks[context].end());
    result.push_back(bodies[context]);
  }
  return result;
}

inline void set_bits(
    Axi512Word& word,
    std::size_t first_bit,
    std::size_t width,
    std::uint64_t value) {
  if (width == 0 || width > 64 || first_bit + width > 512) {
    throw std::out_of_range("invalid AXI word bit range");
  }
  const std::size_t base = first_bit / 64;
  const std::size_t offset = first_bit % 64;
  const std::uint64_t mask = width == 64 ? ~std::uint64_t{0}
                                         : ((std::uint64_t{1} << width) - 1);
  value &= mask;
  word.words[base] |= value << offset;
  if (offset + width > 64) {
    word.words[base + 1] |= value >> (64 - offset);
  }
}

inline std::pair<Axi512Word, Axi512Word> pack_key_beat(
    const std::array<ComplexKeyLane, 16>& lanes) {
  Axi512Word low;
  Axi512Word high;
  for (std::size_t lane = 0; lane < lanes.size(); ++lane) {
    const std::uint64_t packed =
        (static_cast<std::uint32_t>(lanes[lane].real) & kKeyMask) |
        (std::uint64_t{static_cast<std::uint32_t>(lanes[lane].imag) & kKeyMask}
         << 27);
    const std::size_t first = lane * 54;
    if (first < 512) {
      const std::size_t low_width = std::min<std::size_t>(54, 512 - first);
      set_bits(low, first, low_width, packed);
      if (low_width != 54) {
        set_bits(high, 0, 54 - low_width, packed >> low_width);
      }
    } else {
      set_bits(high, first - 512, 54, packed);
    }
  }
  return {low, high};
}

}  // namespace fpt::u280

static_assert(sizeof(fpt::u280::Axi512Word) == 64);
static_assert(fpt::u280::kInputBytes == 40448);
static_assert(fpt::u280::kOutputBytes == 65600);
static_assert(fpt::u280::kKeyBufferBytes == 10321920);

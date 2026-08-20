#include "FptKernelLayout.hpp"

#include <cassert>

int main() {
  using namespace fpt::u280;
  std::array<ComplexKeyLane, 16> lanes{};
  lanes[0] = {-1, 3};
  lanes[9] = {5, -7};
  const auto [low, high] = pack_key_beat(lanes);
  assert((low.words[0] & kKeyMask) == kKeyMask);
  assert(((low.words[0] >> 27) & kKeyMask) == 3);
  // Lane nine begins at bit 486 and therefore exercises the low/high split.
  assert(((low.words[7] >> 38) & ((std::uint64_t{1} << 26) - 1)) == 5);
  assert((high.words[0] & 1) == 0);
  assert(((high.words[0] >> 1) & kKeyMask) ==
         (static_cast<std::uint32_t>(-7) & kKeyMask));
  return 0;
}

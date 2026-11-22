#pragma once

#include <cstdint>
#include <array>
#include <stdexcept>
#include <vector>
#include "fpt/fixed_point.hpp"

namespace fpt::paper_streaming {

// Separate from the legacy arithmetic profiles: no compatibility is implied.
struct Profile {
    static constexpr unsigned schema_version = 1;
    unsigned transform_bits;
    unsigned key_bits;
    unsigned accumulator_bits;
    // A candidate geometry is not a numerically qualified arithmetic schedule.
    bool numerical_qualified = false;
};

inline std::vector<Profile> candidate_geometries()
{
    std::vector<Profile> result;
    for (unsigned transform : {29u, 30u, 32u})
        for (unsigned key : {26u, 27u, 28u})
            for (unsigned accumulator : {32u, 40u, 48u, 59u})
                result.push_back({transform, key, accumulator, false});
    return result;
}

struct Resources {
    std::uint64_t lut, ff, dsp, ramb36, ramb18, uram;
    bool admitted() const
    {
        return lut <= 631200 && ff <= 1099200 && dsp <= 6592 &&
            ramb36 <= 606 && ramb18 <= 1212 && uram <= 75 &&
            2 * ramb36 + ramb18 + 16 * uram <= 1212;
    }
};

struct Geometry {
    static constexpr unsigned dimension = 586, polynomial_size = 512;
    static constexpr unsigned components = 3, levels = 2, contexts = 12;
    static constexpr unsigned complex_points = polynomial_size / 2;
    static constexpr unsigned forward_lanes = 128, inverse_lanes = 64;
    static constexpr unsigned row_cycles = complex_points / forward_lanes;
    static constexpr unsigned cmux_interval = row_cycles * components * levels;
    static constexpr unsigned feedback_budget = contexts * cmux_interval;
    static constexpr unsigned ideal_batch_cycles = dimension * feedback_budget;
};

// Track divisions separately from binary points. In particular the inverse
// normalization is log2(N/2), not log2(N), for this folded transform pair.
struct Scale {
    int spectrum_divisions = 0;
    int inverse_divisions = 0;
    int terminal_normalization() const
    {
        if (spectrum_divisions < 0 || inverse_divisions < 0 ||
            spectrum_divisions > 8 || inverse_divisions > 8)
            throw std::invalid_argument("invalid transform scaling");
        const int shift = 8 - spectrum_divisions - inverse_divisions;
        if (shift < 0) throw std::invalid_argument("excess transform scaling");
        return shift;
    }
};

// Signed nearest-even requantization, including negative ties. Overflow is
// counted before modular narrowing; it must never disappear in a cast.
inline std::int64_t requantize(__int128 raw, unsigned discarded_bits,
                              FixedFormat destination, QuantizationStats &stats)
{
    if (discarded_bits > 120 || !destination.valid())
        throw std::invalid_argument("invalid streaming requantization");
    if (discarded_bits) {
        const auto floor = floor_shift_right(raw, discarded_bits);
        const __int128 remainder = raw - floor * (__int128{1} << discarded_bits);
        const __int128 half = __int128{1} << (discarded_bits - 1);
        raw = floor + (remainder > half || (remainder == half && (floor & 1)));
    }
    return wrap_signed(raw, destination, &stats);
}

struct ComplexProduct {
    __int128 real, imag;
};

// Three real multiplies; additions are widened before multiplication.
inline ComplexProduct gauss_product(std::int64_t ar, std::int64_t ai,
                                    std::int64_t br, std::int64_t bi)
{
    const __int128 z = (__int128{ar} - ai) * br;
    return {z + __int128{ai} * (__int128{br} - bi),
            __int128{ar} * (__int128{br} + bi) - z};
}

// Exact local radix-4 butterfly. Narrow only at the declared stage boundary;
// doing so inside the shared pair sums would change the rounding schedule.
inline std::array<ComplexProduct, 4> butterfly4(
    const std::array<ComplexProduct, 4> &x, bool inverse)
{
    const ComplexProduct even{x[0].real + x[2].real, x[0].imag + x[2].imag};
    const ComplexProduct odd{x[1].real + x[3].real, x[1].imag + x[3].imag};
    const ComplexProduct difference{x[0].real - x[2].real, x[0].imag - x[2].imag};
    const ComplexProduct cross{x[1].real - x[3].real, x[1].imag - x[3].imag};
    const int sign = inverse ? 1 : -1;
    return {{{even.real + odd.real, even.imag + odd.imag},
             {difference.real - sign * cross.imag, difference.imag + sign * cross.real},
             {even.real - odd.real, even.imag - odd.imag},
             {difference.real + sign * cross.imag, difference.imag - sign * cross.real}}};
}

static_assert(Geometry::row_cycles == 2);
static_assert(Geometry::cmux_interval == 12);
static_assert(Geometry::feedback_budget == 144);
static_assert(Geometry::ideal_batch_cycles == 84384);

} // namespace fpt::paper_streaming

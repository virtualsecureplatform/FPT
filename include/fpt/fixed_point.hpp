#pragma once

#include <cmath>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string>

namespace fpt {

// The paper writes FixedPoint_width(integerBits, fractionalBits).  As in
// Chisel/SGen, integer_bits includes the sign bit and width is their sum.
struct FixedFormat {
    int integer_bits;
    int fractional_bits;

    [[nodiscard]] constexpr int width() const
    {
        return integer_bits + fractional_bits;
    }

    [[nodiscard]] constexpr bool valid() const
    {
        return integer_bits >= 1 && fractional_bits >= 0 && width() <= 62;
    }

    [[nodiscard]] std::string str() const
    {
        return "Q" + std::to_string(integer_bits) + "." +
               std::to_string(fractional_bits);
    }
};

struct QuantizationStats {
    std::uint64_t overflows = 0;
    std::uint64_t quantizations = 0;
    long double max_abs_unwrapped_raw = 0;

    void observe(__int128 raw, const FixedFormat format)
    {
        ++quantizations;
        const __int128 limit = static_cast<__int128>(1) << (format.width() - 1);
        if (raw < -limit || raw >= limit) ++overflows;
        const long double magnitude =
            raw < 0 ? static_cast<long double>(-raw)
                    : static_cast<long double>(raw);
        if (magnitude > max_abs_unwrapped_raw)
            max_abs_unwrapped_raw = magnitude;
    }
};

[[nodiscard]] inline std::int64_t wrap_signed(__int128 raw,
                                               const FixedFormat format,
                                               QuantizationStats *stats = nullptr)
{
    if (!format.valid()) throw std::invalid_argument("invalid fixed-point format");
    if (stats != nullptr) stats->observe(raw, format);

    const int width = format.width();
    const __int128 modulus = static_cast<__int128>(1) << width;
    const __int128 mask = modulus - 1;
    __int128 wrapped = raw & mask;
    if ((wrapped & (static_cast<__int128>(1) << (width - 1))) != 0)
        wrapped -= modulus;
    return static_cast<std::int64_t>(wrapped);
}

// A defined arithmetic right shift.  This is floor(x / 2^shift), matching a
// two's-complement bit slice in SGen even for negative products.
[[nodiscard]] inline __int128 floor_shift_right(__int128 value, int shift)
{
    if (shift <= 0)
        return value * (static_cast<__int128>(1) << (-shift));
    if (value >= 0) return value >> shift;
    const __int128 bias = (static_cast<__int128>(1) << shift) - 1;
    return -(((-value) + bias) >> shift);
}

[[nodiscard]] inline std::int64_t requantize_raw(
    __int128 raw, int source_fractional_bits, const FixedFormat destination,
    QuantizationStats *stats = nullptr)
{
    const int shift = source_fractional_bits - destination.fractional_bits;
    return wrap_signed(floor_shift_right(raw, shift), destination, stats);
}

[[nodiscard]] inline std::int64_t quantize_double(
    double value, const FixedFormat format, QuantizationStats *stats = nullptr)
{
    if (!std::isfinite(value))
        throw std::invalid_argument("cannot quantize a non-finite value");
    const long double scaled =
        std::ldexp(static_cast<long double>(value), format.fractional_bits);
    // SGen's constant conversion drops the fractional part toward zero.
    const __int128 raw = static_cast<__int128>(scaled);
    return wrap_signed(raw, format, stats);
}

[[nodiscard]] inline double dequantize(std::int64_t raw,
                                       const FixedFormat format)
{
    return std::ldexp(static_cast<double>(raw), -format.fractional_bits);
}

// Convert a signed fixed-point raw value to an unsigned Torus word without a
// floating-point round trip. Increasing the fractional width is an exact left
// shift; decreasing it is a defined arithmetic right shift. The final mask
// implements reduction modulo 2^torus_bits.
[[nodiscard]] inline std::uint64_t fixed_raw_to_torus(
    std::int64_t raw, int source_fractional_bits, int torus_bits)
{
    if (source_fractional_bits < 0)
        throw std::invalid_argument("negative source fractional width");
    if (torus_bits < 1 || torus_bits > 64)
        throw std::invalid_argument("Torus width must be between 1 and 64");

    const __int128 scaled = floor_shift_right(
        raw, source_fractional_bits - torus_bits);
    std::uint64_t result = static_cast<std::uint64_t>(scaled);
    if (torus_bits < 64)
        result &= (std::uint64_t{1} << torus_bits) - 1;
    return result;
}

[[nodiscard]] inline std::int64_t add_raw(std::int64_t lhs, std::int64_t rhs,
                                          const FixedFormat format,
                                          QuantizationStats *stats = nullptr)
{
    return wrap_signed(static_cast<__int128>(lhs) + rhs, format, stats);
}

[[nodiscard]] inline std::int64_t sub_raw(std::int64_t lhs, std::int64_t rhs,
                                          const FixedFormat format,
                                          QuantizationStats *stats = nullptr)
{
    return wrap_signed(static_cast<__int128>(lhs) - rhs, format, stats);
}

[[nodiscard]] inline std::int64_t multiply_raw(
    std::int64_t lhs, const FixedFormat lhs_format, std::int64_t rhs,
    const FixedFormat rhs_format, const FixedFormat destination,
    QuantizationStats *stats = nullptr)
{
    return requantize_raw(static_cast<__int128>(lhs) * rhs,
                          lhs_format.fractional_bits +
                              rhs_format.fractional_bits,
                          destination, stats);
}

struct FixedComplex {
    std::int64_t real = 0;
    std::int64_t imag = 0;
};

struct PackedComplex {
    std::int32_t real = 0;
    std::int32_t imag = 0;
};

}  // namespace fpt

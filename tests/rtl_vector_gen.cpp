#include <cmath>
#include <bit>
#include <algorithm>
#include <cstdint>
#include <fstream>
#include <numbers>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

#include "fpt/fixed_point.hpp"
#include "fpt/fft.hpp"

namespace {

constexpr fpt::FixedFormat data_format{18, 12};
constexpr fpt::FixedFormat twiddle_format{2, 24};
constexpr fpt::FixedFormat mac_a_format{18, 20};
constexpr fpt::FixedFormat mac_b_format{8, 24};
constexpr fpt::FixedFormat accumulator_format{27, 14};

fpt::FixedComplex gauss_multiply(fpt::FixedComplex value, std::int64_t c,
                                 std::int64_t c_minus_d,
                                 std::int64_t c_plus_d)
{
    const std::int64_t a_minus_b =
        fpt::sub_raw(value.real, value.imag, data_format);
    const std::int64_t z = fpt::multiply_raw(
        a_minus_b, data_format, c, twiddle_format, data_format);
    const std::int64_t x = fpt::multiply_raw(
        value.imag, data_format, c_minus_d, twiddle_format, data_format);
    const std::int64_t y = fpt::multiply_raw(
        value.real, data_format, c_plus_d, twiddle_format, data_format);
    return {fpt::add_raw(x, z, data_format),
            fpt::sub_raw(y, z, data_format)};
}

std::int64_t scaled_sum(std::int64_t lhs, std::int64_t rhs)
{
    return fpt::wrap_signed(fpt::floor_shift_right(
                                static_cast<__int128>(lhs) + rhs, 1),
                            data_format);
}

std::int64_t scaled_difference(std::int64_t lhs, std::int64_t rhs)
{
    return fpt::wrap_signed(fpt::floor_shift_right(
                                static_cast<__int128>(lhs) - rhs, 1),
                            data_format);
}

fpt::FixedComplex complex_mac(fpt::FixedComplex a, fpt::FixedComplex b,
                              fpt::FixedComplex accumulator)
{
    const int product_fractional = mac_a_format.fractional_bits +
                                   mac_b_format.fractional_bits;
    const std::int64_t product_real = fpt::requantize_raw(
        static_cast<__int128>(a.real) * b.real -
            static_cast<__int128>(a.imag) * b.imag,
        product_fractional, accumulator_format);
    const std::int64_t product_imag = fpt::requantize_raw(
        static_cast<__int128>(a.real) * b.imag +
            static_cast<__int128>(a.imag) * b.real,
        product_fractional, accumulator_format);
    return {fpt::add_raw(accumulator.real, product_real, accumulator_format),
            fpt::add_raw(accumulator.imag, product_imag,
                         accumulator_format)};
}

std::uint32_t rotate_subtract(
    const std::array<std::uint32_t, 32> &polynomial, int index, int exponent)
{
    constexpr int polynomial_size = 32;
    const int offset = exponent & (polynomial_size - 1);
    const int source_index = (index - offset) & (polynomial_size - 1);
    const bool negate = exponent < polynomial_size ? index < offset
                                                   : index >= offset;
    const std::uint32_t rotated =
        negate ? std::uint32_t{0} - polynomial[source_index]
               : polynomial[source_index];
    return rotated - polynomial[index];
}

std::int32_t decompose_torus(std::uint32_t value, int level)
{
    constexpr int levels = 3;
    constexpr int base_bits = 6;
    constexpr std::uint32_t half_base = 1U << (base_bits - 1);
    constexpr std::uint32_t mask = (1U << base_bits) - 1;
    constexpr int remaining_bits = 32 - levels * base_bits;
    constexpr std::uint32_t round_offset = 1U << (remaining_bits - 1);
    constexpr std::uint32_t decomposition_offset =
        (half_base << (32 - base_bits)) +
        (half_base << (32 - 2 * base_bits)) +
        (half_base << (32 - 3 * base_bits));
    const std::uint32_t biased = value + decomposition_offset + round_offset;
    const int shift = 32 - (level + 1) * base_bits;
    return static_cast<std::int32_t>((biased >> shift) & mask) - half_base;
}

std::size_t reverse_bits(std::size_t value, int width)
{
    std::size_t result = 0;
    for (int bit = 0; bit < width; ++bit) {
        result = (result << 1) | (value & 1U);
        value >>= 1;
    }
    return result;
}

void cyclic_fft(std::vector<fpt::FixedComplex> &values, bool inverse = false)
{
    const int log_points = static_cast<int>(std::countr_zero(values.size()));
    for (std::size_t i = 0; i < values.size(); ++i) {
        const std::size_t j = reverse_bits(i, log_points);
        if (i < j) std::swap(values[i], values[j]);
    }
    for (std::size_t length = 2; length <= values.size(); length <<= 1) {
        const std::size_t half = length / 2;
        for (std::size_t start = 0; start < values.size(); start += length) {
            for (std::size_t j = 0; j < half; ++j) {
                const double direction = inverse ? 1.0 : -1.0;
                const double angle = direction * 2.0 * std::numbers::pi *
                                     static_cast<double>(j) /
                                     static_cast<double>(length);
                const double c_double = std::cos(angle);
                const double d_double = std::sin(angle);
                const auto odd = gauss_multiply(
                    values[start + j + half],
                    fpt::quantize_double(c_double, twiddle_format),
                    fpt::quantize_double(c_double - d_double, twiddle_format),
                    fpt::quantize_double(c_double + d_double, twiddle_format));
                const auto even = values[start + j];
                values[start + j] =
                    {fpt::add_raw(even.real, odd.real, data_format),
                     fpt::add_raw(even.imag, odd.imag, data_format)};
                values[start + j + half] =
                    {fpt::sub_raw(even.real, odd.real, data_format),
                     fpt::sub_raw(even.imag, odd.imag, data_format)};
            }
        }
    }
}

}  // namespace

int main(int argc, char **argv)
{
    if (argc != 15)
        throw std::invalid_argument(
            "expected all RTL vector and twiddle output paths");
    std::ofstream output(argv[1]);
    if (!output) throw std::runtime_error("could not open vector output");

    std::mt19937_64 generator(0x46505420221635ULL);
    const std::int64_t data_limit = std::int64_t{1} << 27;
    std::uniform_int_distribution<std::int64_t> data_distribution(
        -data_limit, data_limit - 1);
    std::uniform_real_distribution<double> angle_distribution(
        -std::numbers::pi, std::numbers::pi);

    for (int vector = 0; vector < 256; ++vector) {
        const fpt::FixedComplex even{data_distribution(generator),
                                     data_distribution(generator)};
        const fpt::FixedComplex odd{data_distribution(generator),
                                    data_distribution(generator)};
        const double angle = angle_distribution(generator);
        const double c_double = std::cos(angle);
        const double d_double = std::sin(angle);
        const std::int64_t c =
            fpt::quantize_double(c_double, twiddle_format);
        const std::int64_t c_minus_d =
            fpt::quantize_double(c_double - d_double, twiddle_format);
        const std::int64_t c_plus_d =
            fpt::quantize_double(c_double + d_double, twiddle_format);
        const auto twiddled =
            gauss_multiply(odd, c, c_minus_d, c_plus_d);

        const fpt::FixedComplex upper{
            fpt::add_raw(even.real, twiddled.real, data_format),
            fpt::add_raw(even.imag, twiddled.imag, data_format)};
        const fpt::FixedComplex lower{
            fpt::sub_raw(even.real, twiddled.real, data_format),
            fpt::sub_raw(even.imag, twiddled.imag, data_format)};
        const fpt::FixedComplex scaled_upper{
            scaled_sum(even.real, twiddled.real),
            scaled_sum(even.imag, twiddled.imag)};
        const fpt::FixedComplex scaled_lower{
            scaled_difference(even.real, twiddled.real),
            scaled_difference(even.imag, twiddled.imag)};

        output << even.real << ' ' << even.imag << ' ' << odd.real << ' '
               << odd.imag << ' ' << c << ' ' << c_minus_d << ' '
               << c_plus_d << ' ' << upper.real << ' ' << upper.imag << ' '
               << lower.real << ' ' << lower.imag << ' '
               << scaled_upper.real << ' ' << scaled_upper.imag << ' '
               << scaled_lower.real << ' ' << scaled_lower.imag << '\n';
    }

    std::ofstream mac_output(argv[2]);
    if (!mac_output) throw std::runtime_error("could not open MAC vectors");
    std::uniform_int_distribution<std::int64_t> a_distribution(
        -(std::int64_t{1} << 27), (std::int64_t{1} << 27) - 1);
    std::uniform_int_distribution<std::int64_t> b_distribution(
        -(std::int64_t{1} << 29), (std::int64_t{1} << 29) - 1);
    std::uniform_int_distribution<std::int64_t> accumulator_distribution(
        -(std::int64_t{1} << 39), (std::int64_t{1} << 39) - 1);
    for (int vector = 0; vector < 256; ++vector) {
        const std::int64_t a_real = a_distribution(generator);
        const std::int64_t a_imag = a_distribution(generator);
        const std::int64_t b_real = b_distribution(generator);
        const std::int64_t b_imag = b_distribution(generator);
        const std::int64_t accumulator_real =
            accumulator_distribution(generator);
        const std::int64_t accumulator_imag =
            accumulator_distribution(generator);
        const auto result = complex_mac(
            {a_real, a_imag}, {b_real, b_imag},
            {accumulator_real, accumulator_imag});
        mac_output << a_real << ' ' << a_imag << ' ' << b_real << ' '
                   << b_imag << ' ' << accumulator_real << ' '
                   << accumulator_imag << ' ' << result.real << ' '
                   << result.imag << '\n';
    }

    constexpr int external_points = 16;
    constexpr int external_rows = 6;
    constexpr int external_components = 2;
    std::ofstream external_output(argv[11]);
    if (!external_output)
        throw std::runtime_error("could not open External Product vectors");
    std::array<std::array<fpt::FixedComplex, external_points>,
               external_components>
        external_accumulators{};
    for (int row = 0; row < external_rows; ++row) {
        for (int point = 0; point < external_points; ++point) {
            const fpt::FixedComplex a{a_distribution(generator),
                                      a_distribution(generator)};
            std::array<fpt::FixedComplex, external_components> b;
            for (int component = 0; component < external_components;
                 ++component) {
                b[component] = {b_distribution(generator),
                                b_distribution(generator)};
                external_accumulators[component][point] = complex_mac(
                    a, b[component],
                    row == 0 ? fpt::FixedComplex{}
                             : external_accumulators[component][point]);
            }
            external_output << a.real << ' ' << a.imag;
            for (const auto value : b)
                external_output << ' ' << value.real << ' ' << value.imag;
            for (int component = 0; component < external_components;
                 ++component)
                external_output
                    << ' ' << external_accumulators[component][point].real
                    << ' ' << external_accumulators[component][point].imag;
            external_output << '\n';
        }
    }

    constexpr int cmux_polynomial_size = 32;
    constexpr int cmux_points = cmux_polynomial_size / 2;
    constexpr int cmux_components = 2;
    constexpr int cmux_levels = 3;
    std::ofstream cmux_output(argv[12]);
    if (!cmux_output)
        throw std::runtime_error("could not open CMUX frontend vectors");
    std::array<std::array<std::uint32_t, cmux_polynomial_size>,
               cmux_components>
        cmux_accumulator;
    for (int index = 0; index < cmux_polynomial_size; ++index) {
        for (int component = 0; component < cmux_components; ++component)
            cmux_accumulator[component][index] =
                static_cast<std::uint32_t>(generator());
        cmux_output << cmux_accumulator[0][index] << ' '
                    << cmux_accumulator[1][index] << '\n';
    }
    for (int component = 0; component < cmux_components; ++component) {
        for (int level = 0; level < cmux_levels; ++level) {
            const int exponent = 7 + component * 11 + level * 5;
            for (int point = 0; point < cmux_points; ++point) {
                const auto low = decompose_torus(
                    rotate_subtract(cmux_accumulator[component], point,
                                    exponent),
                    level);
                const auto high = decompose_torus(
                    rotate_subtract(cmux_accumulator[component],
                                    point + cmux_points, exponent),
                    level);
                cmux_output << component << ' ' << level << ' ' << exponent
                            << ' ' << point << ' '
                            << static_cast<std::int64_t>(low) * (1LL << 20)
                            << ' '
                            << static_cast<std::int64_t>(high) * (1LL << 20)
                            << '\n';
            }
        }
    }
    std::uniform_int_distribution<std::int64_t> inverse_distribution(
        -(std::int64_t{1} << 20), (std::int64_t{1} << 20) - 1);
    for (int point = 0; point < cmux_points; ++point) {
        cmux_output << point;
        std::array<fpt::FixedComplex, cmux_components> update;
        for (int component = 0; component < cmux_components; ++component) {
            update[component] = {inverse_distribution(generator),
                                 inverse_distribution(generator)};
            cmux_output << ' ' << update[component].real << ' '
                        << update[component].imag;
            cmux_accumulator[component][point] += static_cast<std::uint32_t>(
                update[component].real * (1LL << 18));
            cmux_accumulator[component][point + cmux_points] +=
                static_cast<std::uint32_t>(update[component].imag *
                                           (1LL << 18));
        }
        for (int component = 0; component < cmux_components; ++component)
            cmux_output << ' ' << cmux_accumulator[component][point] << ' '
                        << cmux_accumulator[component][point + cmux_points];
        cmux_output << '\n';
    }

    std::ofstream cmux_engine_output(argv[13]);
    std::ofstream cmux_twiddle_output(argv[14]);
    if (!cmux_engine_output || !cmux_twiddle_output)
        throw std::runtime_error("could not open CMUX engine vectors");
    std::array<std::array<std::uint32_t, cmux_polynomial_size>,
               cmux_components>
        engine_accumulator;
    for (int index = 0; index < cmux_polynomial_size; ++index) {
        for (int component = 0; component < cmux_components; ++component)
            engine_accumulator[component][index] =
                static_cast<std::uint32_t>(generator());
        cmux_engine_output << engine_accumulator[0][index] << ' '
                           << engine_accumulator[1][index] << '\n';
    }

    constexpr int engine_exponent = 13;
    constexpr int engine_rows = cmux_components * cmux_levels;
    using EngineKeyRow = std::array<
        std::array<fpt::FixedComplex, cmux_points>, cmux_components>;
    std::array<EngineKeyRow, engine_rows> engine_key;
    for (int row = 0; row < engine_rows; ++row) {
        for (int point = 0; point < cmux_points; ++point) {
            for (int component = 0; component < cmux_components; ++component) {
                engine_key[row][component][point] = {
                    b_distribution(generator), b_distribution(generator)};
                cmux_engine_output
                    << engine_key[row][component][point].real << ' '
                    << engine_key[row][component][point].imag
                    << (component + 1 == cmux_components ? '\n' : ' ');
            }
        }
    }

    fpt::NegacyclicFFT engine_forward_plan(
        {cmux_polynomial_size, mac_a_format, 4, {}});
    fpt::NegacyclicFFT engine_inverse_plan(
        {cmux_polynomial_size, accumulator_format, 4, {}});
    std::array<std::vector<fpt::FixedComplex>, cmux_components>
        engine_spectrum;
    for (auto &component : engine_spectrum)
        component.assign(cmux_points, {});
    int engine_row = 0;
    for (int component = 0; component < cmux_components; ++component) {
        for (int level = 0; level < cmux_levels; ++level, ++engine_row) {
            std::array<std::int64_t, cmux_polynomial_size> digits;
            for (int index = 0; index < cmux_polynomial_size; ++index)
                digits[index] = decompose_torus(
                    rotate_subtract(engine_accumulator[component], index,
                                    engine_exponent),
                    level);
            const auto transformed = engine_forward_plan.forward_integer(digits);
            for (int output_component = 0;
                 output_component < cmux_components; ++output_component)
                for (int point = 0; point < cmux_points; ++point)
                    engine_spectrum[output_component][point] = complex_mac(
                        transformed.values[point],
                        engine_key[engine_row][output_component][point],
                        engine_spectrum[output_component][point]);
        }
    }
    for (int component = 0; component < cmux_components; ++component) {
        fpt::QuantizedSpectrum spectrum;
        spectrum.values = engine_spectrum[component];
        spectrum.format = accumulator_format;
        const auto inverse = engine_inverse_plan.inverse(spectrum);
        for (int index = 0; index < cmux_polynomial_size; ++index) {
            const auto normalized_raw = static_cast<std::int64_t>(
                std::floor(std::ldexp(inverse[index],
                                      accumulator_format.fractional_bits)));
            engine_accumulator[component][index] +=
                static_cast<std::uint32_t>(normalized_raw * (1LL << 18));
        }
    }
    for (int index = 0; index < cmux_polynomial_size; ++index)
        cmux_engine_output << engine_accumulator[0][index] << ' '
                           << engine_accumulator[1][index] << '\n';

    const auto write_twiddle = [&](double angle,
                                   const fpt::FixedFormat format) {
        const double c = std::cos(angle);
        const double d = std::sin(angle);
        cmux_twiddle_output << fpt::quantize_double(c, format) << ' '
                            << fpt::quantize_double(c - d, format) << ' '
                            << fpt::quantize_double(c + d, format) << '\n';
    };
    constexpr fpt::FixedFormat forward_twiddle_format{2, 32};
    constexpr fpt::FixedFormat inverse_twiddle_format{2, 35};
    for (int index = 0; index < cmux_points / 2; ++index)
        write_twiddle(-2.0 * std::numbers::pi * index / cmux_points,
                      forward_twiddle_format);
    for (int index = 0; index < cmux_points; ++index)
        write_twiddle(std::numbers::pi * index / cmux_polynomial_size,
                      forward_twiddle_format);
    for (int index = 0; index < cmux_points / 2; ++index)
        write_twiddle(2.0 * std::numbers::pi * index / cmux_points,
                      inverse_twiddle_format);
    for (int index = 0; index < cmux_points; ++index)
        write_twiddle(-std::numbers::pi * index / cmux_polynomial_size,
                      inverse_twiddle_format);

    constexpr std::size_t fft_points = 16;
    std::ofstream fft_output(argv[3]);
    std::ofstream twiddle_output(argv[4]);
    if (!fft_output || !twiddle_output)
        throw std::runtime_error("could not open FFT RTL vectors");
    for (std::size_t index = 0; index < fft_points / 2; ++index) {
        const double angle = -2.0 * std::numbers::pi *
                             static_cast<double>(index) /
                             static_cast<double>(fft_points);
        const double c_double = std::cos(angle);
        const double d_double = std::sin(angle);
        twiddle_output
            << fpt::quantize_double(c_double, twiddle_format) << ' '
            << fpt::quantize_double(c_double - d_double, twiddle_format) << ' '
            << fpt::quantize_double(c_double + d_double, twiddle_format) << '\n';
    }
    for (int frame = 0; frame < 16; ++frame) {
        std::vector<fpt::FixedComplex> input(fft_points);
        for (auto &value : input)
            value = {data_distribution(generator), data_distribution(generator)};
        auto expected = input;
        cyclic_fft(expected);
        for (std::size_t index = 0; index < fft_points; ++index)
            fft_output << input[index].real << ' ' << input[index].imag << ' '
                       << expected[index].real << ' '
                       << expected[index].imag << '\n';
    }

    // A streaming radix-2^k FFT and the radix-2 oracle can legitimately
    // diverge after an intermediate fixed-width overflow: later fractional
    // multiplies make modular addition order observable. Keep this separate
    // set within the no-overflow range so it checks SGen's arithmetic and
    // stream ordering rather than associativity after overflow.
    std::ofstream sgen_fft_output(argv[10]);
    if (!sgen_fft_output)
        throw std::runtime_error("could not open SGen FFT RTL vectors");
    const std::int64_t sgen_data_limit = std::int64_t{1} << 23;
    std::uniform_int_distribution<std::int64_t> sgen_data_distribution(
        -sgen_data_limit, sgen_data_limit - 1);
    for (int frame = 0; frame < 16; ++frame) {
        std::vector<fpt::FixedComplex> input(fft_points);
        for (auto &value : input)
            value = {sgen_data_distribution(generator),
                     sgen_data_distribution(generator)};
        auto expected = input;
        cyclic_fft(expected);
        for (std::size_t index = 0; index < fft_points; ++index)
            sgen_fft_output << input[index].real << ' ' << input[index].imag
                            << ' ' << expected[index].real << ' '
                            << expected[index].imag << '\n';
    }

    std::ofstream tangent_output(argv[5]);
    std::ofstream twist_output(argv[6]);
    if (!tangent_output || !twist_output)
        throw std::runtime_error("could not open tangent FFT RTL vectors");
    fpt::NegacyclicFFT tangent_plan(
        {2 * fft_points, data_format, 4, {}});
    for (std::size_t index = 0; index < fft_points; ++index) {
        const double angle = std::numbers::pi * static_cast<double>(index) /
                             static_cast<double>(2 * fft_points);
        const double c_double = std::cos(angle);
        const double d_double = std::sin(angle);
        twist_output
            << fpt::quantize_double(c_double, twiddle_format) << ' '
            << fpt::quantize_double(c_double - d_double, twiddle_format) << ' '
            << fpt::quantize_double(c_double + d_double, twiddle_format) << '\n';
    }
    for (int frame = 0; frame < 8; ++frame) {
        std::vector<std::int64_t> raw(2 * fft_points);
        std::vector<double> coefficients(2 * fft_points);
        for (std::size_t index = 0; index < raw.size(); ++index) {
            raw[index] = data_distribution(generator);
            coefficients[index] = fpt::dequantize(raw[index], data_format);
        }
        const auto expected = tangent_plan.forward(coefficients);
        for (std::size_t index = 0; index < fft_points; ++index)
            tangent_output << raw[index] << ' ' << raw[index + fft_points]
                           << ' ' << expected.values[index].real << ' '
                           << expected.values[index].imag << '\n';
    }

    std::ofstream ifft_output(argv[7]);
    std::ofstream ifft_twiddle_output(argv[8]);
    std::ofstream untwist_output(argv[9]);
    if (!ifft_output || !ifft_twiddle_output || !untwist_output)
        throw std::runtime_error("could not open inverse FFT RTL vectors");
    for (std::size_t index = 0; index < fft_points / 2; ++index) {
        const double angle = 2.0 * std::numbers::pi *
                             static_cast<double>(index) /
                             static_cast<double>(fft_points);
        const double c_double = std::cos(angle);
        const double d_double = std::sin(angle);
        ifft_twiddle_output
            << fpt::quantize_double(c_double, twiddle_format) << ' '
            << fpt::quantize_double(c_double - d_double, twiddle_format) << ' '
            << fpt::quantize_double(c_double + d_double, twiddle_format) << '\n';
    }
    for (std::size_t index = 0; index < fft_points; ++index) {
        const double angle = -std::numbers::pi * static_cast<double>(index) /
                             static_cast<double>(2 * fft_points);
        const double c_double = std::cos(angle);
        const double d_double = std::sin(angle);
        untwist_output
            << fpt::quantize_double(c_double, twiddle_format) << ' '
            << fpt::quantize_double(c_double - d_double, twiddle_format) << ' '
            << fpt::quantize_double(c_double + d_double, twiddle_format) << '\n';
    }
    for (int frame = 0; frame < 8; ++frame) {
        std::vector<fpt::FixedComplex> input(fft_points);
        for (auto &value : input)
            value = {data_distribution(generator), data_distribution(generator)};
        auto expected = input;
        cyclic_fft(expected, true);
        for (std::size_t index = 0; index < fft_points; ++index) {
            const double angle = -std::numbers::pi *
                                 static_cast<double>(index) /
                                 static_cast<double>(2 * fft_points);
            const double c_double = std::cos(angle);
            const double d_double = std::sin(angle);
            const auto untwisted = gauss_multiply(
                expected[index],
                fpt::quantize_double(c_double, twiddle_format),
                fpt::quantize_double(c_double - d_double, twiddle_format),
                fpt::quantize_double(c_double + d_double, twiddle_format));
            const auto low = fpt::wrap_signed(
                fpt::floor_shift_right(untwisted.real, 4), data_format);
            const auto high = fpt::wrap_signed(
                fpt::floor_shift_right(untwisted.imag, 4), data_format);
            ifft_output << input[index].real << ' ' << input[index].imag << ' '
                        << low << ' ' << high << '\n';
        }
    }
}

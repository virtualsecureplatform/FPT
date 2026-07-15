#include "fpt/fft.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <random>
#include <stdexcept>
#include <vector>

namespace {

constexpr std::size_t polynomial_size = 1024;
constexpr std::size_t transform_size = polynomial_size / 2;
constexpr int components = 2;
constexpr int levels = 2;
constexpr int rows = components * levels;
constexpr int base_bits = 10;
constexpr int exponent = 733;
constexpr fpt::FixedFormat forward_format{18, 12};
constexpr fpt::FixedFormat key_format{8, 19};
constexpr fpt::FixedFormat inverse_format{27, 3};

using Polynomial = std::array<std::uint32_t, polynomial_size>;
using Accumulator = std::array<Polynomial, components>;
using KeyRow = std::array<std::vector<fpt::FixedComplex>, components>;
using Key = std::array<KeyRow, rows>;

std::uint32_t rotate_subtract(const Polynomial &polynomial, std::size_t index)
{
    const std::size_t offset = exponent & (polynomial_size - 1);
    const std::size_t source_index =
        (index - offset) & (polynomial_size - 1);
    const bool negate = exponent < static_cast<int>(polynomial_size)
                            ? index < offset
                            : index >= offset;
    const std::uint32_t rotated =
        negate ? std::uint32_t{0} - polynomial[source_index]
               : polynomial[source_index];
    return rotated - polynomial[index];
}

std::int64_t decompose_torus(std::uint32_t value, int level)
{
    constexpr std::uint32_t half_base = 1U << (base_bits - 1);
    constexpr std::uint32_t mask = (1U << base_bits) - 1;
    constexpr int remaining_bits = 32 - levels * base_bits;
    constexpr std::uint32_t round_offset = 1U << (remaining_bits - 1);
    constexpr std::uint32_t decomposition_offset =
        (half_base << (32 - base_bits)) +
        (half_base << (32 - 2 * base_bits));
    const std::uint32_t biased = value + decomposition_offset + round_offset;
    const int shift = 32 - (level + 1) * base_bits;
    return static_cast<std::int64_t>((biased >> shift) & mask) - half_base;
}

void apply_cmux(Accumulator &accumulator, const Key &key)
{
    const fpt::NegacyclicFFT forward_plan(
        {polynomial_size, forward_format, 4, {}});
    const std::vector<bool> scale_every_inverse_stage(9, true);
    const fpt::NegacyclicFFT inverse_plan(
        {polynomial_size, inverse_format, 4, scale_every_inverse_stage});

    std::array<std::vector<fpt::FixedComplex>, components> spectra;
    for (auto &spectrum : spectra) spectrum.assign(transform_size, {});

    int row = 0;
    for (int input_component = 0; input_component < components;
         ++input_component) {
        for (int level = 0; level < levels; ++level, ++row) {
            std::array<std::int64_t, polynomial_size> digits;
            for (std::size_t index = 0; index < polynomial_size; ++index) {
                digits[index] = decompose_torus(
                    rotate_subtract(accumulator[input_component], index),
                    level);
            }
            const auto transformed = forward_plan.forward_integer(digits);
            for (int output_component = 0; output_component < components;
                 ++output_component) {
                fpt::QuantizedSpectrum key_spectrum;
                key_spectrum.values = key[row][output_component];
                key_spectrum.format = key_format;
                fpt::multiply_accumulate_spectra(
                    spectra[output_component], inverse_format, transformed,
                    key_spectrum);
            }
        }
    }

    for (int component = 0; component < components; ++component) {
        fpt::QuantizedSpectrum spectrum;
        spectrum.values = spectra[component];
        spectrum.format = inverse_format;
        const auto update = inverse_plan.inverse(spectrum);
        for (std::size_t index = 0; index < polynomial_size; ++index) {
            const auto raw = static_cast<std::int64_t>(
                std::floor(std::ldexp(update[index],
                                      inverse_format.fractional_bits)));
            accumulator[component][index] +=
                static_cast<std::uint32_t>(raw * (std::int64_t{1} << 29));
        }
    }
}

}  // namespace

int main(int argc, char **argv)
{
    try {
        if (argc != 2)
            throw std::invalid_argument(
                "usage: fpt_paper_cmux_vector_gen OUTPUT_TXT");
        std::ofstream output(argv[1]);
        if (!output)
            throw std::runtime_error("could not open paper CMUX vector output");

        std::mt19937_64 generator(0x4650545f434d5558ULL);
        Accumulator accumulator;
        for (std::size_t index = 0; index < polynomial_size; ++index) {
            for (int component = 0; component < components; ++component) {
                accumulator[component][index] =
                    static_cast<std::uint32_t>(generator());
                output << accumulator[component][index]
                       << (component + 1 == components ? '\n' : ' ');
            }
        }
        const Accumulator initial = accumulator;

        Key key;
        std::uniform_int_distribution<int> key_quarters(-2, 2);
        for (int row = 0; row < rows; ++row) {
            for (auto &component : key[row])
                component.resize(transform_size);
            for (std::size_t point = 0; point < transform_size; ++point) {
                for (int component = 0; component < components; ++component) {
                    int real = key_quarters(generator);
                    int imag = key_quarters(generator);
                    if (real == 0 && imag == 0)
                        real = ((row + component + point) & 1U) ? 1 : -1;
                    key[row][component][point] = {
                        static_cast<std::int64_t>(real) * (1LL << 17),
                        static_cast<std::int64_t>(imag) * (1LL << 17)};
                    output << key[row][component][point].real << ' '
                           << key[row][component][point].imag
                           << (component + 1 == components ? '\n' : ' ');
                }
            }
        }

        apply_cmux(accumulator, key);
        std::size_t changed = 0;
        for (std::size_t index = 0; index < polynomial_size; ++index) {
            for (int component = 0; component < components; ++component) {
                if (accumulator[component][index] != initial[component][index])
                    ++changed;
                output << accumulator[component][index]
                       << (component + 1 == components ? '\n' : ' ');
            }
        }
        std::cout << "Generated Set-II nonzero CMUX vectors; " << changed
                  << '/' << polynomial_size * components
                  << " accumulator coefficients changed\n";
        return 0;
    }
    catch (const std::exception &exception) {
        std::cerr << exception.what() << '\n';
        return 1;
    }
}

#include "fpt/fft.hpp"

#include <array>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string_view>
#include <vector>

namespace {

constexpr std::size_t polynomial_size = 1024;
constexpr std::size_t transform_size = polynomial_size / 2;
constexpr int contexts = 16;
constexpr int components = 2;
constexpr int levels = 2;
constexpr int rows = components * levels;
constexpr int base_bits = 10;
constexpr int exponent_bits = 11;
constexpr int modulus_shift = 32 - exponent_bits;

using Polynomial = std::array<std::uint32_t, polynomial_size>;
using Accumulator = std::array<Polynomial, components>;
using KeyRow = std::array<std::vector<fpt::FixedComplex>, components>;
using Key = std::array<KeyRow, rows>;

fpt::ArithmeticProfile arithmetic_profile(std::string_view name)
{
    if (name == "paper-set-ii")
        return fpt::ArithmeticProfile::parameter_set_ii();
    if (name == "tfhepp-hardware")
        return fpt::ArithmeticProfile::tfhepp_hardware();
    throw std::invalid_argument("unknown RTL arithmetic profile");
}

std::uint32_t rotate_subtract(const Polynomial &polynomial, std::size_t index,
                              int exponent)
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

Accumulator initialize_accumulator(std::uint32_t test_vector, int body)
{
    Accumulator accumulator{};
    const int exponent = (-body) & ((1 << exponent_bits) - 1);
    const int exponent_low = exponent & (polynomial_size - 1);
    const bool exponent_high = (exponent >> 10) != 0;
    for (std::size_t index = 0; index < polynomial_size; ++index) {
        const bool negate = exponent_high ^
                            (index < static_cast<std::size_t>(exponent_low));
        accumulator[1][index] =
            negate ? std::uint32_t{0} - test_vector : test_vector;
    }
    return accumulator;
}

void apply_cmux(Accumulator &accumulator, const Key &key, int exponent,
                const fpt::ArithmeticProfile &profile)
{
    const fpt::NegacyclicFFT forward_plan(
        {polynomial_size, profile.forward_fft, profile.twiddle_width_reduction,
         {}});
    const std::vector<bool> scale_every_inverse_stage(9, true);
    const fpt::NegacyclicFFT inverse_plan(
        {polynomial_size, profile.inverse_fft, profile.twiddle_width_reduction,
         scale_every_inverse_stage});

    std::array<std::vector<fpt::FixedComplex>, components> spectra;
    for (auto &spectrum : spectra) spectrum.assign(transform_size, {});

    int row = 0;
    for (int input_component = 0; input_component < components;
         ++input_component) {
        for (int level = 0; level < levels; ++level, ++row) {
            std::array<std::int64_t, polynomial_size> digits;
            for (std::size_t index = 0; index < polynomial_size; ++index) {
                digits[index] = decompose_torus(
                    rotate_subtract(
                        accumulator[input_component], index, exponent),
                    level);
            }
            const auto transformed = forward_plan.forward_integer(digits);
            for (int output_component = 0; output_component < components;
                 ++output_component) {
                fpt::QuantizedSpectrum key_spectrum;
                key_spectrum.values = key[row][output_component];
                key_spectrum.format = profile.bootstrapping_key;
                fpt::multiply_accumulate_spectra(
                    spectra[output_component], profile.inverse_fft,
                    transformed, key_spectrum);
            }
        }
    }

    for (int component = 0; component < components; ++component) {
        fpt::QuantizedSpectrum spectrum;
        spectrum.values = spectra[component];
        spectrum.format = profile.inverse_fft;
        const auto update = inverse_plan.inverse_raw(spectrum);
        const int torus_shift = 32 - profile.inverse_fft.fractional_bits;
        if (torus_shift < 0)
            throw std::invalid_argument(
                "inverse profile has more than 32 fractional bits");
        for (std::size_t index = 0; index < polynomial_size; ++index) {
            accumulator[component][index] += static_cast<std::uint32_t>(
                update[index] * (std::int64_t{1} << torus_shift));
        }
    }
}

std::vector<std::uint32_t> sample_extract(const Accumulator &accumulator)
{
    std::vector<std::uint32_t> result;
    result.reserve(polynomial_size + 1);
    result.push_back(accumulator[0][0]);
    for (std::size_t index = polynomial_size - 1; index != 0; --index)
        result.push_back(std::uint32_t{0} - accumulator[0][index]);
    result.push_back(accumulator[1][0]);
    return result;
}

}  // namespace

int main(int argc, char **argv)
{
    try {
        if (argc != 2 && argc != 3)
            throw std::invalid_argument(
                "usage: fpt_paper_blind_rotate_vector_gen OUTPUT_TXT "
                "[ARITHMETIC_PROFILE]");
        const std::string_view profile_name =
            argc == 3 ? argv[2] : "paper-set-ii";
        const auto profile = arithmetic_profile(profile_name);
        std::ofstream output(argv[1]);
        if (!output)
            throw std::runtime_error(
                "could not open paper Blind Rotate vector output");

        std::mt19937_64 generator(0x4650545f42524654ULL);
        std::array<std::uint32_t, contexts> test_vectors;
        std::array<int, contexts> mask_exponents;
        std::array<int, contexts> body_exponents;
        for (int context = 0; context < contexts; ++context) {
            test_vectors[context] = static_cast<std::uint32_t>(generator());
            mask_exponents[context] = (37 + 113 * context) & 2047;
            body_exponents[context] = (19 + 79 * context) & 2047;
            const std::uint32_t raw_mask =
                static_cast<std::uint32_t>(mask_exponents[context])
                << modulus_shift;
            const std::uint32_t raw_body =
                static_cast<std::uint32_t>(body_exponents[context])
                << modulus_shift;
            output << test_vectors[context] << ' ' << raw_mask << ' '
                   << raw_body << '\n';
        }

        Key key;
        const int key_quarter_shift =
            profile.bootstrapping_key.fractional_bits - 2;
        if (key_quarter_shift < 0 || key_quarter_shift >= 62)
            throw std::invalid_argument(
                "bootstrapping-key format cannot represent quarter steps");
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
                        static_cast<std::int64_t>(real) *
                            (std::int64_t{1} << key_quarter_shift),
                        static_cast<std::int64_t>(imag) *
                            (std::int64_t{1} << key_quarter_shift)};
                    output << key[row][component][point].real << ' '
                           << key[row][component][point].imag
                           << (component + 1 == components ? '\n' : ' ');
                }
            }
        }

        std::size_t changed = 0;
        for (int context = 0; context < contexts; ++context) {
            auto accumulator = initialize_accumulator(
                test_vectors[context], body_exponents[context]);
            const auto initial = sample_extract(accumulator);
            apply_cmux(accumulator, key, mask_exponents[context], profile);
            const auto expected = sample_extract(accumulator);
            for (std::size_t index = 0; index < expected.size(); ++index) {
                if (expected[index] != initial[index]) ++changed;
                output << expected[index] << '\n';
            }
        }

        std::cout << "Generated " << profile_name
                  << " physical Blind Rotate vectors; "
                  << changed << '/' << contexts * (polynomial_size + 1)
                  << " sample-extracted coefficients changed\n";
        return changed == 0 ? 1 : 0;
    }
    catch (const std::exception &exception) {
        std::cerr << exception.what() << '\n';
        return 1;
    }
}

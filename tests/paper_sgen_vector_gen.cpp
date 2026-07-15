#include "fpt/fft.hpp"

#include <cstdint>
#include <fstream>
#include <iostream>
#include <random>
#include <stdexcept>
#include <vector>

namespace {

constexpr std::size_t polynomial_size = 1024;
constexpr std::size_t transform_size = polynomial_size / 2;
constexpr int frame_count = 4;
constexpr fpt::FixedFormat forward_format{18, 12};
constexpr fpt::FixedFormat inverse_format{27, 3};

void write_forward_vectors(std::ostream &output, std::mt19937_64 &generator)
{
    const fpt::NegacyclicFFT plan(
        {polynomial_size, forward_format, 4, {}});
    std::uniform_int_distribution<std::int64_t> digits(-512, 511);

    for (int frame = 0; frame < frame_count; ++frame) {
        std::vector<double> coefficients(polynomial_size);
        if (frame == 0) {
            coefficients[0] = 511.0;
            coefficients[transform_size + 1] = -512.0;
        }
        else {
            for (auto &coefficient : coefficients)
                coefficient = static_cast<double>(digits(generator));
        }

        const auto fixed = plan.forward(coefficients);
        const auto double_reference = plan.reference_forward(coefficients);
        const auto reference = plan.quantize_reference(double_reference);
        for (std::size_t index = 0; index < transform_size; ++index) {
            const auto low = fpt::quantize_double(
                coefficients[index], forward_format);
            const auto high = fpt::quantize_double(
                coefficients[index + transform_size], forward_format);
            output << low << ' ' << high << ' '
                   << fixed.values[index].real << ' '
                   << fixed.values[index].imag << ' '
                   << reference.values[index].real << ' '
                   << reference.values[index].imag << '\n';
        }
    }
}

void write_inverse_vectors(std::ostream &output, std::mt19937_64 &generator)
{
    const std::vector<bool> scale_every_stage(9, true);
    const fpt::NegacyclicFFT plan(
        {polynomial_size, inverse_format, 4, scale_every_stage});
    std::uniform_int_distribution<std::int64_t> raw_distribution(
        -(std::int64_t{1} << 18), (std::int64_t{1} << 18) - 1);

    for (int frame = 0; frame < frame_count; ++frame) {
        fpt::QuantizedSpectrum spectrum;
        spectrum.values.resize(transform_size);
        spectrum.format = inverse_format;
        std::vector<std::complex<double>> reference_input(transform_size);
        for (std::size_t index = 0; index < transform_size; ++index) {
            if (frame == 0) {
                spectrum.values[index] = index == 0
                    ? fpt::FixedComplex{std::int64_t{1} << 18,
                                        -(std::int64_t{1} << 17)}
                    : fpt::FixedComplex{};
            }
            else {
                spectrum.values[index] = {
                    raw_distribution(generator), raw_distribution(generator)};
            }
            reference_input[index] = {
                fpt::dequantize(spectrum.values[index].real, inverse_format),
                fpt::dequantize(spectrum.values[index].imag, inverse_format)};
        }

        const auto fixed = plan.inverse_raw(spectrum);
        const auto reference = plan.reference_inverse(reference_input);
        for (std::size_t index = 0; index < transform_size; ++index) {
            output << spectrum.values[index].real << ' '
                   << spectrum.values[index].imag << ' '
                   << fixed[index] << ' '
                   << fixed[index + transform_size]
                   << ' '
                   << fpt::quantize_double(reference[index], inverse_format)
                   << ' '
                   << fpt::quantize_double(
                          reference[index + transform_size], inverse_format)
                   << '\n';
        }
    }
}

}  // namespace

int main(int argc, char **argv)
{
    try {
        if (argc != 3)
            throw std::invalid_argument(
                "usage: fpt_paper_sgen_vector_gen FORWARD_TXT INVERSE_TXT");
        std::ofstream forward_output(argv[1]);
        std::ofstream inverse_output(argv[2]);
        if (!forward_output || !inverse_output)
            throw std::runtime_error("could not open paper SGen vector output");

        std::mt19937_64 generator(0x4650545f5347454eULL);
        write_forward_vectors(forward_output, generator);
        write_inverse_vectors(inverse_output, generator);
        std::cout << "Generated Set-II SGen numerical vectors\n";
        return 0;
    }
    catch (const std::exception &exception) {
        std::cerr << exception.what() << '\n';
        return 1;
    }
}

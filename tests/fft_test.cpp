#include <algorithm>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

#include "fpt/fft.hpp"

namespace {

void require(bool condition, const std::string &message)
{
    if (!condition) throw std::runtime_error(message);
}

double max_error(const std::vector<double> &lhs,
                 const std::vector<double> &rhs)
{
    require(lhs.size() == rhs.size(), "max_error size mismatch");
    double result = 0;
    for (std::size_t i = 0; i < lhs.size(); ++i)
        result = std::max(result, std::abs(lhs[i] - rhs[i]));
    return result;
}

void test_fixed_point_primitives()
{
    constexpr fpt::FixedFormat q3_2{3, 2};
    require(fpt::quantize_double(1.75, q3_2) == 7,
            "positive fixed-point conversion failed");
    require(fpt::quantize_double(-1.75, q3_2) == -7,
            "negative fixed-point conversion failed");
    require(fpt::quantize_double(4.0, q3_2) == -16,
            "two's-complement overflow must wrap");
    require(fpt::floor_shift_right(-7, 2) == -2,
            "negative product truncation must be an arithmetic shift");

    fpt::QuantizationStats stats;
    (void)fpt::quantize_double(4.0, q3_2, &stats);
    require(stats.overflows == 1, "overflow accounting failed");

    require(fpt::fixed_raw_to_torus(3, 3, 32) == UINT64_C(3) << 29,
            "positive fixed-point-to-Torus conversion failed");
    require(fpt::fixed_raw_to_torus(-3, 3, 32) ==
                ((UINT64_C(1) << 32) - (UINT64_C(3) << 29)),
            "negative fixed-point-to-Torus conversion failed");
    require(fpt::fixed_raw_to_torus(-257, 40, 32) ==
                UINT64_C(0xfffffffe),
            "narrow fixed-point-to-Torus conversion must shift arithmetically");
}

void test_reference_round_trip()
{
    fpt::NegacyclicFFT plan({16, {20, 20}, 4, {}});
    const std::vector<double> input{1,  -2, 3,  4, -5, 6,  7, -8,
                                    9, -10, 11, 0, 2,  -3, 5, 1};
    const auto spectrum = plan.reference_forward(input);
    const auto output = plan.reference_inverse(spectrum);
    require(max_error(input, output) < 1e-10,
            "double tangent FFT round trip failed");
}

void test_reference_negacyclic_product()
{
    constexpr std::size_t n = 32;
    fpt::NegacyclicFFT plan({n, {20, 20}, 4, {}});
    std::vector<double> lhs(n), rhs(n);
    for (std::size_t i = 0; i < n; ++i) {
        lhs[i] = static_cast<double>(static_cast<int>(i % 7) - 3);
        rhs[i] = static_cast<double>(static_cast<int>(i % 5) - 2);
    }
    auto lhs_fd = plan.reference_forward(lhs);
    auto rhs_fd = plan.reference_forward(rhs);
    for (std::size_t i = 0; i < lhs_fd.size(); ++i) lhs_fd[i] *= rhs_fd[i];
    const auto actual = plan.reference_inverse(lhs_fd);
    const auto expected = fpt::negacyclic_convolution_reference(lhs, rhs);
    require(max_error(actual, expected) < 1e-9,
            "folded/twisted FFT does not implement negacyclic convolution");
}

void test_paper_parameter_set_i_product()
{
    constexpr std::size_t n = 512;
    constexpr auto profile = fpt::ArithmeticProfile::parameter_set_i();
    fpt::NegacyclicFFT forward_plan(
        {n, profile.forward_fft, profile.twiddle_width_reduction, {}});
    fpt::NegacyclicFFT inverse_plan(
        {n, profile.inverse_fft, profile.twiddle_width_reduction, {}});

    std::mt19937 generator(0x465054U);
    std::uniform_int_distribution<int> distribution(-3, 3);
    std::vector<double> lhs(n), rhs(n);
    for (std::size_t i = 0; i < n; ++i) {
        lhs[i] = distribution(generator);
        rhs[i] = distribution(generator);
    }

    fpt::QuantizationStats stats;
    const auto lhs_fd = forward_plan.forward(lhs, &stats);
    const auto rhs_fd = forward_plan.forward(rhs, &stats);
    const auto product_fd =
        fpt::multiply_spectra(lhs_fd, rhs_fd, profile.inverse_fft, &stats);
    const auto actual = inverse_plan.inverse(product_fd, &stats);
    const auto expected = fpt::negacyclic_convolution_reference(lhs, rhs);
    const double error = max_error(actual, expected);

    require(stats.overflows == 0,
            "small-input parameter-set-I FFT unexpectedly overflowed");
    require(error < 0.25,
            "parameter-set-I fixed-point convolution error is too large: " +
                std::to_string(error));
}

void test_scaling_schedule_is_compensated()
{
    constexpr std::size_t n = 32;
    std::vector<bool> schedule{true, false, true, false};
    fpt::NegacyclicFFT forward_plan({n, {12, 16}, 4, schedule});
    fpt::NegacyclicFFT inverse_plan({n, {12, 16}, 4, schedule});
    std::vector<double> input(n);
    for (std::size_t i = 0; i < n; ++i)
        input[i] = static_cast<double>(static_cast<int>(i % 5) - 2);

    const auto spectrum = forward_plan.forward(input);
    require(spectrum.scale_exponent == 2,
            "forward scaling exponent was not recorded");
    const auto output = inverse_plan.inverse(spectrum);
    require(max_error(input, output) < 0.01,
            "stage scale compensation failed");
}

void test_inverse_normalizes_in_fixed_point()
{
    constexpr std::size_t n = 8;
    constexpr fpt::FixedFormat format{6, 4};
    fpt::NegacyclicFFT plan({n, format, 4, {}});
    fpt::QuantizedSpectrum spectrum;
    spectrum.values.resize(n / 2);
    spectrum.values[0].real = -7;
    spectrum.format = format;

    const auto raw = plan.inverse_raw(spectrum);
    const auto dequantized = plan.inverse(spectrum);
    require(raw[0] == -2,
            "inverse normalization must be a signed arithmetic shift");
    require(dequantized[0] == -0.125,
            "inverse must dequantize the normalized fixed-point result");
    for (std::size_t i = 0; i < raw.size(); ++i)
        require(dequantized[i] == fpt::dequantize(raw[i], format),
                "inverse raw and dequantized results disagree");
}

void test_paper_formats()
{
    constexpr auto set_i = fpt::ArithmeticProfile::parameter_set_i();
    constexpr auto set_ii = fpt::ArithmeticProfile::parameter_set_ii();
    static_assert(set_i.bootstrapping_key.width() == 26);
    static_assert(set_i.forward_fft.width() == 29);
    static_assert(set_i.inverse_fft.width() == 29);
    static_assert(set_ii.bootstrapping_key.width() == 27);
    static_assert(set_ii.forward_fft.width() == 30);
    static_assert(set_ii.inverse_fft.width() == 30);
}

}  // namespace

int main()
{
    try {
        test_fixed_point_primitives();
        test_reference_round_trip();
        test_reference_negacyclic_product();
        test_paper_parameter_set_i_product();
        test_scaling_schedule_is_compensated();
        test_inverse_normalizes_in_fixed_point();
        test_paper_formats();
        std::cout << "All fixed-point FFT tests passed.\n";
        return 0;
    }
    catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}

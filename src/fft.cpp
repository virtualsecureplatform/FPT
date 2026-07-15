#include "fpt/fft.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <numbers>
#include <stdexcept>

namespace fpt {
namespace {

[[nodiscard]] bool is_power_of_two(std::size_t value)
{
    return value != 0 && (value & (value - 1)) == 0;
}

[[nodiscard]] std::size_t reverse_bits(std::size_t value, int width)
{
    std::size_t result = 0;
    for (int i = 0; i < width; ++i) {
        result = (result << 1) | (value & 1U);
        value >>= 1;
    }
    return result;
}

[[nodiscard]] int count_true(const std::vector<bool> &values)
{
    int result = 0;
    for (bool value : values)
        if (value) ++result;
    return result;
}

}  // namespace

NegacyclicFFT::NegacyclicFFT(FFTConfig config)
    : polynomial_size_(config.polynomial_size),
      complex_size_(config.polynomial_size / 2),
      log_complex_size_(0),
      data_format_(config.data_format),
      twiddle_format_{2, config.data_format.width() -
                             config.twiddle_width_reduction - 2},
      scale_after_stage_(std::move(config.scale_after_stage)),
      stage_scale_exponent_(0)
{
    if (polynomial_size_ < 4 || (polynomial_size_ & 1U) != 0 ||
        !is_power_of_two(complex_size_))
        throw std::invalid_argument(
            "the polynomial size must be twice a power of two and at least 4");
    if (!data_format_.valid())
        throw std::invalid_argument("invalid FFT data format");
    if (!twiddle_format_.valid())
        throw std::invalid_argument("invalid FFT twiddle format");

    log_complex_size_ = static_cast<int>(std::countr_zero(complex_size_));
    if (scale_after_stage_.empty())
        scale_after_stage_.assign(log_complex_size_, false);
    if (scale_after_stage_.size() !=
        static_cast<std::size_t>(log_complex_size_))
        throw std::invalid_argument(
            "scale_after_stage must have log2(N/2) entries");
    stage_scale_exponent_ = count_true(scale_after_stage_);

    forward_twist_.reserve(complex_size_);
    inverse_twist_.reserve(complex_size_);
    for (std::size_t i = 0; i < complex_size_; ++i) {
        const double angle = std::numbers::pi * static_cast<double>(i) /
                             static_cast<double>(polynomial_size_);
        forward_twist_.push_back(make_twiddle(angle));
        inverse_twist_.push_back(make_twiddle(-angle));
    }
}

NegacyclicFFT::GaussTwiddle NegacyclicFFT::make_twiddle(double angle) const
{
    const double c = std::cos(angle);
    const double d = std::sin(angle);
    return {quantize_double(c, twiddle_format_),
            quantize_double(c - d, twiddle_format_),
            quantize_double(c + d, twiddle_format_)};
}

FixedComplex NegacyclicFFT::multiply_twiddle(
    FixedComplex value, const GaussTwiddle &twiddle,
    QuantizationStats *stats) const
{
    // Equation (6) in the paper.  C-D and C+D are pre-quantized just as they
    // can be precomputed for a constant-twiddle FPGA multiplier.
    const std::int64_t a_minus_b =
        sub_raw(value.real, value.imag, data_format_, stats);
    const std::int64_t z =
        multiply_raw(a_minus_b, data_format_, twiddle.c, twiddle_format_,
                     data_format_, stats);
    const std::int64_t x_part =
        multiply_raw(value.imag, data_format_, twiddle.c_minus_d,
                     twiddle_format_, data_format_, stats);
    const std::int64_t y_part =
        multiply_raw(value.real, data_format_, twiddle.c_plus_d,
                     twiddle_format_, data_format_, stats);
    return {add_raw(x_part, z, data_format_, stats),
            sub_raw(y_part, z, data_format_, stats)};
}

void NegacyclicFFT::fft_in_place(std::span<FixedComplex> values, bool inverse,
                                 QuantizationStats *stats) const
{
    if (values.size() != complex_size_)
        throw std::invalid_argument("incorrect fixed FFT input size");

    for (std::size_t i = 0; i < complex_size_; ++i) {
        const std::size_t j = reverse_bits(i, log_complex_size_);
        if (i < j) std::swap(values[i], values[j]);
    }

    int stage = 0;
    for (std::size_t length = 2; length <= complex_size_; length <<= 1, ++stage) {
        const std::size_t half = length / 2;
        for (std::size_t start = 0; start < complex_size_; start += length) {
            for (std::size_t j = 0; j < half; ++j) {
                const double sign = inverse ? 1.0 : -1.0;
                const double angle = sign * 2.0 * std::numbers::pi *
                                     static_cast<double>(j) /
                                     static_cast<double>(length);
                const FixedComplex odd = multiply_twiddle(
                    values[start + j + half], make_twiddle(angle), stats);
                const FixedComplex even = values[start + j];

                if (scale_after_stage_[stage]) {
                    // Shift the widened butterfly result before narrowing.  A
                    // scaling schedule is useful precisely because it avoids
                    // an otherwise unnecessary overflow at this boundary.
                    values[start + j].real = wrap_signed(
                        floor_shift_right(
                            static_cast<__int128>(even.real) + odd.real, 1),
                        data_format_, stats);
                    values[start + j].imag = wrap_signed(
                        floor_shift_right(
                            static_cast<__int128>(even.imag) + odd.imag, 1),
                        data_format_, stats);
                    values[start + j + half].real = wrap_signed(
                        floor_shift_right(
                            static_cast<__int128>(even.real) - odd.real, 1),
                        data_format_, stats);
                    values[start + j + half].imag = wrap_signed(
                        floor_shift_right(
                            static_cast<__int128>(even.imag) - odd.imag, 1),
                        data_format_, stats);
                }
                else {
                    values[start + j] =
                        {add_raw(even.real, odd.real, data_format_, stats),
                         add_raw(even.imag, odd.imag, data_format_, stats)};
                    values[start + j + half] =
                        {sub_raw(even.real, odd.real, data_format_, stats),
                         sub_raw(even.imag, odd.imag, data_format_, stats)};
                }
            }
        }
    }
}

QuantizedSpectrum NegacyclicFFT::forward(
    std::span<const double> coefficients, QuantizationStats *stats) const
{
    if (coefficients.size() != polynomial_size_)
        throw std::invalid_argument("incorrect polynomial input size");

    QuantizedSpectrum result;
    result.values.resize(complex_size_);
    result.format = data_format_;
    result.scale_exponent = stage_scale_exponent_;
    for (std::size_t i = 0; i < complex_size_; ++i) {
        const FixedComplex folded{
            quantize_double(coefficients[i], data_format_, stats),
            quantize_double(coefficients[i + complex_size_], data_format_,
                            stats)};
        result.values[i] = multiply_twiddle(folded, forward_twist_[i], stats);
    }
    fft_in_place(result.values, false, stats);
    return result;
}

QuantizedSpectrum NegacyclicFFT::forward_integer(
    std::span<const std::int64_t> coefficients, QuantizationStats *stats) const
{
    if (coefficients.size() != polynomial_size_)
        throw std::invalid_argument("incorrect polynomial input size");
    std::vector<double> converted(polynomial_size_);
    std::transform(coefficients.begin(), coefficients.end(), converted.begin(),
                   [](std::int64_t value) { return static_cast<double>(value); });
    return forward(converted, stats);
}

std::vector<std::int64_t> NegacyclicFFT::inverse_raw(
    const QuantizedSpectrum &spectrum, QuantizationStats *stats) const
{
    if (spectrum.values.size() != complex_size_)
        throw std::invalid_argument("incorrect fixed inverse FFT input size");
    if (spectrum.format.integer_bits != data_format_.integer_bits ||
        spectrum.format.fractional_bits != data_format_.fractional_bits)
        throw std::invalid_argument("inverse spectrum format does not match plan");

    std::vector<FixedComplex> data = spectrum.values;
    fft_in_place(data, true, stats);

    const int normalize_shift =
        log_complex_size_ - spectrum.scale_exponent - stage_scale_exponent_;
    std::vector<std::int64_t> result(polynomial_size_);
    for (std::size_t i = 0; i < complex_size_; ++i) {
        const FixedComplex untwisted =
            multiply_twiddle(data[i], inverse_twist_[i], stats);
        result[i] = wrap_signed(
            floor_shift_right(untwisted.real, normalize_shift),
            data_format_, stats);
        result[i + complex_size_] = wrap_signed(
            floor_shift_right(untwisted.imag, normalize_shift),
            data_format_, stats);
    }
    return result;
}

std::vector<double> NegacyclicFFT::inverse(
    const QuantizedSpectrum &spectrum, QuantizationStats *stats) const
{
    const auto raw = inverse_raw(spectrum, stats);
    std::vector<double> result(raw.size());
    std::transform(raw.begin(), raw.end(), result.begin(),
                   [&](std::int64_t value) {
                       return dequantize(value, data_format_);
                   });
    return result;
}

void NegacyclicFFT::reference_fft_in_place(
    std::span<std::complex<double>> values, bool inverse)
{
    if (!is_power_of_two(values.size()))
        throw std::invalid_argument("reference FFT size is not a power of two");
    const int log_size = static_cast<int>(std::countr_zero(values.size()));
    for (std::size_t i = 0; i < values.size(); ++i) {
        const std::size_t j = reverse_bits(i, log_size);
        if (i < j) std::swap(values[i], values[j]);
    }
    for (std::size_t length = 2; length <= values.size(); length <<= 1) {
        const std::size_t half = length / 2;
        const double sign = inverse ? 1.0 : -1.0;
        for (std::size_t start = 0; start < values.size(); start += length) {
            for (std::size_t j = 0; j < half; ++j) {
                const double angle = sign * 2.0 * std::numbers::pi *
                                     static_cast<double>(j) /
                                     static_cast<double>(length);
                const std::complex<double> odd =
                    values[start + j + half] * std::polar(1.0, angle);
                const std::complex<double> even = values[start + j];
                values[start + j] = even + odd;
                values[start + j + half] = even - odd;
            }
        }
    }
}

std::vector<std::complex<double>> NegacyclicFFT::reference_forward(
    std::span<const double> coefficients) const
{
    if (coefficients.size() != polynomial_size_)
        throw std::invalid_argument("incorrect reference polynomial input size");
    std::vector<std::complex<double>> result(complex_size_);
    for (std::size_t i = 0; i < complex_size_; ++i) {
        const double angle = std::numbers::pi * static_cast<double>(i) /
                             static_cast<double>(polynomial_size_);
        result[i] = std::complex<double>(coefficients[i],
                                         coefficients[i + complex_size_]) *
                    std::polar(1.0, angle);
    }
    reference_fft_in_place(result, false);
    return result;
}

std::vector<double> NegacyclicFFT::reference_inverse(
    std::span<const std::complex<double>> spectrum) const
{
    if (spectrum.size() != complex_size_)
        throw std::invalid_argument("incorrect reference inverse FFT input size");
    std::vector<std::complex<double>> data(spectrum.begin(), spectrum.end());
    reference_fft_in_place(data, true);
    const double inverse_size = 1.0 / static_cast<double>(complex_size_);
    std::vector<double> result(polynomial_size_);
    for (std::size_t i = 0; i < complex_size_; ++i) {
        const double angle = -std::numbers::pi * static_cast<double>(i) /
                             static_cast<double>(polynomial_size_);
        const std::complex<double> value =
            data[i] * std::polar(inverse_size, angle);
        result[i] = value.real();
        result[i + complex_size_] = value.imag();
    }
    return result;
}

QuantizedSpectrum NegacyclicFFT::quantize_reference(
    std::span<const std::complex<double>> spectrum,
    QuantizationStats *stats) const
{
    if (spectrum.size() != complex_size_)
        throw std::invalid_argument("incorrect spectrum quantization input size");
    QuantizedSpectrum result;
    result.values.resize(complex_size_);
    result.format = data_format_;
    for (std::size_t i = 0; i < complex_size_; ++i)
        result.values[i] =
            {quantize_double(spectrum[i].real(), data_format_, stats),
             quantize_double(spectrum[i].imag(), data_format_, stats)};
    return result;
}

QuantizedSpectrum multiply_spectra(const QuantizedSpectrum &lhs,
                                   const QuantizedSpectrum &rhs,
                                   FixedFormat destination_format,
                                   QuantizationStats *stats)
{
    if (lhs.values.size() != rhs.values.size())
        throw std::invalid_argument("spectrum sizes do not match");
    QuantizedSpectrum result;
    result.values.resize(lhs.values.size());
    result.format = destination_format;
    result.scale_exponent = lhs.scale_exponent + rhs.scale_exponent;
    const int source_fractional = lhs.format.fractional_bits +
                                  rhs.format.fractional_bits;
    for (std::size_t i = 0; i < lhs.values.size(); ++i) {
        const __int128 real =
            static_cast<__int128>(lhs.values[i].real) * rhs.values[i].real -
            static_cast<__int128>(lhs.values[i].imag) * rhs.values[i].imag;
        const __int128 imag =
            static_cast<__int128>(lhs.values[i].real) * rhs.values[i].imag +
            static_cast<__int128>(lhs.values[i].imag) * rhs.values[i].real;
        result.values[i] =
            {requantize_raw(real, source_fractional, destination_format, stats),
             requantize_raw(imag, source_fractional, destination_format, stats)};
    }
    return result;
}

void multiply_accumulate_spectra(std::span<FixedComplex> accumulator,
                                 FixedFormat accumulator_format,
                                 const QuantizedSpectrum &lhs,
                                 const QuantizedSpectrum &rhs,
                                 QuantizationStats *stats)
{
    if (lhs.values.size() != rhs.values.size() ||
        accumulator.size() != lhs.values.size())
        throw std::invalid_argument("spectrum sizes do not match");
    const int source_fractional = lhs.format.fractional_bits +
                                  rhs.format.fractional_bits;
    for (std::size_t i = 0; i < accumulator.size(); ++i) {
        const __int128 real =
            static_cast<__int128>(lhs.values[i].real) * rhs.values[i].real -
            static_cast<__int128>(lhs.values[i].imag) * rhs.values[i].imag;
        const __int128 imag =
            static_cast<__int128>(lhs.values[i].real) * rhs.values[i].imag +
            static_cast<__int128>(lhs.values[i].imag) * rhs.values[i].real;
        const std::int64_t product_real = requantize_raw(
            real, source_fractional, accumulator_format, stats);
        const std::int64_t product_imag = requantize_raw(
            imag, source_fractional, accumulator_format, stats);
        accumulator[i].real = add_raw(accumulator[i].real, product_real,
                                      accumulator_format, stats);
        accumulator[i].imag = add_raw(accumulator[i].imag, product_imag,
                                      accumulator_format, stats);
    }
}

std::vector<double> negacyclic_convolution_reference(
    std::span<const double> lhs, std::span<const double> rhs)
{
    if (lhs.size() != rhs.size())
        throw std::invalid_argument("polynomial sizes do not match");
    std::vector<double> result(lhs.size(), 0.0);
    for (std::size_t i = 0; i < lhs.size(); ++i) {
        for (std::size_t j = 0; j < rhs.size(); ++j) {
            const std::size_t degree = i + j;
            if (degree < lhs.size())
                result[degree] += lhs[i] * rhs[j];
            else
                result[degree - lhs.size()] -= lhs[i] * rhs[j];
        }
    }
    return result;
}

}  // namespace fpt

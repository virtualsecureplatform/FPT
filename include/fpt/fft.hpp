#pragma once

#include <complex>
#include <cstddef>
#include <span>
#include <vector>

#include "fpt/fixed_point.hpp"

namespace fpt {

enum class PaperParameterSet { I, II };

struct ArithmeticProfile {
    FixedFormat bootstrapping_key;
    FixedFormat forward_fft;
    FixedFormat inverse_fft;
    int twiddle_width_reduction = 4;

    [[nodiscard]] static constexpr ArithmeticProfile parameter_set_i()
    {
        // Table 2: BK Q7.19, FFT Q15.14, IFFT Q23.6.
        return {{7, 19}, {15, 14}, {23, 6}, 4};
    }

    [[nodiscard]] static constexpr ArithmeticProfile parameter_set_ii()
    {
        // Table 2: BK Q8.19, FFT Q18.12, IFFT Q27.3.
        return {{8, 19}, {18, 12}, {27, 3}, 4};
    }

    [[nodiscard]] static constexpr ArithmeticProfile for_parameter_set(
        PaperParameterSet parameter_set)
    {
        return parameter_set == PaperParameterSet::I ? parameter_set_i()
                                                      : parameter_set_ii();
    }
};

struct FFTConfig {
    std::size_t polynomial_size = 0;
    FixedFormat data_format{1, 0};
    int twiddle_width_reduction = 4;

    // One entry per radix-2 stage.  A true entry divides that stage by two.
    // The plan records the resulting exponent so callers can compensate at a
    // later boundary.  An empty vector means no stage scaling.
    std::vector<bool> scale_after_stage;
};

struct QuantizedSpectrum {
    std::vector<FixedComplex> values;
    FixedFormat format{1, 0};

    // The stored numerical values equal the mathematical spectrum divided by
    // 2^scale_exponent.
    int scale_exponent = 0;
};

class NegacyclicFFT {
  public:
    explicit NegacyclicFFT(FFTConfig config);

    [[nodiscard]] std::size_t polynomial_size() const { return polynomial_size_; }
    [[nodiscard]] std::size_t complex_size() const { return complex_size_; }
    [[nodiscard]] FixedFormat data_format() const { return data_format_; }
    [[nodiscard]] FixedFormat twiddle_format() const { return twiddle_format_; }
    [[nodiscard]] int stage_scale_exponent() const { return stage_scale_exponent_; }

    [[nodiscard]] QuantizedSpectrum forward(
        std::span<const double> coefficients,
        QuantizationStats *stats = nullptr) const;

    [[nodiscard]] QuantizedSpectrum forward_integer(
        std::span<const std::int64_t> coefficients,
        QuantizationStats *stats = nullptr) const;

    // The inverse performs an unnormalized fixed-point inverse FFT internally,
    // then applies 1/(N/2) and compensates all recorded stage scale factors
    // with a fixed-width signed power-of-two shift. inverse_raw exposes the
    // exact normalized raw coefficients used by RTL; inverse only dequantizes
    // those values for numerical callers.
    [[nodiscard]] std::vector<std::int64_t> inverse_raw(
        const QuantizedSpectrum &spectrum,
        QuantizationStats *stats = nullptr) const;

    [[nodiscard]] std::vector<double> inverse(
        const QuantizedSpectrum &spectrum,
        QuantizationStats *stats = nullptr) const;

    // Double-precision versions of the same folded/twisted transform.  They
    // are used for bootstrapping-key preparation and as validation oracles.
    [[nodiscard]] std::vector<std::complex<double>> reference_forward(
        std::span<const double> coefficients) const;

    [[nodiscard]] std::vector<double> reference_inverse(
        std::span<const std::complex<double>> spectrum) const;

    [[nodiscard]] QuantizedSpectrum quantize_reference(
        std::span<const std::complex<double>> spectrum,
        QuantizationStats *stats = nullptr) const;

  private:
    struct GaussTwiddle {
        std::int64_t c;
        std::int64_t c_minus_d;
        std::int64_t c_plus_d;
    };

    [[nodiscard]] GaussTwiddle make_twiddle(double angle) const;
    [[nodiscard]] FixedComplex multiply_twiddle(
        FixedComplex value, const GaussTwiddle &twiddle,
        QuantizationStats *stats) const;
    void fft_in_place(std::span<FixedComplex> values, bool inverse,
                      QuantizationStats *stats) const;
    static void reference_fft_in_place(
        std::span<std::complex<double>> values, bool inverse);

    std::size_t polynomial_size_;
    std::size_t complex_size_;
    int log_complex_size_;
    FixedFormat data_format_;
    FixedFormat twiddle_format_;
    std::vector<bool> scale_after_stage_;
    int stage_scale_exponent_;
    std::vector<GaussTwiddle> forward_twist_;
    std::vector<GaussTwiddle> inverse_twist_;
};

[[nodiscard]] QuantizedSpectrum multiply_spectra(
    const QuantizedSpectrum &lhs, const QuantizedSpectrum &rhs,
    FixedFormat destination_format, QuantizationStats *stats = nullptr);

void multiply_accumulate_spectra(std::span<FixedComplex> accumulator,
                                 FixedFormat accumulator_format,
                                 const QuantizedSpectrum &lhs,
                                 const QuantizedSpectrum &rhs,
                                 QuantizationStats *stats = nullptr);

[[nodiscard]] std::vector<double> negacyclic_convolution_reference(
    std::span<const double> lhs, std::span<const double> rhs);

}  // namespace fpt

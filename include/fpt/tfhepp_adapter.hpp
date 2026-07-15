#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <random>
#include <span>
#include <stdexcept>
#include <type_traits>
#include <vector>

#include <params.hpp>
#include <tfhe/key.hpp>
#include <tfhe/tlwe.hpp>
#include <tfhe/trgsw.hpp>
#include <tfhe/trlwe.hpp>
#include <utils.hpp>

#include "fpt/fft.hpp"

namespace fpt::tfhepp {

// This adapter deliberately defines a separate frequency-domain key type.
// TFHEpp stores unnormalized Torus integers in a double FFT, while FPT stores
// normalized Torus values in the compact BK fixed-point format.

template <class P>
inline constexpr int fpt_trgsw_rows = P::k * P::lₐ + P::l;

template <class P>
using PolynomialFPT = std::array<PackedComplex, P::n / 2>;

template <class P>
using TRLWEFPT = std::array<PolynomialFPT<P>, P::k + 1>;

template <class P>
using TRGSWFPT = std::array<TRLWEFPT<P>, fpt_trgsw_rows<P>>;

template <class P>
using BootstrappingKeyElementFPT =
    std::array<TRGSWFPT<typename P::targetP>, P::domainP::key_value_diff>;

template <class P>
using BootstrappingKeyFPT =
    std::array<BootstrappingKeyElementFPT<P>,
               P::domainP::k * P::domainP::n>;

struct BlindRotateStats {
    QuantizationStats bootstrapping_key;
    QuantizationStats forward_fft;
    QuantizationStats pointwise;
    QuantizationStats inverse_fft;
    std::uint64_t cmux_count = 0;
};

template <class P>
[[nodiscard]] constexpr ArithmeticProfile profile_for()
{
    static_assert(P::n == 512 || P::n == 1024,
                  "the current FPT reference supports N=512 or N=1024");
    // Keep the paper profiles available in ArithmeticProfile, but use extra
    // fractional guard bits for TFHEpp's different decomposition parameters.
    // A later format sweep will narrow these independently.
    return {{8, 24}, {18, 20}, {27, 14}, 4};
}

template <class P>
[[nodiscard]] const NegacyclicFFT &bootstrapping_key_plan()
{
    constexpr auto profile = profile_for<P>();
    static const NegacyclicFFT plan(
        {P::n, profile.bootstrapping_key,
         profile.twiddle_width_reduction, {}});
    return plan;
}

template <class P>
[[nodiscard]] const NegacyclicFFT &forward_plan()
{
    constexpr auto profile = profile_for<P>();
    static const NegacyclicFFT plan(
        {P::n, profile.forward_fft, profile.twiddle_width_reduction, {}});
    return plan;
}

template <class P>
[[nodiscard]] const NegacyclicFFT &inverse_plan()
{
    constexpr auto profile = profile_for<P>();
    static const NegacyclicFFT plan(
        {P::n, profile.inverse_fft, profile.twiddle_width_reduction, {}});
    return plan;
}

template <class P>
void TransformBootstrappingKeyPolynomial(
    PolynomialFPT<P> &destination, const TFHEpp::Polynomial<P> &source,
    QuantizationStats *stats = nullptr)
{
    static_assert(std::is_same_v<typename P::T, std::uint32_t>,
                  "FPT key normalization currently supports a 32-bit Torus");
    std::vector<double> normalized(P::n);
    constexpr double torus_scale = 0x1p-32;
    for (std::size_t i = 0; i < P::n; ++i)
        normalized[i] =
            static_cast<double>(static_cast<std::int32_t>(source[i])) *
            torus_scale;

    const auto reference =
        bootstrapping_key_plan<P>().reference_forward(normalized);
    const auto quantized =
        bootstrapping_key_plan<P>().quantize_reference(reference, stats);
    for (std::size_t i = 0; i < P::n / 2; ++i) {
        destination[i].real =
            static_cast<std::int32_t>(quantized.values[i].real);
        destination[i].imag =
            static_cast<std::int32_t>(quantized.values[i].imag);
    }
}

template <class P>
void TransformBootstrappingKey(TRGSWFPT<P> &destination,
                               const TFHEpp::TRGSW<P> &source,
                               QuantizationStats *stats = nullptr)
{
    static_assert(P::l̅ == 1 && P::l̅ₐ == 1,
                  "the first FPT reference handles standard decomposition");
    for (int row = 0; row < fpt_trgsw_rows<P>; ++row)
        for (int component = 0; component < P::k + 1; ++component)
            TransformBootstrappingKeyPolynomial<P>(
                destination[row][component], source[row][component], stats);
}

template <class P>
[[nodiscard]] typename P::T NormalizedToTorus(double value)
{
    static_assert(std::is_same_v<typename P::T, std::uint32_t>);
    const std::int64_t rounded =
        static_cast<std::int64_t>(std::round(std::ldexp(value, 32)));
    return static_cast<std::uint32_t>(rounded);
}

// Generate an ordinary coefficient-domain TRLWE without relying on TFHEpp's
// selected floating-point backend. This remains a genuine noisy encryption:
// masks are uniform Torus polynomials and b = sum(a_i * s_i) + e. The double
// transform is used only during offline bootstrapping-key preparation.
template <class P>
void EncryptBootstrappingKeyTRLWE(
    TFHEpp::TRLWE<P> &ciphertext, const TFHEpp::Key<P> &key)
{
    static_assert(std::is_same_v<typename P::T, std::uint32_t>,
                  "FPT key generation currently supports a 32-bit Torus");
    std::vector<std::complex<double>> product_spectrum(P::n / 2);
    std::vector<double> normalized_mask(P::n);
    std::vector<double> signed_key(P::n);
    constexpr double torus_scale = 0x1p-32;
    static thread_local std::mt19937_64 generator(std::random_device{}());
    std::uniform_int_distribution<std::uint32_t> uniform_torus;
    std::normal_distribution<double> gaussian(0.0, P::α);

    for (int component = 0; component < P::k; ++component) {
        for (std::size_t i = 0; i < P::n; ++i) {
            ciphertext[component][i] = uniform_torus(generator);
            normalized_mask[i] =
                static_cast<double>(static_cast<std::int32_t>(
                    ciphertext[component][i])) * torus_scale;
            signed_key[i] = static_cast<double>(static_cast<std::int32_t>(
                key[component * P::n + i]));
        }
        const auto mask_spectrum =
            bootstrapping_key_plan<P>().reference_forward(normalized_mask);
        const auto key_spectrum =
            bootstrapping_key_plan<P>().reference_forward(signed_key);
        for (std::size_t i = 0; i < product_spectrum.size(); ++i)
            product_spectrum[i] += mask_spectrum[i] * key_spectrum[i];
    }

    const auto product =
        bootstrapping_key_plan<P>().reference_inverse(product_spectrum);
    for (std::size_t i = 0; i < P::n; ++i)
        ciphertext[P::k][i] =
            NormalizedToTorus<P>(product[i]) +
            NormalizedToTorus<P>(gaussian(generator));
}

template <class P>
void BootstrappingKeyFPTGen(
    BootstrappingKeyFPT<P> &destination,
    const TFHEpp::Key<typename P::domainP> &domain_key,
    const TFHEpp::Key<typename P::targetP> &target_key,
    QuantizationStats *stats = nullptr)
{
    static_assert(P::Addends == 1,
                  "the first FPT reference does not use key bundling");
    using Target = typename P::targetP;
    static_assert(Target::l̅ == 1 && Target::l̅ₐ == 1,
                  "the first FPT reference handles standard decomposition");

    constexpr auto nonce_gadget = TFHEpp::hgen<Target, true>();
    constexpr auto main_gadget = TFHEpp::hgen<Target, false>();
    auto coefficient_key = std::make_unique<TFHEpp::TRGSW<Target>>();
    for (int i = 0; i < P::domainP::k * P::domainP::n; ++i) {
        int key_index = 0;
        for (int value = P::domainP::key_value_min;
            value <= P::domainP::key_value_max; ++value) {
            if (value == 0) continue;
            for (auto &row_ciphertext : *coefficient_key)
                EncryptBootstrappingKeyTRLWE<Target>(row_ciphertext,
                                                      target_key);

            const typename Target::T plaintext = domain_key[i] == value;
            int row = 0;
            for (int component = 0; component < Target::k; ++component)
                for (int level = 0; level < Target::lₐ; ++level, ++row)
                    (*coefficient_key)[row][component][0] +=
                        plaintext * nonce_gadget[level];
            for (int level = 0; level < Target::l; ++level, ++row)
                (*coefficient_key)[row][Target::k][0] +=
                    plaintext * main_gadget[level];
            TransformBootstrappingKey<Target>(
                destination[i][key_index], *coefficient_key, stats);
            ++key_index;
        }
    }
}

template <class P>
void BootstrappingKeyFPTGen(BootstrappingKeyFPT<P> &destination,
                            const TFHEpp::SecretKey &secret_key,
                            QuantizationStats *stats = nullptr)
{
    BootstrappingKeyFPTGen<P>(
        destination, secret_key.key.get<typename P::domainP>(),
        secret_key.key.get<typename P::targetP>(), stats);
}

template <class P>
[[nodiscard]] QuantizedSpectrum ForwardDecomposedPolynomial(
    const TFHEpp::Polynomial<P> &polynomial,
    QuantizationStats *stats = nullptr)
{
    static_assert(std::is_same_v<typename P::T, std::uint32_t>,
                  "the first FPT datapath model supports uint32_t digits");
    std::vector<std::int64_t> signed_coefficients(P::n);
    for (std::size_t i = 0; i < P::n; ++i)
        signed_coefficients[i] =
            static_cast<std::int32_t>(polynomial[i]);
    return forward_plan<P>().forward_integer(signed_coefficients, stats);
}

template <class P>
void MultiplyAccumulatePacked(
    std::span<FixedComplex> accumulator,
    const QuantizedSpectrum &decomposed_spectrum,
    const PolynomialFPT<P> &bootstrapping_key_spectrum,
    QuantizationStats *stats = nullptr)
{
    constexpr auto profile = profile_for<P>();
    const int source_fractional = profile.forward_fft.fractional_bits +
                                  profile.bootstrapping_key.fractional_bits;
    for (std::size_t i = 0; i < accumulator.size(); ++i) {
        const __int128 real =
            static_cast<__int128>(decomposed_spectrum.values[i].real) *
                bootstrapping_key_spectrum[i].real -
            static_cast<__int128>(decomposed_spectrum.values[i].imag) *
                bootstrapping_key_spectrum[i].imag;
        const __int128 imag =
            static_cast<__int128>(decomposed_spectrum.values[i].real) *
                bootstrapping_key_spectrum[i].imag +
            static_cast<__int128>(decomposed_spectrum.values[i].imag) *
                bootstrapping_key_spectrum[i].real;
        const std::int64_t product_real = requantize_raw(
            real, source_fractional, profile.inverse_fft, stats);
        const std::int64_t product_imag = requantize_raw(
            imag, source_fractional, profile.inverse_fft, stats);
        accumulator[i].real = add_raw(accumulator[i].real, product_real,
                                      profile.inverse_fft, stats);
        accumulator[i].imag = add_raw(accumulator[i].imag, product_imag,
                                      profile.inverse_fft, stats);
    }
}

template <class P>
void ExternalProductFPT(TFHEpp::TRLWE<P> &result,
                        const TFHEpp::TRLWE<P> &input,
                        const TRGSWFPT<P> &bootstrapping_key,
                        BlindRotateStats *stats = nullptr)
{
    static_assert(P::l̅ == 1 && P::l̅ₐ == 1,
                  "the first FPT reference handles standard decomposition");
    constexpr auto profile = profile_for<P>();
    std::array<std::vector<FixedComplex>, P::k + 1> accumulators;
    for (auto &accumulator : accumulators)
        accumulator.resize(P::n / 2);

    auto process_row = [&](const TFHEpp::Polynomial<P> &polynomial, int row) {
        auto spectrum = ForwardDecomposedPolynomial<P>(
            polynomial, stats == nullptr ? nullptr : &stats->forward_fft);
        for (int component = 0; component < P::k + 1; ++component)
            MultiplyAccumulatePacked<P>(
                accumulators[component], spectrum,
                bootstrapping_key[row][component],
                stats == nullptr ? nullptr : &stats->pointwise);
    };

    int row = 0;
    for (int key_component = 0; key_component < P::k; ++key_component) {
        TFHEpp::DecomposedNoncePolynomial<P> decomposition;
        TFHEpp::NonceDecomposition<P>(decomposition, input[key_component]);
        for (int level = 0; level < P::lₐ; ++level, ++row)
            process_row(decomposition[level], row);
    }
    {
        TFHEpp::DecomposedPolynomial<P> decomposition;
        TFHEpp::Decomposition<P>(decomposition, input[P::k]);
        for (int level = 0; level < P::l; ++level, ++row)
            process_row(decomposition[level], row);
    }

    for (int component = 0; component < P::k + 1; ++component) {
        QuantizedSpectrum spectrum;
        spectrum.values = std::move(accumulators[component]);
        spectrum.format = profile.inverse_fft;
        spectrum.scale_exponent = forward_plan<P>().stage_scale_exponent();
        const auto coefficients = inverse_plan<P>().inverse_raw(
            spectrum, stats == nullptr ? nullptr : &stats->inverse_fft);
        for (std::size_t i = 0; i < P::n; ++i)
            result[component][i] = static_cast<typename P::T>(
                fixed_raw_to_torus(
                    coefficients[i], profile.inverse_fft.fractional_bits,
                    std::numeric_limits<typename P::T>::digits));
    }
}

template <class P>
void CMUXFPTWithPolynomialMulByXaiMinusOne(
    TFHEpp::TRLWE<typename P::targetP> &accumulator,
    const BootstrappingKeyElementFPT<P> &bootstrapping_key, int exponent,
    BlindRotateStats *stats = nullptr)
{
    using Target = typename P::targetP;
    static_assert(P::domainP::key_value_diff == 1,
                  "the first FPT reference expects a binary domain key");
    TFHEpp::TRLWE<Target> difference;
    for (int component = 0; component < Target::k + 1; ++component)
        TFHEpp::PolynomialMulByXaiMinusOne<Target>(
            difference[component], accumulator[component], exponent);
    ExternalProductFPT<Target>(difference, difference, bootstrapping_key[0],
                               stats);
    for (int component = 0; component < Target::k + 1; ++component)
        for (std::size_t i = 0; i < Target::n; ++i)
            accumulator[component][i] += difference[component][i];
    if (stats != nullptr) ++stats->cmux_count;
}

template <class P, std::uint32_t num_out = 1>
void BlindRotateFPT(
    TFHEpp::TRLWE<typename P::targetP> &result,
    const TFHEpp::TLWE<typename P::domainP> &input,
    const BootstrappingKeyFPT<P> &bootstrapping_key,
    const TFHEpp::Polynomial<typename P::targetP> &test_vector,
    BlindRotateStats *stats = nullptr)
{
    static_assert(P::Addends == 1,
                  "the first FPT reference does not use key bundling");
    using Domain = typename P::domainP;
    using Target = typename P::targetP;
    TFHEpp::ModswitchTLWE<Domain> modulus_switched;
    constexpr std::uint32_t bit_width = TFHEpp::bits_needed<num_out - 1>();
    std::make_signed_t<typename Domain::T> correction = 0;
    constexpr typename Domain::T round_offset =
        1ULL << (std::numeric_limits<typename Domain::T>::digits - 2 -
                 Target::nbit + bit_width);
    for (int i = 0; i < Domain::k * Domain::n; ++i) {
        modulus_switched[i] =
            (input[i] + round_offset) >>
                (std::numeric_limits<typename Domain::T>::digits - 1 -
                 Target::nbit + bit_width)
            << bit_width;
        correction +=
            input[i] -
            (modulus_switched[i]
             << (std::numeric_limits<typename Domain::T>::digits - 1 -
                 Target::nbit));
    }
    modulus_switched[Domain::k * Domain::n] =
        2 * Target::n -
        (static_cast<typename Domain::T>(
             input[Domain::k * Domain::n] - correction / 2 + round_offset) >>
         (std::numeric_limits<typename Domain::T>::digits - 1 - Target::nbit +
          bit_width)
         << bit_width);
    result = {};
    TFHEpp::PolynomialMulByXai<typename P::targetP>(
        result[P::targetP::k], test_vector,
        modulus_switched[P::domainP::k * P::domainP::n]);
    for (int i = 0; i < P::domainP::k * P::domainP::n; ++i) {
        if (modulus_switched[i] == 0) continue;
        CMUXFPTWithPolynomialMulByXaiMinusOne<P>(
            result, bootstrapping_key[i], modulus_switched[i], stats);
    }
}

template <class P>
void GateBootstrappingTLWE2TLWEFPT(
    TFHEpp::TLWE<typename P::targetP> &result,
    const TFHEpp::TLWE<typename P::domainP> &input,
    const BootstrappingKeyFPT<P> &bootstrapping_key,
    const TFHEpp::Polynomial<typename P::targetP> &test_vector,
    BlindRotateStats *stats = nullptr)
{
    TFHEpp::TRLWE<typename P::targetP> accumulator;
    BlindRotateFPT<P>(accumulator, input, bootstrapping_key, test_vector,
                      stats);
    TFHEpp::SampleExtractIndex<typename P::targetP>(result, accumulator, 0);
}

}  // namespace fpt::tfhepp

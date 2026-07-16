#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <string_view>
#include <type_traits>
#include <vector>

#include <params.hpp>
#include <tfhe/key.hpp>
#include <tfhe/tlwe.hpp>

#include "fpt/tfhepp_adapter.hpp"

namespace {

using BootstrapParams = TFHEpp::lvl01param;
using Domain = BootstrapParams::domainP;
using Target = BootstrapParams::targetP;
using Key = fpt::tfhepp::BootstrappingKeyFPT<BootstrapParams>;
static_assert(std::is_same_v<Target::T, std::uint32_t>);

// The high-precision reference and guarded profile share the same Q8.24 key.
// Narrower profiles requantize that one spectral key so every row, mask, and
// noise sample is identical across the sweep.
constexpr fpt::ArithmeticProfile guarded =
    fpt::tfhepp::tfhepp_guarded_profile;
constexpr fpt::ArithmeticProfile high_precision =
    fpt::tfhepp::tfhepp_reference_profile;
constexpr fpt::ArithmeticProfile guarded_with_paper_bk{
    {8, 19}, guarded.forward_fft, guarded.inverse_fft, 4};
constexpr fpt::ArithmeticProfile guarded_with_paper_fft{
    guarded.bootstrapping_key, {18, 12}, guarded.inverse_fft, 4};
constexpr fpt::ArithmeticProfile guarded_with_paper_ifft{
    guarded.bootstrapping_key, guarded.forward_fft, {27, 3}, 4};
constexpr fpt::ArithmeticProfile midpoint{
    {8, 21}, {18, 16}, {27, 9}, 4};
constexpr fpt::ArithmeticProfile paper =
    fpt::ArithmeticProfile::parameter_set_ii();

struct SweepResult {
    int successes = 0;
    double maximum_phase_error = 0;
    double mean_phase_error = 0;
    fpt::tfhepp::BlindRotateStats stats;
};

std::int32_t narrow_raw(std::int32_t value, int source_fractional,
                        fpt::FixedFormat destination)
{
    const int shift = source_fractional - destination.fractional_bits;
    std::int64_t converted = value;
    if (shift > 0)
        converted /= std::int64_t{1} << shift;
    else if (shift < 0)
        converted *= std::int64_t{1} << -shift;
    return static_cast<std::int32_t>(
        fpt::wrap_signed(converted, destination));
}

template <fpt::ArithmeticProfile Profile>
std::unique_ptr<Key> requantize_key(const Key &source)
{
    auto destination = std::make_unique<Key>();
    for (std::size_t index = 0; index < source.size(); ++index)
        for (std::size_t key_value = 0;
             key_value < source[index].size(); ++key_value)
            for (std::size_t row = 0;
                 row < source[index][key_value].size(); ++row)
                for (std::size_t component = 0;
                     component < source[index][key_value][row].size();
                     ++component)
                    for (std::size_t point = 0;
                         point < source[index][key_value][row][component].size();
                         ++point) {
                        const auto value =
                            source[index][key_value][row][component][point];
                        auto &converted = (*destination)[index][key_value][row]
                                                        [component][point];
                        converted.real = narrow_raw(
                            value.real,
                            guarded.bootstrapping_key.fractional_bits,
                            Profile.bootstrapping_key);
                        converted.imag = narrow_raw(
                            value.imag,
                            guarded.bootstrapping_key.fractional_bits,
                            Profile.bootstrapping_key);
                    }
    return destination;
}

std::vector<TFHEpp::TLWE<Domain>> deterministic_inputs(
    int trials, const TFHEpp::Key<Domain> &key)
{
    std::mt19937_64 generator(0x4650545f53574550ULL);
    std::uniform_int_distribution<Domain::T> uniform_torus;
    std::normal_distribution<double> gaussian(0.0, Domain::α);
    std::vector<TFHEpp::TLWE<Domain>> inputs(trials);
    for (int trial = 0; trial < trials; ++trial) {
        const bool message = (trial & 1) != 0;
        auto &input = inputs[trial];
        input.fill(0);
        const double noise = gaussian(generator);
        using SignedTorus = std::make_signed_t<Domain::T>;
        const auto torus_noise = static_cast<SignedTorus>(
            static_cast<std::int64_t>(std::ldexp(
                noise, std::numeric_limits<Domain::T>::digits)));
        input[Domain::k * Domain::n] =
            static_cast<Domain::T>(
                message ? Domain::μ : static_cast<Domain::T>(-Domain::μ)) +
            static_cast<Domain::T>(torus_noise);
        for (int component = 0; component < Domain::k; ++component)
            for (std::size_t coefficient = 0;
                 coefficient < Domain::n; ++coefficient) {
                const std::size_t offset =
                    component * Domain::n + coefficient;
                input[offset] = uniform_torus(generator);
                input[Domain::k * Domain::n] += input[offset] * key[offset];
            }
    }
    return inputs;
}

template <fpt::ArithmeticProfile Profile>
SweepResult run_profile(
    std::string_view name, const Key &key,
    const std::vector<TFHEpp::TLWE<Domain>> &inputs,
    const TFHEpp::Key<Target> &target_key)
{
    SweepResult result;
    TFHEpp::Polynomial<Target> test_vector;
    test_vector.fill(Target::μ);
    long double total_phase_error = 0;
    constexpr int torus_quantum_bits =
        std::numeric_limits<Target::T>::digits -
        Profile.inverse_fft.fractional_bits;
    constexpr Target::T torus_quantum_mask =
        (Target::T{1} << torus_quantum_bits) - 1;

    for (std::size_t trial = 0; trial < inputs.size(); ++trial) {
        const bool message = (trial & 1U) != 0;
        TFHEpp::TLWE<Target> output;
        fpt::tfhepp::GateBootstrappingTLWE2TLWEFPT<
            BootstrapParams, Profile>(
            output, inputs[trial], key, test_vector, &result.stats);
        for (const auto coefficient : output)
            if ((coefficient & torus_quantum_mask) != 0)
                throw std::runtime_error(
                    "profile sweep retained sub-quantum Torus bits");
        if (TFHEpp::tlweSymDecrypt<Target>(output, target_key) == message)
            ++result.successes;
        const Target::T phase =
            TFHEpp::tlweSymPhase<Target>(output, target_key);
        const Target::T expected = message
            ? static_cast<Target::T>(Target::μ)
            : static_cast<Target::T>(-Target::μ);
        const std::int32_t signed_error =
            std::bit_cast<std::int32_t>(phase - expected);
        const double phase_error =
            std::abs(static_cast<double>(signed_error)) * 0x1p-32;
        result.maximum_phase_error =
            std::max(result.maximum_phase_error, phase_error);
        total_phase_error += phase_error;
    }
    result.mean_phase_error =
        static_cast<double>(total_phase_error / inputs.size());

    std::cout << name << '\t' << Profile.bootstrapping_key.str() << '\t'
              << Profile.forward_fft.str() << '\t'
              << Profile.inverse_fft.str() << '\t' << result.successes << '/'
              << inputs.size() << '\t' << result.maximum_phase_error << '\t'
              << result.mean_phase_error << '\t'
              << result.stats.forward_fft.overflows << '\t'
              << result.stats.pointwise.overflows << '\t'
              << result.stats.inverse_fft.overflows << '\n';
    return result;
}

template <fpt::ArithmeticProfile Profile>
SweepResult requantize_and_run(
    std::string_view name, const Key &guarded_key,
    const std::vector<TFHEpp::TLWE<Domain>> &inputs,
    const TFHEpp::Key<Target> &target_key)
{
    const auto key = requantize_key<Profile>(guarded_key);
    return run_profile<Profile>(name, *key, inputs, target_key);
}

}  // namespace

int main(int argc, char **argv)
{
    try {
        int trials = 8;
        if (argc > 2)
            throw std::invalid_argument(
                "usage: fpt_tfhepp_profile_sweep [EVEN_TRIALS]");
        if (argc == 2) trials = std::stoi(argv[1]);
        if (trials < 2 || (trials & 1) != 0)
            throw std::invalid_argument("EVEN_TRIALS must be even and >= 2");

        std::mt19937_64 key_generator(0x4650545f4b455953ULL);
        std::uniform_int_distribution<std::int32_t> domain_key_distribution(
            Domain::key_value_min, Domain::key_value_max);
        std::uniform_int_distribution<std::int32_t> target_key_distribution(
            Target::key_value_min, Target::key_value_max);
        TFHEpp::Key<Domain> domain_key;
        TFHEpp::Key<Target> target_key;
        for (auto &coefficient : domain_key)
            coefficient = static_cast<Domain::T>(
                domain_key_distribution(key_generator));
        for (auto &coefficient : target_key)
            coefficient = static_cast<Target::T>(
                target_key_distribution(key_generator));
        auto guarded_key = std::make_unique<Key>();
        fpt::tfhepp::BlindRotateStats key_stats;
        fpt::tfhepp::BootstrappingKeyFPTGen<BootstrapParams, guarded>(
            *guarded_key, domain_key, target_key,
            &key_stats.bootstrapping_key, &key_generator);
        const auto inputs = deterministic_inputs(trials, domain_key);

        std::cout << std::setprecision(9)
                  << "profile\tbk\tfft\tifft\tdecryptions\tmax_phase_error"
                     "\tmean_phase_error\tfft_overflows"
                     "\tpointwise_overflows\tifft_overflows\n";
        const auto high_precision_result = run_profile<high_precision>(
            "high-precision", *guarded_key, inputs, target_key);
        const auto guarded_result =
            run_profile<guarded>("guarded", *guarded_key, inputs, target_key);
        requantize_and_run<guarded_with_paper_bk>(
            "paper-bk-only", *guarded_key, inputs, target_key);
        requantize_and_run<guarded_with_paper_fft>(
            "paper-fft-only", *guarded_key, inputs, target_key);
        requantize_and_run<guarded_with_paper_ifft>(
            "paper-ifft-only", *guarded_key, inputs, target_key);
        requantize_and_run<midpoint>(
            "midpoint", *guarded_key, inputs, target_key);
        requantize_and_run<paper>(
            "paper-set-ii", *guarded_key, inputs, target_key);

        if (high_precision_result.successes != trials)
            throw std::runtime_error(
                "high-precision TFHEpp profile failed Boolean decryption");
        if (key_stats.bootstrapping_key.overflows != 0 ||
            high_precision_result.stats.forward_fft.overflows != 0 ||
            high_precision_result.stats.pointwise.overflows != 0 ||
            high_precision_result.stats.inverse_fft.overflows != 0 ||
            guarded_result.stats.forward_fft.overflows != 0 ||
            guarded_result.stats.pointwise.overflows != 0 ||
            guarded_result.stats.inverse_fft.overflows != 0)
            throw std::runtime_error(
                "validated TFHEpp profiles overflowed");
        return 0;
    }
    catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <type_traits>

#include <params.hpp>
#include <tfhe/key.hpp>
#include <tfhe/tlwe.hpp>

#include "fpt/tfhepp_adapter.hpp"

namespace {

using BootstrapParams = TFHEpp::lvl01param;
using Domain = BootstrapParams::domainP;
using Target = BootstrapParams::targetP;
using Key = fpt::tfhepp::BootstrappingKeyFPT<BootstrapParams>;

constexpr fpt::ArithmeticProfile rtl_candidate =
    fpt::tfhepp::tfhepp_hardware_profile;
static_assert(std::is_same_v<Target::T, std::uint32_t>);

struct TrialResult {
    int successes = 0;
    double maximum_phase_error = 0;
    fpt::tfhepp::BlindRotateStats stats;
};

TrialResult run_key_trial(int inputs_per_key)
{
    TFHEpp::SecretKey secret_key;
    auto bootstrapping_key = std::make_unique<Key>();
    TrialResult result;
    fpt::tfhepp::BootstrappingKeyFPTGen<BootstrapParams, rtl_candidate>(
        *bootstrapping_key, secret_key, &result.stats.bootstrapping_key);

    TFHEpp::Polynomial<Target> test_vector;
    test_vector.fill(Target::μ);
    constexpr int torus_quantum_bits =
        std::numeric_limits<Target::T>::digits -
        rtl_candidate.inverse_fft.fractional_bits;
    constexpr Target::T torus_quantum_mask =
        (Target::T{1} << torus_quantum_bits) - 1;

    for (int input_trial = 0; input_trial < inputs_per_key; ++input_trial) {
        const bool message = (input_trial & 1) != 0;
        TFHEpp::TLWE<Domain> input;
        TFHEpp::tlweSymEncrypt<Domain>(
            input,
            message ? Domain::μ : static_cast<Domain::T>(-Domain::μ),
            secret_key.key.get<Domain>());

        TFHEpp::TLWE<Target> output;
        fpt::tfhepp::GateBootstrappingTLWE2TLWEFPT<
            BootstrapParams, rtl_candidate>(
            output, input, *bootstrapping_key, test_vector, &result.stats);
        for (const auto coefficient : output)
            if ((coefficient & torus_quantum_mask) != 0)
                throw std::runtime_error(
                    "RTL profile retained sub-quantum Torus bits");

        if (TFHEpp::tlweSymDecrypt<Target>(
                output, secret_key.key.get<Target>()) == message)
            ++result.successes;
        const Target::T phase = TFHEpp::tlweSymPhase<Target>(
            output, secret_key.key.get<Target>());
        const Target::T expected = message
            ? static_cast<Target::T>(Target::μ)
            : static_cast<Target::T>(-Target::μ);
        const std::int32_t signed_error =
            std::bit_cast<std::int32_t>(phase - expected);
        const double phase_error =
            std::abs(static_cast<double>(signed_error)) * 0x1p-32;
        result.maximum_phase_error =
            std::max(result.maximum_phase_error, phase_error);
    }
    return result;
}

bool overflowed(const fpt::tfhepp::BlindRotateStats &stats)
{
    return stats.bootstrapping_key.overflows != 0 ||
           stats.forward_fft.overflows != 0 ||
           stats.pointwise.overflows != 0 ||
           stats.inverse_fft.overflows != 0;
}

}  // namespace

int main(int argc, char **argv)
{
    try {
        int key_trials = 4;
        int inputs_per_key = 4;
        if (argc > 3)
            throw std::invalid_argument(
                "usage: fpt_tfhepp_rtl_profile_stress "
                "[KEY_TRIALS [INPUTS_PER_KEY]]");
        if (argc >= 2) key_trials = std::stoi(argv[1]);
        if (argc == 3) inputs_per_key = std::stoi(argv[2]);
        if (key_trials < 1)
            throw std::invalid_argument("KEY_TRIALS must be positive");
        if (inputs_per_key < 2 || (inputs_per_key & 1) != 0)
            throw std::invalid_argument(
                "INPUTS_PER_KEY must be even and at least two");

        int total_successes = 0;
        double maximum_phase_error = 0;
        bool any_overflow = false;
        std::cout << std::setprecision(9)
                  << "key_trial\tdecryptions\tmax_phase_error"
                     "\tbk_overflows\tfft_overflows"
                     "\tpointwise_overflows\tifft_overflows\n";
        for (int key_trial = 0; key_trial < key_trials; ++key_trial) {
            const TrialResult result = run_key_trial(inputs_per_key);
            total_successes += result.successes;
            maximum_phase_error = std::max(
                maximum_phase_error, result.maximum_phase_error);
            any_overflow = any_overflow || overflowed(result.stats);
            std::cout << key_trial << '\t' << result.successes << '/'
                      << inputs_per_key << '\t'
                      << result.maximum_phase_error << '\t'
                      << result.stats.bootstrapping_key.overflows << '\t'
                      << result.stats.forward_fft.overflows << '\t'
                      << result.stats.pointwise.overflows << '\t'
                      << result.stats.inverse_fft.overflows << '\n';
        }

        const int total_inputs = key_trials * inputs_per_key;
        std::cout << "total\t" << total_successes << '/' << total_inputs
                  << '\t' << maximum_phase_error << '\n';
        if (total_successes != total_inputs)
            throw std::runtime_error(
                "RTL candidate changed at least one Boolean message");
        if (any_overflow)
            throw std::runtime_error("RTL candidate overflowed");
        return 0;
    }
    catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}

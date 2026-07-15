#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <memory>
#include <stdexcept>

#include <params.hpp>
#include <tfhe/key.hpp>
#include <tfhe/tlwe.hpp>

#include "fpt/tfhepp_adapter.hpp"

int main()
{
    try {
        using BootstrapParams = TFHEpp::lvl01param;
        using Domain = BootstrapParams::domainP;
        using Target = BootstrapParams::targetP;
        static_assert(std::is_same_v<Target::T, std::uint32_t>,
                      "the FPT reference currently targets uint32_t TFHE");

        TFHEpp::SecretKey secret_key;
        {
            TFHEpp::TRLWE<Target> probe;
            fpt::tfhepp::EncryptBootstrappingKeyTRLWE<Target>(
                probe, secret_key.key.get<Target>());
            std::uint64_t maximum_keygen_error = 0;
            for (std::size_t coefficient = 0; coefficient < Target::n;
                 ++coefficient) {
                Target::T exact_product = 0;
                for (std::size_t j = 0; j <= coefficient; ++j)
                    exact_product +=
                        probe[0][j] * secret_key.key.get<Target>()[coefficient - j];
                for (std::size_t j = coefficient + 1; j < Target::n; ++j)
                    exact_product -=
                        probe[0][j] *
                        secret_key.key.get<Target>()[Target::n + coefficient - j];
                const std::int32_t error = std::bit_cast<std::int32_t>(
                    probe[Target::k][coefficient] - exact_product);
                maximum_keygen_error = std::max(
                    maximum_keygen_error,
                    static_cast<std::uint64_t>(
                        std::abs(static_cast<std::int64_t>(error))));
            }
            std::cout << "offline_keygen_max_error=" << maximum_keygen_error
                      << '\n';
            if (maximum_keygen_error > 4096)
                throw std::runtime_error(
                    "offline TRLWE key preparation is inconsistent");
        }
        auto bootstrapping_key = std::make_unique<
            fpt::tfhepp::BootstrappingKeyFPT<BootstrapParams>>();
        fpt::tfhepp::BlindRotateStats stats;
        const auto key_start = std::chrono::steady_clock::now();
        fpt::tfhepp::BootstrappingKeyFPTGen<BootstrapParams>(
            *bootstrapping_key, secret_key, &stats.bootstrapping_key);
        const double key_seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - key_start).count();

        constexpr std::array<bool, 2> messages{false, true};
        std::array<double, messages.size()> bootstrap_seconds{};
        double maximum_phase_error = 0.0;
        std::size_t trial = 0;
        for (bool message : messages) {
            TFHEpp::TLWE<Domain> input;
            TFHEpp::tlweSymEncrypt<Domain>(
                input, message ? Domain::μ : static_cast<Domain::T>(-Domain::μ),
                secret_key.key.get<Domain>());

            TFHEpp::TLWE<Target> output;
            TFHEpp::Polynomial<Target> test_vector;
            test_vector.fill(Target::μ);
            const auto bootstrap_start = std::chrono::steady_clock::now();
            fpt::tfhepp::GateBootstrappingTLWE2TLWEFPT<BootstrapParams>(
                output, input, *bootstrapping_key, test_vector, &stats);
            bootstrap_seconds[trial++] = std::chrono::duration<double>(
                std::chrono::steady_clock::now() - bootstrap_start).count();
            constexpr auto profile = fpt::tfhepp::profile_for<Target>();
            constexpr int torus_quantum_bits =
                std::numeric_limits<typename Target::T>::digits -
                profile.inverse_fft.fractional_bits;
            constexpr typename Target::T torus_quantum_mask =
                (typename Target::T{1} << torus_quantum_bits) - 1;
            for (const auto coefficient : output)
                if ((coefficient & torus_quantum_mask) != 0)
                    throw std::runtime_error(
                        "fixed-point Blind Rotate retained hidden "
                        "sub-quantum Torus bits");
            const bool decrypted = TFHEpp::tlweSymDecrypt<Target>(
                output, secret_key.key.get<Target>());
            const Target::T phase = TFHEpp::tlweSymPhase<Target>(
                output, secret_key.key.get<Target>());
            const Target::T expected = message
                ? static_cast<Target::T>(Target::μ)
                : static_cast<Target::T>(-Target::μ);
            const std::int32_t signed_error =
                std::bit_cast<std::int32_t>(phase - expected);
            const double phase_error =
                std::abs(static_cast<double>(signed_error)) * 0x1p-32;
            maximum_phase_error = std::max(maximum_phase_error, phase_error);
            std::cout << "message=" << message << ", phase="
                      << static_cast<double>(
                             std::bit_cast<std::int32_t>(phase)) * 0x1p-32
                      << ", phase_error=" << phase_error << '\n';
            if (decrypted != message)
                throw std::runtime_error(
                    "fixed-point Blind Rotate changed the Boolean message");
        }

        std::cout << std::fixed << std::setprecision(6)
                  << "Fixed-point TFHEpp Blind Rotate passed; keygen_s="
                  << key_seconds << ", bootstrap_s=[" << bootstrap_seconds[0]
                  << ", " << bootstrap_seconds[1]
                  << "], max_phase_error=" << maximum_phase_error
                  << ", CMUXes="
                  << stats.cmux_count
                  << ", BK overflows=" << stats.bootstrapping_key.overflows
                  << ", FFT overflows=" << stats.forward_fft.overflows
                  << ", pointwise overflows=" << stats.pointwise.overflows
                  << ", IFFT overflows=" << stats.inverse_fft.overflows << '\n';
        return 0;
    }
    catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}

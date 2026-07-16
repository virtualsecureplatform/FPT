#include <array>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <stdexcept>

#include <verilated.h>

#include "VHOGEBlindRotateBaseline.h"

namespace {

template <class Wide>
void clear_wide(Wide &value)
{
    for (int word = 0; word < 16; ++word) value[word] = 0;
}

void clear_inputs(VHOGEBlindRotateBaseline &dut)
{
    clear_wide(dut.io_tlwe_TDATA);
    clear_wide(dut.io_bootstrappingKey_0_TDATA);
    clear_wide(dut.io_bootstrappingKey_1_TDATA);
    clear_wide(dut.io_bootstrappingKey_2_TDATA);
    clear_wide(dut.io_bootstrappingKey_3_TDATA);
    clear_wide(dut.io_bootstrappingKey_4_TDATA);
    clear_wide(dut.io_bootstrappingKey_5_TDATA);
    clear_wide(dut.io_bootstrappingKey_6_TDATA);
    clear_wide(dut.io_bootstrappingKey_7_TDATA);
}

std::array<bool, 8> key_ready(const VHOGEBlindRotateBaseline &dut)
{
    return {static_cast<bool>(dut.io_bootstrappingKey_0_TREADY),
            static_cast<bool>(dut.io_bootstrappingKey_1_TREADY),
            static_cast<bool>(dut.io_bootstrappingKey_2_TREADY),
            static_cast<bool>(dut.io_bootstrappingKey_3_TREADY),
            static_cast<bool>(dut.io_bootstrappingKey_4_TREADY),
            static_cast<bool>(dut.io_bootstrappingKey_5_TREADY),
            static_cast<bool>(dut.io_bootstrappingKey_6_TREADY),
            static_cast<bool>(dut.io_bootstrappingKey_7_TREADY)};
}

std::array<bool, 8> key_valid(const VHOGEBlindRotateBaseline &dut)
{
    return {static_cast<bool>(dut.io_bootstrappingKey_0_TVALID),
            static_cast<bool>(dut.io_bootstrappingKey_1_TVALID),
            static_cast<bool>(dut.io_bootstrappingKey_2_TVALID),
            static_cast<bool>(dut.io_bootstrappingKey_3_TVALID),
            static_cast<bool>(dut.io_bootstrappingKey_4_TVALID),
            static_cast<bool>(dut.io_bootstrappingKey_5_TVALID),
            static_cast<bool>(dut.io_bootstrappingKey_6_TVALID),
            static_cast<bool>(dut.io_bootstrappingKey_7_TVALID)};
}

void drive_key_valid(VHOGEBlindRotateBaseline &dut,
                     const std::array<std::uint64_t, 8> &beats,
                     const std::uint64_t expected)
{
    dut.io_bootstrappingKey_0_TVALID = beats[0] < expected;
    dut.io_bootstrappingKey_1_TVALID = beats[1] < expected;
    dut.io_bootstrappingKey_2_TVALID = beats[2] < expected;
    dut.io_bootstrappingKey_3_TVALID = beats[3] < expected;
    dut.io_bootstrappingKey_4_TVALID = beats[4] < expected;
    dut.io_bootstrappingKey_5_TVALID = beats[5] < expected;
    dut.io_bootstrappingKey_6_TVALID = beats[6] < expected;
    dut.io_bootstrappingKey_7_TVALID = beats[7] < expected;
}

}  // namespace

int main(int argc, char **argv)
{
    Verilated::commandArgs(argc, argv);
    VHOGEBlindRotateBaseline dut;
    clear_inputs(dut);
    dut.io_tlwe_TVALID = 0;
    dut.io_bootstrappingKey_0_TVALID = 0;
    dut.io_bootstrappingKey_1_TVALID = 0;
    dut.io_bootstrappingKey_2_TVALID = 0;
    dut.io_bootstrappingKey_3_TVALID = 0;
    dut.io_bootstrappingKey_4_TVALID = 0;
    dut.io_bootstrappingKey_5_TVALID = 0;
    dut.io_bootstrappingKey_6_TVALID = 0;
    dut.io_bootstrappingKey_7_TVALID = 0;
    dut.io_result_TREADY = 1;

    std::uint64_t cycles = 0;
    std::uint64_t input_beats = 0;
    std::uint64_t output_beats = 0;
    std::array<std::uint64_t, 8> key_beats{};
    std::uint64_t maximum_key_skew = 0;

    const auto tick = [&] {
        dut.clock = 0;
        dut.eval();

        if (!dut.reset && dut.io_tlwe_TVALID && dut.io_tlwe_TREADY)
            ++input_beats;
        if (!dut.reset) {
            const auto ready = key_ready(dut);
            const auto valid = key_valid(dut);
            for (int index = 0; index < 8; ++index)
                if (valid[index] && ready[index]) ++key_beats[index];
            std::uint64_t minimum = key_beats[0];
            std::uint64_t maximum = key_beats[0];
            for (int index = 1; index < 8; ++index) {
                if (key_beats[index] < minimum) minimum = key_beats[index];
                if (key_beats[index] > maximum) maximum = key_beats[index];
            }
            if (maximum - minimum > maximum_key_skew)
                maximum_key_skew = maximum - minimum;
        }
        const bool output_fire = !dut.reset && dut.io_result_TVALID &&
            dut.io_result_TREADY;
        const bool output_last = output_fire && dut.io_result_TLAST;
        if (output_fire) ++output_beats;

        dut.clock = 1;
        dut.eval();
        ++cycles;
        return output_last;
    };

    dut.reset = 1;
    for (int cycle = 0; cycle < 5; ++cycle) tick();
    dut.reset = 0;
    const std::uint64_t start_cycle = cycles;

    constexpr std::uint64_t input_beats_expected = 40;
    // HOGE's DataMover command transfers one complete bootstrapping key on
    // every 512-bit bus: n * (k + 1) * l * numcycle beats.  The eight buses
    // are grouped four-at-a-time into the two internal TRGSWBatchMemory rows.
    constexpr std::uint64_t key_beats_expected = 636 * 2 * 3 * 32;
    constexpr std::uint64_t output_beats_expected = 2 * (1024 + 1);
    constexpr std::uint64_t timeout_cycles = 2'000'000;
    bool completed = false;
    while (cycles - start_cycle < timeout_cycles) {
        dut.io_tlwe_TVALID = input_beats < input_beats_expected;
        drive_key_valid(dut, key_beats, key_beats_expected);
        if (tick()) {
            completed = true;
            break;
        }
    }

    dut.final();
    if (!completed)
        throw std::runtime_error("HOGE Blind Rotate schedule timed out");
    if (input_beats != input_beats_expected)
        throw std::runtime_error("HOGE did not consume both input TLWEs");
    if (output_beats != output_beats_expected)
        throw std::runtime_error("HOGE returned an unexpected TLWE beat count");
    for (int index = 0; index < 8; ++index)
        if (key_beats[index] != key_beats_expected)
            throw std::runtime_error(
                "HOGE consumed an unexpected bootstrapping-key length");
    const std::uint64_t elapsed = cycles - start_cycle;
    std::cout << "hoge_blind_rotate_batch_cycles=" << elapsed << '\n'
              << std::fixed << std::setprecision(1)
              << "hoge_blind_rotate_cycles_per_result="
              << static_cast<double>(elapsed) / 2.0 << '\n'
              << "hoge_input_beats=" << input_beats << '\n'
              << "hoge_key_beats_per_bus=" << key_beats[0] << '\n'
              << "hoge_maximum_key_beat_skew=" << maximum_key_skew << '\n'
              << "hoge_output_beats=" << output_beats << '\n';
}

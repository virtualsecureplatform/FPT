#include <algorithm>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <limits>
#include <stdexcept>

#include <verilated.h>

#include "VBatchedBlindRotateSampleExtractEngine.h"

namespace {

void clear_inputs(VBatchedBlindRotateSampleExtractEngine &dut)
{
#include "fpt_zero_inputs.inc"
}

}  // namespace

int main(int argc, char **argv)
{
    Verilated::commandArgs(argc, argv);
    VBatchedBlindRotateSampleExtractEngine dut;
    clear_inputs(dut);
    dut.io_resultReady = 1;

    std::uint64_t cycles = 0;
    std::uint64_t input_coefficients = 0;
    std::uint64_t key_transactions = 0;
    std::uint64_t output_beats = 0;
    std::uint64_t output_last_count = 0;
    std::uint64_t compute_done_edge =
        std::numeric_limits<std::uint64_t>::max();
    std::uint64_t last_key_transaction_cycle =
        std::numeric_limits<std::uint64_t>::max();
    std::uint64_t minimum_key_transaction_gap =
        std::numeric_limits<std::uint64_t>::max();
    std::uint64_t maximum_key_transaction_gap = 0;

    constexpr std::uint64_t contexts = 16;
    constexpr std::uint64_t dimension = 630;
    constexpr std::uint64_t polynomial_size = 1024;
    constexpr std::uint64_t coefficients_per_input = dimension + 1;
    constexpr std::uint64_t coefficients_per_output = polynomial_size + 1;
    constexpr std::uint64_t expected_inputs =
        contexts * coefficients_per_input;
    constexpr std::uint64_t expected_commands = contexts * dimension;
    constexpr std::uint64_t expected_outputs =
        contexts * coefficients_per_output;
    constexpr std::uint64_t timeout_cycles = 2'000'000;

    const auto tick = [&] {
        dut.clock = 0;
        dut.eval();

        bool done = false;
        if (!dut.reset) {
            if (dut.io_inputValid && dut.io_inputReady)
                ++input_coefficients;
            if (dut.io_keyValid && dut.io_keyFirst) {
                if (last_key_transaction_cycle !=
                    std::numeric_limits<std::uint64_t>::max()) {
                    const std::uint64_t gap =
                        cycles - last_key_transaction_cycle;
                    minimum_key_transaction_gap =
                        std::min(minimum_key_transaction_gap, gap);
                    maximum_key_transaction_gap =
                        std::max(maximum_key_transaction_gap, gap);
                }
                last_key_transaction_cycle = cycles;
                ++key_transactions;
            }
            if (dut.io_computeDone &&
                compute_done_edge ==
                    std::numeric_limits<std::uint64_t>::max())
                compute_done_edge = cycles + 1;
            if (dut.io_resultValid && dut.io_resultReady) {
                const std::uint64_t expected_context =
                    output_beats / coefficients_per_output;
                if (dut.io_resultContext != expected_context)
                    throw std::runtime_error(
                        "FPT returned a result for the wrong context");
                if (dut.io_result != 0)
                    throw std::runtime_error(
                        "zero FPT schedule test returned nonzero data");
                ++output_beats;
                if (dut.io_resultLast) {
                    ++output_last_count;
                    if (output_beats != expected_outputs)
                        throw std::runtime_error(
                            "FPT asserted resultLast before the final TLWE");
                }
            }
            done = dut.io_done;
        }

        dut.clock = 1;
        dut.eval();
        ++cycles;
        return done;
    };

    dut.reset = 1;
    for (int cycle = 0; cycle < 5; ++cycle) tick();
    dut.reset = 0;
    const std::uint64_t batch_start_edge = cycles + 1;

    for (std::uint64_t context = 0; context < contexts; ++context) {
        while (!dut.io_inputStartReady) {
            tick();
            if (cycles > timeout_cycles)
                throw std::runtime_error("FPT input-start timeout");
        }
        dut.io_inputContext = context;
        dut.io_testVector = 0;
        dut.io_inputStart = 1;
        tick();
        dut.io_inputStart = 0;

        dut.io_inputValid = 1;
        dut.io_inputCoefficient = 0;
        for (std::uint64_t index = 0; index < coefficients_per_input;
             ++index) {
            while (!dut.io_inputReady) {
                tick();
                if (cycles > timeout_cycles)
                    throw std::runtime_error("FPT input-data timeout");
            }
            tick();
        }
        dut.io_inputValid = 0;
        while (!dut.io_inputDone) {
            tick();
            if (cycles > timeout_cycles)
                throw std::runtime_error("FPT accumulator-load timeout");
        }
        if (dut.io_inputDoneContext != context)
            throw std::runtime_error("FPT initialized the wrong context");
        tick();
    }

    while (!dut.io_runReady) {
        tick();
        if (cycles > timeout_cycles)
            throw std::runtime_error("FPT run-start timeout");
    }
    const std::uint64_t run_start_edge = cycles + 1;
    dut.io_runStart = 1;
    tick();
    dut.io_runStart = 0;

    bool completed = false;
    while (cycles < timeout_cycles) {
        if (tick()) {
            completed = true;
            break;
        }
    }
    dut.final();

    if (!completed)
        throw std::runtime_error("FPT Blind Rotate schedule timed out");
    if (input_coefficients != expected_inputs)
        throw std::runtime_error("FPT consumed an unexpected TLWE length");
    if (key_transactions != expected_commands)
        throw std::runtime_error(
            "FPT issued an unexpected bootstrapping-key transaction count");
    if (output_beats != expected_outputs || output_last_count != 1)
        throw std::runtime_error("FPT returned an unexpected TLWE batch");
    if (compute_done_edge == std::numeric_limits<std::uint64_t>::max())
        throw std::runtime_error("FPT never completed the CMUX schedule");

    const std::uint64_t batch_cycles = cycles - batch_start_edge + 1;
    const std::uint64_t input_phase_cycles =
        run_start_edge - batch_start_edge;
    const std::uint64_t compute_phase_cycles =
        compute_done_edge - run_start_edge;
    const std::uint64_t drain_tail_cycles =
        cycles - compute_done_edge + 1;
    std::cout << "fpt_blind_rotate_batch_cycles=" << batch_cycles << '\n'
              << std::fixed << std::setprecision(1)
              << "fpt_blind_rotate_cycles_per_result="
              << static_cast<double>(batch_cycles) /
                     static_cast<double>(contexts)
              << '\n'
              << "fpt_blind_rotate_input_phase_cycles="
              << input_phase_cycles << '\n'
              << "fpt_blind_rotate_compute_phase_cycles="
              << compute_phase_cycles << '\n'
              << "fpt_blind_rotate_drain_tail_cycles="
              << drain_tail_cycles << '\n'
              << "fpt_input_coefficients=" << input_coefficients << '\n'
              << "fpt_key_transactions=" << key_transactions << '\n'
              << "fpt_minimum_key_transaction_gap="
              << minimum_key_transaction_gap << '\n'
              << "fpt_maximum_key_transaction_gap="
              << maximum_key_transaction_gap << '\n'
              << "fpt_output_beats=" << output_beats << '\n';
}

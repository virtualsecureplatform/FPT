#pragma once
#include "fpt/paper_streaming_mac.hpp"

namespace fpt::paper_streaming {
struct ExternalDiagnostics {
    std::uint64_t mac_overflows=0, inverse_input_overflows=0;
};
struct ExternalSchedule {
    TransformSchedule forward, inverse;
    MacSchedule mac;
    ExternalDiagnostics *diagnostics = nullptr;
};
using KeyRows = std::array<std::array<Frame,3>,6>;

// The caller supplies already decomposed, fixed-point digit polynomials.
// This function does not perform CMUX rotation or Torus coefficient updates.
inline std::array<Polynomial,3> external_product(
    const std::array<Polynomial,6> &digits, const KeyRows &key,
    const ExternalSchedule &schedule, QuantizationStats &stats,
    bool retain_normalization_bits=false)
{
    if(schedule.forward.output_format().integer_bits!=schedule.mac.spectrum.integer_bits ||
       schedule.forward.output_format().fractional_bits!=schedule.mac.spectrum.fractional_bits)
        throw std::invalid_argument("forward/MAC binary point mismatch");
    const int narrow=schedule.mac.accumulator.fractional_bits-schedule.inverse.data.fractional_bits;
    if(narrow<0)throw std::invalid_argument("inverse input widening is not scheduled");
    unsigned divisions=0;for(auto s:schedule.forward.divide_bits)divisions+=s;
    SixRowMac mac(schedule.mac);
    for(unsigned row=0;row<6;++row) {
        const auto spectrum=negacyclic_forward(digits[row],schedule.forward,stats);
        const auto before=stats.overflows;
        mac.push(spectrum,key[row],stats);
        if(schedule.diagnostics)schedule.diagnostics->mac_overflows+=stats.overflows-before;
    }
    std::array<Polynomial,3> output;
    for(unsigned c=0;c<3;++c) {
        Frame input;
        const auto before=stats.overflows;
        for(unsigned j=0;j<256;++j) {
            input[j].real=requantize(mac.result()[c][j].real,narrow,schedule.inverse.data,stats);
            input[j].imag=requantize(mac.result()[c][j].imag,narrow,schedule.inverse.data,stats);
        }
        if(schedule.diagnostics)schedule.diagnostics->inverse_input_overflows+=stats.overflows-before;
        output[c]=negacyclic_inverse(input,schedule.inverse,divisions,stats,retain_normalization_bits);
    }
    return output;
}
} // namespace fpt::paper_streaming

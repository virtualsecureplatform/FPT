#pragma once
#include "fpt/paper_streaming_contract.hpp"
#include <cmath>
#include <numbers>
#include <optional>

namespace fpt::paper_streaming {
struct TransformDiagnostics {
    // twist, radix-4 stages 0..3, untwist; diagnostic counters do not affect arithmetic.
    std::array<std::uint64_t,6> overflows{};
};
struct TransformSchedule {
    FixedFormat data{15,14};
    unsigned twiddle_fraction = 23;
    std::array<unsigned,4> divide_bits{};
    TransformDiagnostics *diagnostics = nullptr;
    std::optional<std::array<FixedFormat,4>> stage_data = std::nullopt;
    // Optional rounded rotation boundary before the exact radix-4 sum.
    // Null retains the original full product until the stage output.
    std::optional<unsigned> leg_guard_bits = std::nullopt;
    FixedFormat output_format() const {return stage_data?stage_data->back():data;}
};
using Frame = std::array<FixedComplex,256>;
using Polynomial = std::array<std::int64_t,512>;

inline FixedComplex rotate(FixedComplex value, long double angle,
                           const TransformSchedule &schedule,
                           QuantizationStats &stats, unsigned extra_shift=0)
{
    if (!schedule.data.valid() || schedule.twiddle_fraction>40 || extra_shift>8)
        throw std::invalid_argument("invalid twist schedule");
    const auto c=static_cast<std::int64_t>(std::nearbyint(std::ldexp(std::cos(angle),schedule.twiddle_fraction)));
    const auto s=static_cast<std::int64_t>(std::nearbyint(std::ldexp(std::sin(angle),schedule.twiddle_fraction)));
    const auto product=gauss_product(value.real,value.imag,c,s);
    return {requantize(product.real,schedule.twiddle_fraction+extra_shift,schedule.data,stats),
            requantize(product.imag,schedule.twiddle_fraction+extra_shift,schedule.data,stats)};
}

// Natural order in/out. Four radix-4 stages; inverse is unnormalized.
inline Frame transform(const Frame &input, const TransformSchedule &schedule,
                       bool inverse, QuantizationStats &stats)
{
    if (!schedule.data.valid() || schedule.twiddle_fraction > 40)
        throw std::invalid_argument("invalid streaming FFT schedule");
    if(schedule.leg_guard_bits && *schedule.leg_guard_bits>schedule.twiddle_fraction)
        throw std::invalid_argument("rotation guard exceeds twiddle precision");
    for (auto shift : schedule.divide_bits)
        if (shift > 2) throw std::invalid_argument("invalid radix-4 scale");
    Frame values;
    for (unsigned i=0;i<256;++i) {
        unsigned index=i, reversed=0;
        for (unsigned digit=0;digit<4;++digit) {
            reversed=4*reversed+index%4; index/=4;
        }
        values[reversed]=input[i];
    }
    unsigned length=4;
    auto input_format=schedule.data;
    for (unsigned stage=0;stage<4;++stage,length*=4) {
        const auto before=stats.overflows;
        const auto destination=schedule.stage_data?(*schedule.stage_data)[stage]:schedule.data;
        if(!destination.valid() || destination.fractional_bits>input_format.fractional_bits)
            throw std::invalid_argument("invalid FFT stage binary point");
        const unsigned quarter=length/4;
        for (unsigned base=0;base<256;base+=length)
            for (unsigned j=0;j<quarter;++j) {
                std::array<ComplexProduct,4> rotated;
                for (unsigned leg=0;leg<4;++leg) {
                    const auto v=values[base+j+leg*quarter];
                    const long double angle=(inverse?2:-2)*std::numbers::pi_v<long double>*leg*j/length;
                    const auto c=static_cast<std::int64_t>(std::nearbyint(std::ldexp(std::cos(angle),schedule.twiddle_fraction)));
                    const auto s=static_cast<std::int64_t>(std::nearbyint(std::ldexp(std::sin(angle),schedule.twiddle_fraction)));
                    rotated[leg]=gauss_product(v.real,v.imag,c,s);
                    if(schedule.leg_guard_bits) {
                        const FixedFormat leg_format{input_format.integer_bits+1,
                            input_format.fractional_bits+static_cast<int>(*schedule.leg_guard_bits)};
                        rotated[leg].real=requantize(rotated[leg].real,schedule.twiddle_fraction-*schedule.leg_guard_bits,leg_format,stats);
                        rotated[leg].imag=requantize(rotated[leg].imag,schedule.twiddle_fraction-*schedule.leg_guard_bits,leg_format,stats);
                    }
                }
                const auto outputs=butterfly4(rotated,inverse);
                for (unsigned leg=0;leg<4;++leg) {
                    auto &v=values[base+j+leg*quarter];
                    const unsigned shift=schedule.leg_guard_bits.value_or(schedule.twiddle_fraction)+schedule.divide_bits[stage]+
                        input_format.fractional_bits-destination.fractional_bits;
                    v.real=requantize(outputs[leg].real,shift,destination,stats);
                    v.imag=requantize(outputs[leg].imag,shift,destination,stats);
                }
            }
        if(schedule.diagnostics)schedule.diagnostics->overflows[stage+1]+=stats.overflows-before;
        input_format=destination;
    }
    return values;
}

inline Frame negacyclic_forward(const Polynomial &input,
                               const TransformSchedule &schedule,
                               QuantizationStats &stats)
{
    Frame folded;
    const auto before=stats.overflows;
    for (unsigned j=0;j<256;++j)
        folded[j]=rotate({input[j],input[j+256]},std::numbers::pi_v<long double>*j/512,schedule,stats);
    if(schedule.diagnostics)schedule.diagnostics->overflows[0]+=stats.overflows-before;
    return transform(folded,schedule,false,stats);
}

inline Polynomial negacyclic_inverse(const Frame &input,
                                     const TransformSchedule &schedule,
                                     unsigned spectrum_divisions,
                                     QuantizationStats &stats,
                                     bool retain_normalization_bits=false)
{
    unsigned inverse_divisions=0;
    for (auto s:schedule.divide_bits) inverse_divisions+=s;
    if(spectrum_divisions>8 || inverse_divisions>8)
        throw std::invalid_argument("invalid normalization exponent");
    const auto shift=Scale{static_cast<int>(spectrum_divisions),static_cast<int>(inverse_divisions)}.terminal_normalization();
    const auto values=transform(input,schedule,true,stats);
    Polynomial result;
    const auto before=stats.overflows;
    auto output_schedule=schedule;
    output_schedule.data=schedule.output_format();
    for (unsigned j=0;j<256;++j) {
        // Retaining normalization bits changes the output binary point from
        // F to F+shift, rather than rounding away shift low bits here.
        const auto v=rotate(values[j],-std::numbers::pi_v<long double>*j/512,output_schedule,stats,
                            retain_normalization_bits?0:shift);
        result[j]=v.real;result[j+256]=v.imag;
    }
    if(schedule.diagnostics)schedule.diagnostics->overflows[5]+=stats.overflows-before;
    return result;
}

inline std::uint32_t torus_word(std::int64_t raw, unsigned fractional_bits)
{
    if(fractional_bits>62)throw std::invalid_argument("invalid Torus binary point");
    QuantizationStats ignored;
    if(fractional_bits>32) {
        // Modular narrowing is intentional only at the final Torus boundary.
        const auto rounded=requantize(raw,fractional_bits-32,{33,0},ignored);
        return static_cast<std::uint32_t>(rounded);
    }
    return static_cast<std::uint32_t>(static_cast<std::uint64_t>(raw)<<(32-fractional_bits));
}
} // namespace fpt::paper_streaming

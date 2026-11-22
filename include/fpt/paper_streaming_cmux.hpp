#pragma once
#include "fpt/paper_streaming_external_product.hpp"

namespace fpt::paper_streaming {
using Ciphertext = std::array<std::array<std::uint32_t,512>,3>;

inline Ciphertext rotate_ciphertext(const Ciphertext &state, unsigned exponent)
{
    Ciphertext result;
    exponent%=1024;
    for(unsigned c=0;c<3;++c)for(unsigned j=0;j<512;++j) {
        const unsigned index=j+exponent;
        const auto value=state[c][j];
        result[c][index%512]=((index/512)&1)?0u-value:value;
    }
    return result;
}

inline std::array<Polynomial,6> decompose_difference(
    const Ciphertext &state, unsigned exponent, FixedFormat forward)
{
    if(!forward.valid() || forward.integer_bits<9)
        throw std::invalid_argument("gadget digits require signed headroom");
    const auto rotated=rotate_ciphertext(state,exponent);
    std::array<Polynomial,6> digits;
    constexpr std::uint32_t offset=(1u<<31)+(1u<<23)+(1u<<15);
    for(unsigned c=0;c<3;++c)for(unsigned j=0;j<512;++j) {
        const std::uint32_t biased=rotated[c][j]-state[c][j]+offset;
        for(unsigned level=0;level<2;++level) {
            const int digit=static_cast<int>((biased>>(24-8*level))&255)-128;
            digits[2*c+level][j]=static_cast<std::int64_t>(digit)*(std::int64_t{1}<<forward.fractional_bits);
        }
    }
    return digits;
}

inline Ciphertext cmux(const Ciphertext &state, unsigned exponent,
                        const KeyRows &key, const ExternalSchedule &schedule,
                        QuantizationStats &stats)
{
    if(exponent%1024==0)return state;
    const auto digits=decompose_difference(state,exponent,schedule.forward.data);
    const auto update=external_product(digits,key,schedule,stats,true);
    unsigned forward_divisions=0,inverse_divisions=0;
    for(auto shift:schedule.forward.divide_bits)forward_divisions+=shift;
    for(auto shift:schedule.inverse.divide_bits)inverse_divisions+=shift;
    const auto normalization=Scale{static_cast<int>(forward_divisions),static_cast<int>(inverse_divisions)}.terminal_normalization();
    const unsigned output_fraction=schedule.inverse.output_format().fractional_bits+normalization;
    Ciphertext result;
    for(unsigned c=0;c<3;++c)for(unsigned j=0;j<512;++j)
        result[c][j]=state[c][j]+torus_word(update[c][j],output_fraction);
    return result;
}
} // namespace fpt::paper_streaming

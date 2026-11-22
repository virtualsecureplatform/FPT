#pragma once
#include <cstdint>
#include <stdexcept>

namespace fpt::paper_coefficient17 {
constexpr unsigned discarded_bits=15;
constexpr std::uint32_t mask=0x1ffffu;
constexpr std::uint32_t decode(std::uint32_t code){return (code&mask)<<discarded_bits;}
inline std::uint32_t encode_exact(std::uint32_t word)
{
    if(word&0x7fffu)throw std::invalid_argument("coefficient is not on the profile's 17-bit Torus grid");
    return word>>discarded_bits;
}
// Arbitrary 32-bit lookup entries are rounded only at the versioned profile
// load boundary. All subsequent updates already have fifteen zero low bits.
constexpr std::uint32_t quantize_lookup(std::uint32_t word)
{
    const auto high=word>>discarded_bits,low=word&0x7fffu;
    return (high+(low>0x4000u || (low==0x4000u && (high&1))))&mask;
}
constexpr std::uint32_t negate(std::uint32_t code){return (0u-code)&mask;}
constexpr std::uint32_t add(std::uint32_t a,std::uint32_t b){return (a+b)&mask;}
inline int decompose_difference(std::uint32_t rotated,std::uint32_t current,unsigned level)
{
    if(level>1)throw std::invalid_argument("invalid Set-I decomposition level");
    const auto biased=(rotated-current+0x10101u)&mask;
    return int((biased>>(level==0?9:1))&255)-128;
}
} // namespace fpt::paper_coefficient17

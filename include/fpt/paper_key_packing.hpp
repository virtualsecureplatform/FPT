#pragma once
#include "fpt/fixed_point.hpp"
#include <array>
#include <cstdint>
#include <span>
#include <stdexcept>

namespace fpt::paper_key_packing {
// Opt-in fullstaged32 key payload: signed Q7.21 real then imaginary,
// little endian, densely packed. DMA framing/versioning is a separate layer.
constexpr unsigned coefficient_bits=28, bytes_per_complex=7;
constexpr unsigned points=256, components=3, rows=6, steps=586;
constexpr unsigned row_bytes=components*points*bytes_per_complex;
constexpr unsigned step_bytes=rows*row_bytes;
constexpr unsigned key_bytes=steps*step_bytes;
using Encoded=std::array<std::uint8_t,bytes_per_complex>;
enum class SpectrumOrder {Natural,ButterflyGroups};
constexpr unsigned natural_point(unsigned point,SpectrumOrder order)
{
    return order==SpectrumOrder::Natural?point:point/4+(point%4)*64;
}

inline Encoded encode(FixedComplex value)
{
    constexpr std::int64_t limit=1LL<<27;
    if(value.real < -limit || value.real >= limit || value.imag < -limit || value.imag >= limit)
        throw std::out_of_range("paper key does not fit Q7.21");
    constexpr std::uint64_t mask=(1ULL<<28)-1;
    const auto word=(std::uint64_t(value.real)&mask)|((std::uint64_t(value.imag)&mask)<<28);
    Encoded result;
    for(unsigned byte=0;byte<bytes_per_complex;++byte)result[byte]=word>>(8*byte);
    return result;
}

inline FixedComplex decode(const Encoded &encoded)
{
    std::uint64_t word=0;
    for(unsigned byte=0;byte<bytes_per_complex;++byte)word|=std::uint64_t(encoded[byte])<<(8*byte);
    auto extend=[](std::uint64_t value) {
        value&=(1ULL<<28)-1;
        return std::int64_t(value)-((value&(1ULL<<27))?(1LL<<28):0);
    };
    return {extend(word),extend(word>>28)};
}

constexpr unsigned offset(unsigned step,unsigned row,unsigned component,unsigned point)
{
    return (((step*rows+row)*components+component)*points+point)*bytes_per_complex;
}

// Caller owns the destination allocation; no duplicate full key is retained.
template<class Provider>
inline void pack(std::span<std::uint8_t> destination,Provider &&get,
                 SpectrumOrder order=SpectrumOrder::Natural)
{
    if(destination.size()!=key_bytes)throw std::invalid_argument("paper key payload size mismatch");
    for(unsigned step=0;step<steps;++step)for(unsigned row=0;row<rows;++row)
        for(unsigned component=0;component<components;++component)for(unsigned point=0;point<points;++point) {
            const auto value=encode(get(step,row,component,natural_point(point,order)));
            const auto index=offset(step,row,component,point);
            for(unsigned byte=0;byte<bytes_per_complex;++byte)destination[index+byte]=value[byte];
        }
}
static_assert(row_bytes==5376 && step_bytes==32256 && key_bytes==18902016);
static_assert(row_bytes%64==0);
} // namespace fpt::paper_key_packing

#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <limits>
#include <span>
#include <stdexcept>
#include <vector>

namespace fpt::paper_dma_layout {
constexpr unsigned version=2,profile_id=0x46505432,contexts=12,dimension=586,points=512;
constexpr unsigned input_words=points+contexts*(dimension+1),output_words=contexts*(2*points+1);
constexpr unsigned align64(unsigned bytes){return (bytes+63)&~63u;}
constexpr unsigned input_stride=align64(4*input_words),output_stride=align64(4*output_words);
using Tlwe=std::array<std::uint32_t,dimension+1>;
using Sample=std::array<std::uint32_t,2*points+1>;
struct InputFrame {
    std::array<std::uint32_t,points> lookup;
    std::array<Tlwe,contexts> inputs;
};
inline void put(std::span<std::uint8_t> bytes,std::size_t offset,std::uint32_t value)
{for(unsigned byte=0;byte<4;++byte)bytes[offset+byte]=std::uint8_t(value>>(8*byte));}
inline std::uint32_t get(std::span<const std::uint8_t> bytes,std::size_t offset)
{
    std::uint32_t value=0;
    for(unsigned byte=0;byte<4;++byte)value|=std::uint32_t(bytes[offset+byte])<<(8*byte);
    return value;
}
inline std::vector<std::uint8_t> pack(std::span<const InputFrame> frames)
{
    if(frames.empty() || frames.size()>4096)throw std::invalid_argument("paper DMA job requires 1..4096 frames");
    std::vector<std::uint8_t> bytes(frames.size()*input_stride,0);
    for(std::size_t frame=0;frame<frames.size();++frame) {
        std::size_t offset=frame*input_stride;
        for(auto value:frames[frame].lookup){put(bytes,offset,value);offset+=4;}
        for(const auto &input:frames[frame].inputs)for(auto value:input){put(bytes,offset,value);offset+=4;}
    }
    return bytes;
}
inline std::vector<std::array<Sample,contexts>> unpack(std::span<const std::uint8_t> bytes,unsigned frames)
{
    if(frames==0 || frames>4096 || bytes.size()!=std::size_t(frames)*output_stride)
        throw std::invalid_argument("paper DMA output buffer size mismatch");
    std::vector<std::array<Sample,contexts>> result(frames);
    for(unsigned frame=0;frame<frames;++frame) {
        std::size_t offset=std::size_t(frame)*output_stride;
        for(auto &sample:result[frame])for(auto &value:sample){value=get(bytes,offset);offset+=4;}
    }
    return result;
}
static_assert(input_words==7556 && input_stride==30272);
static_assert(output_words==12300 && output_stride==49216);
} // namespace fpt::paper_dma_layout

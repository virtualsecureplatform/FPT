#pragma once
#include "fpt/paper_dma_layout.hpp"
#include "fpt/paper_grouped_key_packing.hpp"
#include <filesystem>
#include <fstream>

namespace fpt::paper_dma_fixture {
// Diagnostic loader for the source-bound fixture format, not a key generator.
// Repeated frames are explicit: they do not count as fresh numerical trials.
struct Data {
    std::array<std::vector<std::uint8_t>,4> keys;
    std::vector<paper_dma_layout::InputFrame> frames;
    std::vector<std::array<paper_dma_layout::Sample,12>> expected;
    unsigned distinct_inputs=24;
};
inline std::uint32_t word(std::istream &input)
{
    std::uint32_t result=0;
    for(unsigned byte=0;byte<4;++byte){const auto v=input.get();if(v<0)throw std::runtime_error("truncated DMA fixture");result|=std::uint32_t(v)<<(8*byte);}
    return result;
}
inline Data load_repeated(const std::filesystem::path &directory,unsigned count)
{
    namespace pk=paper_key_packing;namespace grouped=paper_grouped_key_packing;namespace dma=paper_dma_layout;
    if(count==0 || count>4096)throw std::invalid_argument("invalid repeated fixture count");
    Data result;
    std::ifstream key_file(directory/"key.bin",std::ios::binary);
    for(auto value:{0x4650544bu,1u,586u,512u,2u,28u,7u,18902016u})
        if(word(key_file)!=value)throw std::runtime_error("DMA fixture key header mismatch");
    std::vector<std::uint8_t> natural(pk::key_bytes);
    if(!key_file.read(reinterpret_cast<char*>(natural.data()),natural.size()) || key_file.get()!=std::char_traits<char>::eof())
        throw std::runtime_error("DMA fixture key payload size mismatch");
    std::array<std::span<std::uint8_t>,4> destinations;
    for(unsigned port=0;port<4;++port){result.keys[port].resize(grouped::key_bytes(port));destinations[port]=result.keys[port];}
    grouped::pack(destinations,[&](unsigned step,unsigned row,unsigned component,unsigned point) {
        pk::Encoded bytes;const auto offset=pk::offset(step,row,component,point);
        std::copy_n(natural.begin()+offset,7,bytes.begin());return pk::decode(bytes);
    });
    std::array<dma::InputFrame,2> frames;
    std::array<std::array<dma::Sample,12>,2> samples;
    for(unsigned kind=0;kind<2;++kind) {
        std::ifstream file(directory/(kind?"batch-ramp8.bin":"batch-bool.bin"),std::ios::binary);
        for(auto value:{0x46505442u,1u,586u,12u,26u,4u,40u,kind})
            if(word(file)!=value)throw std::runtime_error("DMA fixture batch header mismatch");
        for(unsigned i=0;i<12*3*512+12*586;++i)(void)word(file);
        for(unsigned context=0;context<12;++context)for(unsigned component=0;component<3;++component)
            for(unsigned point=0;point<512;++point) {
                const auto value=word(file);
                if(component<2)samples[kind][context][component*512+(point==0?0:512-point)]=point==0?value:0u-value;
                else if(point==0)samples[kind][context][1024]=value;
            }
        for(auto &input:frames[kind].inputs)for(auto &value:input)value=word(file);
        for(auto &value:frames[kind].lookup)value=word(file);
        if(file.get()!=std::char_traits<char>::eof())throw std::runtime_error("DMA fixture has trailing batch data");
    }
    result.frames.resize(count);result.expected.resize(count);
    for(unsigned frame=0;frame<count;++frame){result.frames[frame]=frames[frame%2];result.expected[frame]=samples[frame%2];}
    result.distinct_inputs=count==1?12:24;
    return result;
}
} // namespace fpt::paper_dma_fixture

#include "fpt/paper_dma_layout.hpp"
#include <cassert>
#include <random>
int main()
{
    namespace dma=fpt::paper_dma_layout;
    std::mt19937 random(313);
    std::vector<dma::InputFrame> frames(3);
    for(auto &frame:frames) {
        for(auto &value:frame.lookup)value=random();
        for(auto &input:frame.inputs)for(auto &value:input)value=random();
    }
    const auto bytes=dma::pack(frames);
    for(unsigned frame=0;frame<3;++frame) {
        unsigned offset=frame*dma::input_stride;
        for(auto value:frames[frame].lookup){assert(dma::get(bytes,offset)==value);offset+=4;}
        for(const auto &input:frames[frame].inputs)for(auto value:input){assert(dma::get(bytes,offset)==value);offset+=4;}
        while(offset<(frame+1)*dma::input_stride)assert(bytes[offset++]==0);
    }
    std::vector<std::uint8_t> outputs(3*dma::output_stride,0xa5);
    for(unsigned frame=0;frame<3;++frame)for(unsigned word=0;word<dma::output_words;++word)
        dma::put(outputs,frame*dma::output_stride+word*4,frame*100000+word);
    const auto samples=dma::unpack(outputs,3);
    for(unsigned frame=0;frame<3;++frame)for(unsigned context=0;context<12;++context)for(unsigned word=0;word<1025;++word)
        assert(samples[frame][context][word]==frame*100000+context*1025+word);
    bool rejected=false;
    try {dma::unpack(outputs,2);}catch(const std::invalid_argument &){rejected=true;}
    assert(rejected);
}

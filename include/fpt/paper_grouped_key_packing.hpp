#pragma once
#include "fpt/paper_key_packing.hpp"
#include <algorithm>

namespace fpt::paper_grouped_key_packing {
// Version 2, four port images. Each 512-bit DMA beat carries nine 56-bit
// coefficients and one padding byte. Native row/half order matches KeyCache.
constexpr unsigned version=2,ports=4,groups=43,slots=12,beat_bytes=64;
constexpr unsigned groups_per_slot(unsigned port){return port==3?10:11;}
constexpr unsigned step_bytes(unsigned port){return slots*groups_per_slot(port)*beat_bytes;}
constexpr unsigned key_bytes(unsigned port){return paper_key_packing::steps*step_bytes(port);}
constexpr unsigned total_key_bytes=3*key_bytes(0)+key_bytes(3);

template<class Provider>
inline void pack(std::array<std::span<std::uint8_t>,4> destination,Provider &&get)
{
    namespace pk=paper_key_packing;
    for(unsigned port=0;port<ports;++port) {
        if(destination[port].size()!=key_bytes(port))throw std::invalid_argument("grouped key image size mismatch");
        std::fill(destination[port].begin(),destination[port].end(),0);
        for(unsigned step=0;step<pk::steps;++step)for(unsigned slot=0;slot<slots;++slot)
            for(unsigned local=0;local<groups_per_slot(port);++local) {
                const unsigned group=port+4*local;
                const unsigned offset=step*step_bytes(port)+(slot*groups_per_slot(port)+local)*beat_bytes;
                for(unsigned item=0;item<9;++item) {
                    const unsigned lane=group*9+item;
                    if(lane>=384)continue;
                    const unsigned index=(slot%2)*128+lane%128;
                    const unsigned point=pk::natural_point(index,pk::SpectrumOrder::ButterflyGroups);
                    const auto value=pk::encode(get(step,slot/2,lane/128,point));
                    std::copy(value.begin(),value.end(),destination[port].begin()+offset+item*7);
                }
            }
    }
}
static_assert(step_bytes(0)==8448 && step_bytes(3)==7680);
static_assert(total_key_bytes==19352064);
} // namespace fpt::paper_grouped_key_packing

#include "fpt/paper_streaming_bootstrap.hpp"
#include <cassert>

int main()
{
    using namespace fpt::paper_streaming;
    assert(modulus_switch(0)==0);
    assert(modulus_switch(1u<<21)==1);
    assert(modulus_switch(0xffffffffu)==0);
    const TransformSchedule fft{{18,14},28,{}};
    const ExternalSchedule schedule{fft,fft,{{18,14},{7,19},{26,33}}};
    InputTlwe input{};
    std::array<std::uint32_t,512> lookup;
    for(unsigned j=0;j<512;++j)lookup[j]=j*1234567u;
    const KeyRows zero{};
    unsigned calls=0;
    auto provider=[&](unsigned)->const KeyRows& {++calls;return zero;};
    fpt::QuantizationStats stats;
    auto output=bootstrap(input,lookup,provider,schedule,stats);
    assert(calls==0 && output[1024]==lookup[0]);
    input[586]=1u<<22;
    output=bootstrap(input,lookup,provider,schedule,stats);
    assert(output[1024]==lookup[1]);
    // A nonzero rotation at the first and last step exercises the complete
    // loop and provider indices. Zero-key algebra is not crypto qualification.
    input[0]=1u<<22;input[585]=1u<<22;
    unsigned first=999,last=999;
    auto indexed=[&](unsigned index)->const KeyRows& {if(first==999)first=index;last=index;return zero;};
    assert(bootstrap(input,lookup,indexed,schedule,stats)==output);
    assert(first==0 && last==585 && stats.overflows==0);
    Ciphertext state{};
    for(unsigned c=0;c<2;++c)for(unsigned j=0;j<512;++j)state[c][j]=j+10*c;
    const auto extracted=sample_extract(state);
    assert(extracted[0]==0 && extracted[1]==0u-511u);
    assert(extracted[512]==10 && extracted[1023]==0u-11u);
}

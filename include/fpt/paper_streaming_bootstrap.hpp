#pragma once
#include "fpt/paper_streaming_cmux.hpp"

namespace fpt::paper_streaming {
using InputTlwe = std::array<std::uint32_t,587>;
using OutputTlwe = std::array<std::uint32_t,1025>;

inline unsigned modulus_switch(std::uint32_t value)
{
    // Round on the Torus before narrowing to the 2N rotation domain.
    return ((std::uint64_t{value}+(1u<<21))>>22)&1023;
}

inline OutputTlwe sample_extract(const Ciphertext &state)
{
    OutputTlwe result;
    for(unsigned c=0;c<2;++c) {
        result[c*512]=state[c][0];
        for(unsigned j=1;j<512;++j)result[c*512+j]=0u-state[c][512-j];
    }
    result[1024]=state[2][0];
    return result;
}

// provider(step) supplies an ordinary TGGSW encryption's six spectral rows.
// Its returned key must use schedule.mac.key and natural spectrum ordering.
template<class KeyProvider>
inline OutputTlwe bootstrap(const InputTlwe &input,
                            const std::array<std::uint32_t,512> &lookup,
                            KeyProvider &&provider,
                            const ExternalSchedule &schedule,
                            QuantizationStats &stats)
{
    Ciphertext state{};
    state[2]=lookup;
    state=rotate_ciphertext(state,(1024-modulus_switch(input[586]))%1024);
    for(unsigned step=0;step<586;++step) {
        const auto exponent=modulus_switch(input[step]);
        if(exponent)state=cmux(state,exponent,provider(step),schedule,stats);
    }
    return sample_extract(state);
}
} // namespace fpt::paper_streaming

#include "fpt/paper_streaming_cmux.hpp"
#include <cassert>
#include <random>

int main()
{
    using namespace fpt::paper_streaming;
    Ciphertext state;std::mt19937 random(25);
    for(auto &component:state)for(auto &v:component)v=random();
    for(unsigned exponent:{0u,1u,511u,512u,513u,1023u}) {
        const auto rotated=rotate_ciphertext(state,exponent);
        assert(rotate_ciphertext(rotated,1024-exponent)==state);
        const auto digits=decompose_difference(state,exponent,{15,14});
        for(unsigned c=0;c<3;++c)for(unsigned j=0;j<512;++j) {
            const auto high=digits[2*c][j]/16384;
            const auto low=digits[2*c+1][j]/16384;
            assert(high>=-128 && high<=127 && low>=-128 && low<=127);
            const auto reconstructed=static_cast<std::uint32_t>(high*16777216+low*65536);
            const std::uint32_t residual=rotated[c][j]-state[c][j]-reconstructed;
            assert(residual<=32767 || residual>=0xffff8000u);
        }
    }
    KeyRows zero{};
    const TransformSchedule fft{{18,14},28,{}};
    const ExternalSchedule schedule{fft,fft,{{18,14},{7,19},{26,33}}};
    fpt::QuantizationStats stats;
    assert(cmux(state,0,zero,schedule,stats)==state);
    assert(cmux(state,513,zero,schedule,stats)==state);
    assert(stats.overflows==0);
}

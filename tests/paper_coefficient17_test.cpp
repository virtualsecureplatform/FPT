#include "fpt/paper_coefficient17.hpp"
#include "fpt/paper_streaming_cmux.hpp"
#include <cassert>
#include <random>

int main()
{
    namespace pc=fpt::paper_coefficient17;
    namespace ps=fpt::paper_streaming;
    std::mt19937 random(229);
    for(unsigned trial=0;trial<100000;++trial) {
        const auto a=random()&pc::mask,b=random()&pc::mask;
        assert(pc::encode_exact(pc::decode(a))==a);
        assert(pc::decode(pc::add(a,b))==std::uint32_t(pc::decode(a)+pc::decode(b)));
        assert(pc::decode(pc::negate(a))==0u-pc::decode(a));
        const std::uint32_t word=random();
        const auto rounded=pc::decode(pc::quantize_lookup(word));
        const auto error=static_cast<std::int32_t>(rounded-word);
        assert(error>=-16384 && error<=16384);
    }
    assert(pc::quantize_lookup(0xffffc000u)==0);
    assert(pc::quantize_lookup(0xffff4000u)==0x1fffeu);
    bool rejected=false;
    try{pc::encode_exact(1);}catch(const std::invalid_argument&){rejected=true;}
    assert(rejected);
    ps::Ciphertext state;
    for(auto &component:state)for(auto &word:component)word=pc::decode(random()&pc::mask);
    for(unsigned exponent:{0u,1u,127u,511u,512u,513u,777u,1023u}) {
        const auto rotated=ps::rotate_ciphertext(state,exponent);
        const auto digits=ps::decompose_difference(state,exponent,{9,23});
        for(unsigned c=0;c<3;++c)for(unsigned j=0;j<512;++j)for(unsigned level=0;level<2;++level) {
            const auto digit=pc::decompose_difference(pc::encode_exact(rotated[c][j]),pc::encode_exact(state[c][j]),level);
            assert(digits[c*2+level][j]==std::int64_t(digit)*(1LL<<23));
        }
    }
}

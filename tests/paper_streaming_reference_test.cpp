#include "fpt/paper_reference.hpp"
#include <cassert>
#include <random>

int main()
{
    using namespace fpt::paper_reference;
    std::mt19937 random(109);
    for(unsigned trial=0;trial<4;++trial) {
        Polynomial a,b,schoolbook{};
        for(auto &v:a)v=static_cast<int>(random()%255)-127;
        for(auto &v:b)v=(static_cast<int>(random()%255)-127)/256.L;
        for(unsigned j=0;j<512;++j)for(unsigned k=0;k<512;++k)
            schoolbook[(j+k)%512]+=(j+k>=512?-1:1)*a[j]*b[k];
        auto fa=forward(a),fb=forward(b);
        for(unsigned j=0;j<256;++j)fa[j]*=fb[j];
        const auto convolution=inverse(fa);
        for(unsigned j=0;j<512;++j)assert(std::abs(convolution[j]-schoolbook[j])<1e-9L);
    }
    Ciphertext state{};
    for(auto &component:state)for(auto &v:component)v=random();
    for(unsigned exponent:{0u,1u,511u,512u,513u,1023u})
        assert(rotate(rotate(state,exponent),(1024-exponent)%1024)==state);
    KeyRows zero{};
    assert(cmux(state,713,zero)==state);
}

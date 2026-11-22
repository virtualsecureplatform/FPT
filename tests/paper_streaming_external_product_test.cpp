#include "fpt/paper_streaming_external_product.hpp"
#include <cassert>
#include <random>

int main()
{
    using namespace fpt::paper_streaming;
    constexpr int fraction=14;
    const TransformSchedule fft{{18,fraction},28,{}};
    const ExternalSchedule schedule{fft,fft,{{18,fraction},{18,fraction},{30,28}}};
    std::array<Polynomial,6> digits;
    std::array<std::array<Polynomial,3>,6> coefficients;
    std::mt19937 random(17);
    for(auto &row:digits)for(auto &v:row)v=(static_cast<int>(random()%3)-1)*(1<<fraction);
    KeyRows key;
    fpt::QuantizationStats stats;
    for(unsigned r=0;r<6;++r)for(unsigned c=0;c<3;++c) {
        auto &p=coefficients[r][c];p.fill(0);
        // Sparse independently generated key polynomials, including wraparound.
        for(unsigned t=0;t<5;++t)p[random()%512]+=(static_cast<int>(random()%3)-1)*(1<<fraction);
        key[r][c]=negacyclic_forward(p,fft,stats);
    }
    const auto output=external_product(digits,key,schedule,stats);
    for(unsigned c=0;c<3;++c)for(unsigned i=0;i<512;++i) {
        std::int64_t reference=0;
        for(unsigned r=0;r<6;++r)for(unsigned j=0;j<512;++j) {
            const unsigned k=(i+512-j)%512;
            reference+=(j+k>=512?-1:1)*(digits[r][j]>>fraction)*(coefficients[r][c][k]>>fraction);
        }
        assert(std::abs(output[c][i]-reference*(1<<fraction))<32);
    }
    assert(stats.overflows==0);
}

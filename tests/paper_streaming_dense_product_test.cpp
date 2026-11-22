#include "fpt/paper_streaming_external_product.hpp"
#include "fpt/paper_reference.hpp"
#include <cassert>
#include <iostream>
#include <random>

int main()
{
    namespace ps=fpt::paper_streaming;
    namespace pr=fpt::paper_reference;
    ps::TransformSchedule forward{{9,23},26,{}};
    forward.stage_data=std::array<fpt::FixedFormat,4>{{{11,21},{13,19},{15,17},{17,15}}};
    ps::TransformSchedule inverse{{19,13},26,{}};
    inverse.stage_data=std::array<fpt::FixedFormat,4>{{{20,12},{21,11},{22,10},{23,9}}};
    const ps::ExternalSchedule schedule{forward,inverse,{forward.output_format(),{7,21},{26,22}}};
    std::mt19937 random(149);
    long double maximum_error=0;
    for(unsigned trial=0;trial<4;++trial) {
        std::array<ps::Polynomial,6> digits;
        ps::KeyRows key;
        std::array<pr::Spectrum,3> sums{};
        fpt::QuantizationStats stats;
        for(unsigned row=0;row<6;++row) {
            pr::Polynomial digit_polynomial;
            for(unsigned j=0;j<512;++j) {
                const int digit=static_cast<int>(random()%256)-128;
                digit_polynomial[j]=digit;digits[row][j]=std::int64_t(digit)*(1LL<<23);
            }
            const auto digit_spectrum=pr::forward(digit_polynomial);
            for(unsigned c=0;c<3;++c) {
                pr::Polynomial polynomial;
                for(auto &v:polynomial)v=(static_cast<int>(random()%1024)-512)/1024.L;
                const auto spectrum=pr::forward(polynomial);
                for(unsigned j=0;j<256;++j) {
                    key[row][c][j]={static_cast<std::int64_t>(std::round(std::ldexp(spectrum[j].real(),21))),
                                    static_cast<std::int64_t>(std::round(std::ldexp(spectrum[j].imag(),21)))};
                    const pr::Complex quantized{std::ldexp(static_cast<long double>(key[row][c][j].real),-21),
                                                std::ldexp(static_cast<long double>(key[row][c][j].imag),-21)};
                    sums[c][j]+=digit_spectrum[j]*quantized;
                }
            }
        }
        const auto output=ps::external_product(digits,key,schedule,stats,true);
        assert(stats.overflows==0);
        for(unsigned c=0;c<3;++c) {
            const auto expected=pr::inverse(sums[c]);
            for(unsigned j=0;j<512;++j) {
                const auto error=std::abs(std::ldexp(static_cast<long double>(output[c][j]),-17)-expected[j]);
                maximum_error=std::max(maximum_error,error);
                assert(error<0.0002L);
            }
        }
    }
    std::cout<<"maximum dense external-product coefficient error="<<maximum_error<<'\n';
}

#include "fpt/paper_streaming_fft.hpp"
#include <cassert>
#include <complex>
#include <random>

int main()
{
    using namespace fpt::paper_streaming;
    std::mt19937 random(42);
    {
        Polynomial a{},b{};
        for(auto &v:a)v=(static_cast<int>(random()%3)-1)*16384;
        b[1]=16384;
        const TransformSchedule schedule{{18,14},28,{}};
        fpt::QuantizationStats stats;
        const auto fa=negacyclic_forward(a,schedule,stats);
        const auto fb=negacyclic_forward(b,schedule,stats);
        Frame product;
        for(unsigned j=0;j<256;++j) {
            const auto p=gauss_product(fa[j].real,fa[j].imag,fb[j].real,fb[j].imag);
            product[j]={requantize(p.real,14,schedule.data,stats),requantize(p.imag,14,schedule.data,stats)};
        }
        const auto recovered=negacyclic_inverse(fa,schedule,0,stats);
        const auto retained=negacyclic_inverse(fa,schedule,0,stats,true);
        for(unsigned j=0;j<512;++j)
            assert(std::abs(retained[j]-a[j]*256)<=512);
        assert(torus_word(1,14)==(1u<<18));
        assert(torus_word(-1,14)==0u-(1u<<18));
        assert(torus_word(3,33)==2);
        assert(torus_word(-3,33)==0xfffffffeu);
        const auto multiplied=negacyclic_inverse(product,schedule,0,stats);
        for(unsigned j=0;j<512;++j) {
            assert(std::abs(recovered[j]-a[j])<=2);
            assert(std::abs(multiplied[j]-(j?a[j-1]:-a[511]))<=3);
        }
        assert(stats.overflows==0);
    }
    for (bool inverse : {false,true}) {
        Frame input;
        for (auto &v:input) v={static_cast<int>(random()%256)-128,static_cast<int>(random()%256)-128};
        for (auto scaling: {std::array<unsigned,4>{0,0,0,0},std::array<unsigned,4>{2,2,2,2}}) {
            fpt::QuantizationStats stats;
            const auto output=transform(input,{{29,0},23,scaling},inverse,stats);
            assert(stats.overflows==0);
            for (unsigned k=0;k<256;++k) {
                std::complex<long double> reference{};
                for (unsigned j=0;j<256;++j) {
                    const auto angle=(inverse?2:-2)*std::numbers::pi_v<long double>*j*k/256;
                    reference+=std::complex<long double>(input[j].real,input[j].imag)*std::polar(1.L,angle);
                }
                unsigned total=0;for(auto s:scaling)total+=s;
                reference/=std::ldexp(1.L,total);
                assert(std::abs(output[k].real-reference.real())<16);
                assert(std::abs(output[k].imag-reference.imag())<16);
            }
        }
    }
    // Legal gadget digits can exceed Q15's range in the final forward stage.
    // Reserve two more integer bits without increasing the stored word width.
    {
        Polynomial digits;
        digits.fill(127LL<<17);
        fpt::QuantizationStats narrow_stats;
        negacyclic_forward(digits,{{15,17},26,{}},narrow_stats);
        assert(narrow_stats.overflows>0);
        digits.fill(127LL<<15);
        fpt::QuantizationStats headroom_stats;
        negacyclic_forward(digits,{{17,15},26,{}},headroom_stats);
        assert(headroom_stats.overflows==0);
    }
    {
        TransformSchedule staged{{19,13},26,{}};
        staged.stage_data=std::array<fpt::FixedFormat,4>{{{20,12},{21,11},{22,10},{23,9}}};
        Frame input;
        for(auto &v:input)v={static_cast<int>(random()%65536)-32768,
                             static_cast<int>(random()%65536)-32768};
        for(bool inverse:{false,true}) {
            fpt::QuantizationStats stats;
            const auto result=transform(input,staged,inverse,stats);
            assert(stats.overflows==0);
            for(unsigned k=0;k<256;++k) {
                std::complex<long double> expected{};
                for(unsigned j=0;j<256;++j)
                    expected+=std::complex<long double>(input[j].real,input[j].imag)*
                        std::polar(1.L,(inverse?2:-2)*std::numbers::pi_v<long double>*j*k/256);
                expected/=16; // Four fractional bits discarded, no mathematical scaling.
                assert(std::abs(result[k].real-expected.real())<16);
                assert(std::abs(result[k].imag-expected.imag())<16);
            }
        }
    }
}

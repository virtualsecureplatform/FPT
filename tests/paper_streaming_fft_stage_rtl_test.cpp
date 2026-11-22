#include "VPaperStreamingFFTStage.h"
#include <array>
#include <cmath>
#include <cstdint>
#include <deque>
#include <iostream>
#include <numbers>
#include <random>
#include <stdexcept>

#ifndef TEST_LANES
#define TEST_LANES 128
#define TEST_STAGE 0
#define TEST_INVERSE 0
#endif
#ifndef TEST_LEG_GUARD
#define TEST_LEG_GUARD -1
#endif
constexpr unsigned lanes=TEST_LANES,stage=TEST_STAGE,inverse=TEST_INVERSE;
struct Expected {bool valid;std::array<std::int64_t,lanes> real,imag;};
static std::int64_t round_even(std::int64_t value,unsigned shift)
{
    const auto divisor=1LL<<shift;
    auto quotient=value/divisor,remainder=value%divisor;
    const auto magnitude=remainder<0?-remainder:remainder;
    if(magnitude>divisor/2 || (magnitude==divisor/2 && quotient%2!=0))quotient+=value<0?-1:1;
    return quotient;
}
int main()
{
    VPaperStreamingFFTStage dut;
    std::mt19937_64 random(199);
    std::deque<Expected> pipe;
    unsigned phase=0,checked=0;
    for(unsigned cycle=0;cycle<1200;++cycle) {
        dut.clk=0;dut.reset=cycle%193==0;
        dut.input_valid=(cycle<1180 || phase!=0) && (phase!=0 || random()%4!=0);
        Expected expected{bool(dut.input_valid),{}, {}};
        const unsigned quarter=1u<<(2*stage),length=4*quarter;
        for(unsigned butterfly=0;butterfly<lanes/4;++butterfly) {
            std::array<std::int64_t,4> real,imag;
            for(unsigned leg=0;leg<4;++leg) {
                const unsigned position=stage==0 || stage==3?4*butterfly+leg:
                    (butterfly/quarter)*length+butterfly%quarter+leg*quarter;
                const auto ar=std::int64_t(random()%(1ULL<<26))-(1LL<<25);
                const auto ai=std::int64_t(random()%(1ULL<<26))-(1LL<<25);
                dut.input_real[position]=ar;dut.input_imag[position]=ai;
                const unsigned j=stage==3?phase*(lanes/4)+butterfly:butterfly%quarter;
                const long double angle=(inverse?2:-2)*std::numbers::pi_v<long double>*leg*j/length;
                const auto c=std::int64_t(std::nearbyint(std::ldexp(std::cos(angle),26)));
                const auto s=std::int64_t(std::nearbyint(std::ldexp(std::sin(angle),26)));
                real[leg]=ar*c-ai*s;imag[leg]=ar*s+ai*c;
                if constexpr(TEST_LEG_GUARD>=0) {
                    real[leg]=round_even(real[leg],26-TEST_LEG_GUARD);
                    imag[leg]=round_even(imag[leg],26-TEST_LEG_GUARD);
                }
            }
            for(unsigned k=0;k<4;++k) {
                constexpr int wr[4]={1,0,-1,0},wi[4]={0,1,0,-1};
                std::int64_t r=0,i=0;
                for(unsigned leg=0;leg<4;++leg) {
                    const unsigned index=(inverse?k*leg:4-(k*leg)%4)%4;
                    r+=real[leg]*wr[index]-imag[leg]*wi[index];
                    i+=real[leg]*wi[index]+imag[leg]*wr[index];
                }
                const unsigned position=stage==0 || stage==3?4*butterfly+k:
                    (butterfly/quarter)*length+butterfly%quarter+k*quarter;
                const unsigned shift=(TEST_LEG_GUARD<0?26:TEST_LEG_GUARD)+(inverse?(stage==0?0:1):2);
                expected.real[position]=round_even(r,shift);expected.imag[position]=round_even(i,shift);
            }
        }
        dut.eval();dut.clk=1;dut.eval();
        if(dut.reset){pipe.clear();phase=0;}
        else {
            pipe.push_back(expected);
            if(dut.input_valid)phase=(phase+1)%(256/lanes);
        }
        Expected output{false,{}, {}};
        if(pipe.size()>=5){output=pipe.front();pipe.pop_front();}
        if(bool(dut.output_valid)!=output.valid || dut.fault)throw std::runtime_error("FFT stage valid/phase/reset mismatch");
        if(output.valid)for(unsigned j=0;j<lanes;++j) {
            if(std::uint32_t(dut.output_real[j])!=std::uint32_t(output.real[j]) ||
               std::uint32_t(dut.output_imag[j])!=std::uint32_t(output.imag[j]))
                throw std::runtime_error("FFT stage arithmetic mismatch");
            ++checked;
        }
    }
    dut.final();std::cout<<"PASS FFT stage="<<stage<<" inverse="<<inverse<<" complex_values="<<checked<<'\n';
}

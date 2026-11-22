#include "VPaperStreamingCoefficientFrontend17.h"
#include <array>
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>
#ifndef TEST_DIGIT_REVERSED
#define TEST_DIGIT_REVERSED 0
#endif
struct Expected {
    bool valid;unsigned context,row,half;
    std::array<std::uint8_t,128> low,high;
};
int main()
{
    VPaperStreamingCoefficientFrontend17 dut;
    std::mt19937 random(251);
    std::deque<Expected> pipe;
    unsigned checked=0;
    constexpr unsigned edges[]={0,1,127,128,255,256,511,512,513,1023};
    for(unsigned cycle=0;cycle<4000;++cycle) {
        dut.clk=0;dut.reset=cycle%193==0;dut.input_valid=cycle<3980 && random()%4!=0;
        dut.input_context=random()%12;dut.input_row=random()%6;dut.input_half=random()%2;
        dut.input_exponent=cycle%3==0?edges[(cycle/3)%10]:random()%1024;
        std::array<std::uint32_t,512> words;
        for(unsigned point=0;point<512;++point) {
            dut.input_code[point]=random()&0x1ffff;
            words[point]=dut.input_code[point]<<15;
        }
        Expected expected{bool(dut.input_valid),dut.input_context,dut.input_row,dut.input_half,{}, {}};
        auto digit=[&](unsigned point) {
            const unsigned source=(point+1024-dut.input_exponent)%1024;
            const std::uint32_t rotated=source>=512?0u-words[source%512]:words[source];
            const std::uint32_t biased=rotated-words[point]+0x80808000u;
            return std::uint8_t(int((biased>>((dut.input_row&1)?16:24))&255)-128);
        };
        for(unsigned lane=0;lane<128;++lane) {
            unsigned point=dut.input_half*128+lane;
            if constexpr(TEST_DIGIT_REVERSED)point=(point%4)*64+((point/4)%4)*16+((point/16)%4)*4+point/64;
            expected.low[lane]=digit(point);expected.high[lane]=digit(point+256);
        }
        dut.eval();dut.clk=1;dut.eval();
        if(dut.reset)pipe.clear();else pipe.push_back(expected);
        Expected result{false,0,0,0,{}, {}};
        if(pipe.size()>=10){result=pipe.front();pipe.pop_front();}
        if(bool(dut.output_valid)!=result.valid || dut.fault)throw std::runtime_error("coefficient front-end latency/reset fault");
        if(result.valid) {
            if(dut.output_context!=result.context || dut.output_row!=result.row || dut.output_half!=result.half)
                throw std::runtime_error("coefficient front-end metadata mismatch");
            for(unsigned lane=0;lane<128;++lane) {
                if(std::uint8_t(dut.digit_low[lane])!=result.low[lane] || std::uint8_t(dut.digit_high[lane])!=result.high[lane])
                    throw std::runtime_error("coefficient front end differs from Torus32 rotation/decomposition");
                checked+=2;
            }
        }
    }
    dut.final();std::cout<<"PASS coefficient front end digits="<<checked<<" latency=10\n";
}

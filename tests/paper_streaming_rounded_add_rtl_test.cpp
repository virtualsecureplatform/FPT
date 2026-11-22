#include "VPaperStreamingRoundedAdd.h"
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>
#ifndef TEST_SUBTRACT
#define TEST_SUBTRACT 0
#endif
int main()
{
    VPaperStreamingRoundedAdd dut;
    std::mt19937_64 random(227);
    unsigned checked=0,overflows=0;
    constexpr std::int64_t divisor=1LL<<28;
    for(unsigned cycle=0;cycle<20000;++cycle) {
        auto a=std::int64_t(random()&((1ULL<<63)-1))-(1LL<<62);
        auto b=std::int64_t(random()&((1ULL<<63)-1))-(1LL<<62);
        if(cycle%3==0) {
            constexpr std::int64_t bases[]={-2147483649LL,-2147483648LL,-3,-2,-1,0,1,2,3,2147483647LL,2147483648LL};
            const auto target=bases[(cycle/3)%11]*divisor+divisor/2+int(cycle%5)-2;
            a=std::int64_t(random()%(1ULL<<60))-(1LL<<59);
            b=TEST_SUBTRACT?a-target:target-a;
        }
        const __int128 raw=TEST_SUBTRACT?__int128(a)-b:__int128(a)+b;
        auto expected=raw/divisor,remainder=raw%divisor;
        const auto magnitude=remainder<0?-remainder:remainder;
        if(magnitude>divisor/2 || (magnitude==divisor/2 && expected%2!=0))expected+=raw<0?-1:1;
        const bool overflow=expected<-(1LL<<31) || expected>=(1LL<<31);
        dut.clk=0;dut.reset=cycle%193==0;dut.input_valid=random()%4!=0;
        dut.a=a;dut.b=b;dut.eval();dut.clk=1;dut.eval();
        if(bool(dut.output_valid)!=bool(dut.input_valid && !dut.reset))throw std::runtime_error("rounded-add valid/reset mismatch");
        if(dut.output_valid) {
            if(std::uint32_t(dut.result)!=std::uint32_t(expected) || bool(dut.overflow)!=overflow)
                throw std::runtime_error("rounded-add RNE/overflow mismatch");
            ++checked;overflows+=overflow;
        }
    }
    dut.final();std::cout<<"PASS rounded add subtract="<<TEST_SUBTRACT<<" comparisons="<<checked<<" overflows="<<overflows<<'\n';
}

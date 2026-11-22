#include "VPaperStreamingRequantize.h"
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>
#ifndef TEST_SHIFT
#define TEST_SHIFT 20
#endif
#ifndef TEST_INPUT_WIDTH
#define TEST_INPUT_WIDTH 57
#endif
#ifndef TEST_OUTPUT_WIDTH
#define TEST_OUTPUT_WIDTH 32
#endif

// Division/remainder oracle is independent of the RTL bit-slice implementation.
int main()
{
    VPaperStreamingRequantize dut;
    std::mt19937_64 random(79);
    unsigned checked=0, overflows=0;
    constexpr std::int64_t divisor=1LL<<TEST_SHIFT;
    constexpr std::int64_t limit=1LL<<(TEST_OUTPUT_WIDTH-1);
    constexpr std::int64_t input_limit=1LL<<(TEST_INPUT_WIDTH-1);
    constexpr std::uint64_t output_mask=(1ULL<<TEST_OUTPUT_WIDTH)-1;
    for(unsigned cycle=0;cycle<20000;++cycle) {
        auto raw=static_cast<std::int64_t>(random()&((1ULL<<TEST_INPUT_WIDTH)-1));
        if(raw&input_limit)raw-=1LL<<TEST_INPUT_WIDTH;
        // Both signs, parity at exact ties, and values at the destination limit.
        if(cycle%3==0 && TEST_SHIFT>0) {
            const std::int64_t bases[]={-limit-1,-limit,-3,-2,-1,0,1,2,3,limit-1,limit};
            raw=bases[(cycle/3)%11]*divisor+divisor/2+static_cast<int>(cycle%5)-2;
            if(raw < -input_limit)raw=-input_limit;
            if(raw >= input_limit)raw=input_limit-1;
        }
        auto expected=raw/divisor;
        auto remainder=raw%divisor;
        const auto magnitude=remainder<0?-remainder:remainder;
        if(TEST_SHIFT>0 && (magnitude>divisor/2 || (magnitude==divisor/2 && expected%2!=0)))
            expected+=raw<0?-1:1;
        const bool overflow=expected < -limit || expected >= limit;
        dut.clk=0;dut.reset=cycle%193==0;dut.input_valid=random()%4!=0;
        dut.value=raw;dut.eval();dut.clk=1;dut.eval();
        if(bool(dut.output_valid)!=bool(dut.input_valid && !dut.reset))
            throw std::runtime_error("requantize valid/reset mismatch");
        if(dut.output_valid) {
            if((std::uint64_t(dut.result)&output_mask)!=(std::uint64_t(expected)&output_mask) ||
               bool(dut.overflow)!=overflow) {
                std::cerr<<"cycle="<<cycle<<" raw="<<raw<<" expected="<<expected
                         <<" actual="<<std::uint64_t(dut.result)
                         <<" overflow="<<overflow<<" actual_overflow="<<bool(dut.overflow)<<'\n';
                throw std::runtime_error("requantize rounding/overflow mismatch");
            }
            ++checked;overflows+=overflow;
        }
    }
    dut.final();
    std::cout<<"PASS requantize RTL comparisons="<<checked<<" observed_overflows="<<overflows<<'\n';
}

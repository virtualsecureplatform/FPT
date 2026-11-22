#ifdef TEST_SPLIT
#include "VPaperStreamingComplexProductSplit.h"
using ProductDut=VPaperStreamingComplexProductSplit;
#else
#include "VPaperStreamingComplexProduct.h"
using ProductDut=VPaperStreamingComplexProduct;
#endif
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>
#ifndef TEST_A_WIDTH
#define TEST_A_WIDTH 29
#define TEST_B_WIDTH 26
#endif

struct Expected {bool valid;std::int64_t real,imag;};
static std::int64_t signed_value(std::uint64_t value,unsigned width)
{
    const auto mask=(std::uint64_t{1}<<width)-1;
    value&=mask;
    return value&(std::uint64_t{1}<<(width-1)) ?
        static_cast<std::int64_t>(value)-static_cast<std::int64_t>(std::uint64_t{1}<<width):value;
}
int main()
{
    ProductDut dut;
    std::mt19937_64 random(67);
    std::deque<Expected> pending;
    unsigned checked=0;
    for(unsigned cycle=0;cycle<10004;++cycle) {
        dut.clk=0;dut.reset=cycle%193==0;
        dut.input_valid=cycle<10000 && random()%4!=0;
        auto a=[&] {return signed_value(random(),TEST_A_WIDTH);};
        auto b=[&] {return signed_value(random(),TEST_B_WIDTH);};
        auto ar=a(),ai=a(),br=b(),bi=b();
        if(cycle%97==0){ar=-(1LL<<(TEST_A_WIDTH-1));ai=(1LL<<(TEST_A_WIDTH-1))-1;br=-(1LL<<(TEST_B_WIDTH-1));bi=(1LL<<(TEST_B_WIDTH-1))-1;}
        dut.ar=ar;dut.ai=ai;dut.br=br;dut.bi=bi;
        dut.eval();dut.clk=1;dut.eval();
        if(dut.reset)pending.clear();
        else pending.push_back({bool(dut.input_valid),ar*br-ai*bi,ar*bi+ai*br});
        Expected expected{false,0,0};
        if(pending.size()>=3){expected=pending.front();pending.pop_front();}
        if(bool(dut.output_valid)!=expected.valid)throw std::runtime_error("valid latency/reset mismatch");
        if(expected.valid) {
            if(signed_value(dut.pr,TEST_A_WIDTH+TEST_B_WIDTH+2)!=expected.real || signed_value(dut.pi,TEST_A_WIDTH+TEST_B_WIDTH+2)!=expected.imag)
                throw std::runtime_error("complex multiplication mismatch");
            ++checked;
        }
    }
    dut.final();
    std::cout<<"PASS product RTL comparisons="<<checked<<" cycles=10004 resets=52\n";
}

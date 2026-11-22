#include "VPaperStreamingComplexProductRounded.h"
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>
#ifndef TEST_B_WIDTH
#define TEST_B_WIDTH 28
#define TEST_OUTPUT_WIDTH 48
#define TEST_SHIFT 14
#endif
struct Expected {bool valid;__int128 real,imag;};
struct Rounded {std::uint64_t word;bool overflow;};
static Rounded round_even(__int128 raw)
{
    constexpr std::int64_t divisor=1LL<<TEST_SHIFT,limit=1LL<<(TEST_OUTPUT_WIDTH-1);
    auto quotient=raw/divisor,remainder=raw%divisor;
    const auto magnitude=remainder<0?-remainder:remainder;
    if(magnitude>divisor/2 || (magnitude==divisor/2 && quotient%2!=0))quotient+=raw<0?-1:1;
    return {std::uint64_t(quotient)&((1ULL<<TEST_OUTPUT_WIDTH)-1),quotient < -__int128(limit) || quotient>=limit};
}
int main()
{
    VPaperStreamingComplexProductRounded dut;
    std::mt19937_64 random(233);
    std::deque<Expected> pipe;
    unsigned checked=0,overflows=0;
    for(unsigned cycle=0;cycle<10004;++cycle) {
        dut.clk=0;dut.reset=cycle%193==0;dut.input_valid=cycle<10000 && random()%4!=0;
        auto ar=std::int64_t(static_cast<std::int32_t>(random())),ai=std::int64_t(static_cast<std::int32_t>(random()));
        auto br=std::int64_t(random()%(1ULL<<TEST_B_WIDTH))-(1LL<<(TEST_B_WIDTH-1));
        auto bi=std::int64_t(random()%(1ULL<<TEST_B_WIDTH))-(1LL<<(TEST_B_WIDTH-1));
        if(cycle%97==0){ar=ai=-(1LL<<31);br=bi=-(1LL<<(TEST_B_WIDTH-1));}
        dut.ar=ar;dut.ai=ai;dut.br=br;dut.bi=bi;dut.eval();dut.clk=1;dut.eval();
        if(dut.reset)pipe.clear();
        else pipe.push_back({bool(dut.input_valid),__int128(ar)*br-__int128(ai)*bi,__int128(ar)*bi+__int128(ai)*br});
        Expected expected{false,0,0};
        if(pipe.size()>=3){expected=pipe.front();pipe.pop_front();}
        if(bool(dut.output_valid)!=expected.valid)throw std::runtime_error("rounded product valid/reset mismatch");
        if(expected.valid) {
            const auto real=round_even(expected.real),imag=round_even(expected.imag);
            if(std::uint64_t(dut.pr)!=real.word || std::uint64_t(dut.pi)!=imag.word ||
               bool(dut.overflow_real)!=real.overflow || bool(dut.overflow_imag)!=imag.overflow)
                throw std::runtime_error("rounded complex product mismatch");
            ++checked;overflows+=real.overflow || imag.overflow;
        }
    }
    dut.final();std::cout<<"PASS rounded product width="<<TEST_OUTPUT_WIDTH<<" comparisons="<<checked<<" overflows="<<overflows<<'\n';
}

#include "VPaperStreamingButterfly4.h"
#include <array>
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>
#ifndef TEST_INVERSE
#define TEST_INVERSE 0
#endif

int main()
{
    VPaperStreamingButterfly4 dut;
    std::mt19937_64 random(131);
    unsigned checked=0;
    for(unsigned cycle=0;cycle<10000;++cycle) {
        std::array<std::int64_t,4> r,i;
        dut.clk=0;dut.reset=cycle%193==0;dut.input_valid=random()%4!=0;
        for(unsigned j=0;j<4;++j) {
            r[j]=static_cast<std::int32_t>(random());
            i[j]=static_cast<std::int32_t>(random());
            if(cycle%97==0){r[j]=-(1LL<<31);i[j]=(1LL<<31)-1;}
            dut.xr[j]=r[j];dut.xi[j]=i[j];
        }
        dut.eval();dut.clk=1;dut.eval();
        if(bool(dut.output_valid)!=bool(dut.input_valid && !dut.reset))
            throw std::runtime_error("butterfly valid/reset mismatch");
        if(!dut.output_valid)continue;
        // Direct Gaussian-integer DFT matrix, independent of pair-sum RTL.
        constexpr int wr[4]={1,0,-1,0};
        constexpr int wi[4]={0,1,0,-1};
        for(unsigned k=0;k<4;++k) {
            std::int64_t real=0,imag=0;
            for(unsigned j=0;j<4;++j) {
                const unsigned index=(TEST_INVERSE?k*j:4-k*j%4)%4;
                real+=r[j]*wr[index]-i[j]*wi[index];
                imag+=r[j]*wi[index]+i[j]*wr[index];
            }
            auto signed34=[](std::uint64_t v) {v&=(1ULL<<34)-1;return std::int64_t(v)-((v&(1ULL<<33))?(1LL<<34):0);};
            if(signed34(dut.yr[k])!=real || signed34(dut.yi[k])!=imag)
                throw std::runtime_error("butterfly DFT mismatch");
            ++checked;
        }
    }
    dut.final();std::cout<<"PASS butterfly RTL complex comparisons="<<checked<<'\n';
}

#include "VPaperStreamingDecompose17.h"
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>
struct Expected {bool valid;std::uint8_t digit;};
int main()
{
    VPaperStreamingDecompose17 dut;
    std::mt19937 random(241);
    std::deque<Expected> pipe;
    unsigned checked=0;
    for(unsigned cycle=0;cycle<20006;++cycle) {
        dut.clk=0;dut.reset=cycle%193==0;dut.input_valid=cycle<20000 && random()%4!=0;
        dut.rotated=random()&0x1ffff;dut.current=random()&0x1ffff;
        dut.negate=random()&1;dut.level=random()&1;
        if(cycle%97==0){dut.rotated=0;dut.current=0;dut.negate=1;}
        std::uint32_t rotated=std::uint32_t(dut.rotated)<<15;
        if(dut.negate)rotated=0u-rotated;
        const std::uint32_t biased=rotated-(std::uint32_t(dut.current)<<15)+0x80808000u;
        const auto digit=std::uint8_t(int((biased>>(dut.level?16:24))&255)-128);
        dut.eval();dut.clk=1;dut.eval();
        if(dut.reset)pipe.clear();else pipe.push_back({bool(dut.input_valid),digit});
        Expected expected{false,0};
        if(pipe.size()>=5){expected=pipe.front();pipe.pop_front();}
        if(bool(dut.output_valid)!=expected.valid)throw std::runtime_error("decomposition latency/reset mismatch");
        if(expected.valid) {
            if(std::uint8_t(dut.digit)!=expected.digit)throw std::runtime_error("17-bit sliced decomposition differs from Torus32 reference");
            ++checked;
        }
    }
    dut.final();std::cout<<"PASS sliced decomposition coefficients="<<checked<<'\n';
}

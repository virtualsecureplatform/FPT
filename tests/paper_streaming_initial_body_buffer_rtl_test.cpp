#include "VPaperStreamingInitialBodyBuffer17.h"
#include <array>
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>
int main()
{
    VPaperStreamingInitialBodyBuffer17 dut;
    std::array<std::array<unsigned,512>,12> codes;
    std::mt19937 random(283);
    unsigned checked=0;
    auto tick=[&](bool check_fault=true) {
        dut.clk=0;dut.eval();
        if(!dut.reset && dut.read_valid && dut.read_ready) {
            for(unsigned lane=0;lane<64;++lane) {
                const unsigned native=dut.read_chunk*64+lane,point=native/4+(native%4)*64;
                if(dut.read_low[lane]!=codes[dut.read_context][point] || dut.read_high[lane]!=codes[dut.read_context][point+256])
                    throw std::runtime_error("initial-body buffer native chunk mismatch");
                checked+=2;
            }
        }
        dut.clk=1;dut.eval();
        if(!dut.reset && check_fault && dut.fault)throw std::runtime_error("initial-body buffer fault");
    };
    auto reset=[&] {
        dut.reset=1;dut.input_valid=0;dut.read_valid=0;tick();dut.reset=0;
    };
    reset();
    for(unsigned batch=0;batch<3;++batch) {
        for(auto &context:codes)for(auto &code:context)code=random()&0x1ffff;
        for(unsigned index=0;index<6144;) {
            dut.input_valid=random()%4!=0;dut.input_context=index/512;dut.input_point=index%512;
            dut.input_code=codes[index/512][index%512];dut.clk=0;dut.eval();
            const bool take=dut.input_valid && dut.input_ready;tick();index+=take;
        }
        dut.input_valid=0;
        if(!dut.full || dut.input_ready)throw std::runtime_error("initial-body batch completion mismatch");
        for(unsigned index=0;index<48;) {
            dut.read_valid=random()%3!=0;dut.read_context=index/4;dut.read_chunk=index%4;
            dut.clk=0;dut.eval();if(!dut.read_ready)throw std::runtime_error("prepared initial body is unavailable");
            const bool take=dut.read_valid;tick();index+=take;
        }
        dut.read_valid=0;
        if(dut.full || !dut.drained || !dut.input_ready)throw std::runtime_error("initial-body batch did not drain");
    }
    dut.input_valid=1;dut.input_context=0;dut.input_point=0;dut.input_code=123;tick();reset();
    if(dut.full)throw std::runtime_error("initial-body validity survived reset");
    dut.input_valid=1;dut.input_context=12;dut.input_point=0;tick(false);
    if(!dut.fault)throw std::runtime_error("invalid initial-body input was accepted");
    dut.final();std::cout<<"PASS initial-body buffer coefficients="<<checked<<" batches=3 reset/bounds/native-order\n";
}

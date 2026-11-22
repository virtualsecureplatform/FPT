#include "VPaperStreamingCoefficientStore17.h"
#include <array>
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>

int main()
{
    VPaperStreamingCoefficientStore17 dut;
    std::mt19937 random(239);
    std::array<std::array<std::uint32_t,512>,36> memory{};
    std::array<unsigned,36> valid{};
    std::array<bool,36> known{};
    bool active=false,load=false,initial=false;
    unsigned slot=0,chunk=0,initialize=0,writes=0,reads=0;
    for(unsigned cycle=0;cycle<5000;++cycle) {
        dut.clk=0;dut.reset=cycle%997==0;
        if(dut.reset){active=false;initialize=0;valid.fill(0);}
        dut.write_valid=!dut.reset && random()%4!=0;
        if(!active && dut.write_valid) {
            slot=initialize<36?initialize:random()%36;
            load=initialize<36 || random()%7==0;initial=random()%5==0;
            active=true;chunk=0;
        }
        dut.write_context=slot/3;dut.write_component=slot%3;dut.write_chunk=chunk;
        dut.write_load=load;dut.write_initial=initial;
        std::array<std::uint32_t,64> low,high;
        for(unsigned lane=0;lane<64;++lane) {
            low[lane]=random()&0x1ffff;high[lane]=random()&0x1ffff;
            dut.write_low[lane]=low[lane];dut.write_high[lane]=high[lane];
        }
        const unsigned read_slot=random()%36;
        dut.read_context=read_slot/3;dut.read_component=read_slot%3;
        dut.read_initial=random()%3==0;
        const bool zero=dut.read_initial && read_slot%3<2;
        const bool ready=zero || valid[read_slot]==15;
        dut.read_valid=!dut.reset && ready && (!dut.write_valid || read_slot!=slot || zero);
        dut.eval();
        if(!dut.reset) {
            if(bool(dut.read_ready)!=ready || dut.fault)throw std::runtime_error("coefficient RAM validity/protocol mismatch");
            if(zero || known[read_slot])for(unsigned point=0;point<512;++point) {
                if(dut.read_code[point]!=(zero?0:memory[read_slot][point]))
                    throw std::runtime_error("coefficient RAM read mismatch");
                ++reads;
            }
        }
        dut.clk=1;dut.eval();
        if(dut.write_valid) {
            for(unsigned lane=0;lane<64;++lane) {
                const unsigned index=chunk*64+lane,point=index/4+(index%4)*64;
                const bool clear=load || (initial && slot%3<2);
                memory[slot][point]=((clear?0:memory[slot][point])+low[lane])&0x1ffff;
                memory[slot][point+256]=((clear?0:memory[slot][point+256])+high[lane])&0x1ffff;
            }
            valid[slot]=chunk==0?1:valid[slot]|(1u<<chunk);
            if(chunk==3){known[slot]=true;active=false;if(initialize<36)++initialize;}
            else ++chunk;
            ++writes;
        }
        if(dut.reset && known[read_slot])for(unsigned point=0;point<512;++point)
            if(dut.read_code[point]!=(zero?0:memory[read_slot][point]))
                throw std::runtime_error("reset changed coefficient payload");
    }
    auto reset=[&] {
        dut.clk=0;dut.reset=1;dut.read_valid=0;dut.write_valid=0;dut.eval();dut.clk=1;dut.eval();dut.reset=0;
    };
    reset();
    dut.clk=0;dut.write_valid=1;dut.write_context=12;dut.write_component=0;dut.write_chunk=0;
    dut.eval();dut.clk=1;dut.eval();
    if(!dut.fault)throw std::runtime_error("invalid coefficient address not rejected");
    reset();dut.clk=0;dut.read_context=7;dut.read_component=0;dut.read_initial=1;dut.read_valid=1;dut.eval();
    if(!dut.read_ready)throw std::runtime_error("initial mask unavailable after reset");
    for(auto word:dut.read_code)if(word!=0)throw std::runtime_error("permanent zero word was corrupted");
    dut.clk=1;dut.eval();
    if(dut.fault)throw std::runtime_error("initial mask falsely rejected");
    reset();dut.clk=0;dut.read_component=2;dut.read_valid=1;dut.eval();dut.clk=1;dut.eval();
    if(!dut.fault)throw std::runtime_error("uninitialized body read not rejected");
    dut.final();std::cout<<"PASS coefficient store writes="<<writes<<" coefficient_reads="<<reads<<'\n';
}

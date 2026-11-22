#include "VPaperStreamingMixedCoefficientStore17.h"
#include <array>
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>
struct Write {
    bool valid=false,load=false,initial=false,replace=false;
    unsigned slot=0,chunk=0;
    std::array<unsigned,128> delta{},replacement{};
};
int main()
{
    VPaperStreamingMixedCoefficientStore17 dut;
    std::mt19937 random(311);
    std::array<std::array<unsigned,512>,36> memory{};
    std::array<unsigned,36> valid{};
    Write pending;
    bool active=false,load=false,initial=false,replace=false;
    unsigned slot=0,chunk=0,initialize=0,reads=0,writes=0;
    for(unsigned cycle=0;cycle<5000;++cycle) {
        dut.clk=0;dut.reset=cycle%997==0;
        if(dut.reset){active=false;initialize=0;valid.fill(0);pending.valid=false;}
        Write next;
        next.valid=!dut.reset && random()%4!=0;
        if(!active && next.valid) {
            slot=initialize<36?initialize:random()%36;load=initialize<36 || random()%7==0;
            initial=random()%5==0;replace=random()%4==0;active=true;chunk=0;
        }
        next.slot=slot;next.chunk=chunk;next.load=load;next.initial=initial;next.replace=replace;
        dut.write_valid=next.valid;dut.write_load=load;dut.write_initial=initial;
        dut.write_context=slot/3;dut.write_component=slot%3;dut.write_chunk=chunk;
        dut.write_replace=pending.replace;
        for(unsigned lane=0;lane<64;++lane) {
            next.delta[2*lane]=random()&0x1ffff;next.delta[2*lane+1]=random()&0x1ffff;
            next.replacement[2*lane]=random()&0x1ffff;next.replacement[2*lane+1]=random()&0x1ffff;
            dut.write_low[lane]=next.delta[2*lane];dut.write_high[lane]=next.delta[2*lane+1];
            dut.replacement_low[lane]=pending.replacement[2*lane];dut.replacement_high[lane]=pending.replacement[2*lane+1];
        }
        const unsigned read_slot=random()%36;
        dut.read_context=read_slot/3;dut.read_component=read_slot%3;dut.read_initial=random()%3==0;
        const bool zero=dut.read_initial && read_slot%3<2,ready=zero || valid[read_slot]==15;
        dut.read_valid=!dut.reset && ready && (!pending.valid || pending.slot!=read_slot || zero);
        std::array<unsigned,512> expected_read=memory[read_slot];if(zero)expected_read.fill(0);
        dut.eval();
        if(!dut.reset && bool(dut.read_ready)!=ready)throw std::runtime_error("mixed-state read readiness mismatch");
        if(!dut.reset && bool(dut.commit_valid)!=pending.valid)throw std::runtime_error("mixed-state commit latency mismatch");
        if(pending.valid) {
            if(dut.commit_context!=pending.slot/3 || dut.commit_component!=pending.slot%3 || dut.commit_chunk!=pending.chunk || bool(dut.commit_load)!=pending.load)
                throw std::runtime_error("mixed-state commit tags mismatch");
            for(unsigned lane=0;lane<64;++lane) {
                const unsigned native=pending.chunk*64+lane,point=native/4+(native%4)*64;
                const bool clear=pending.load || (pending.initial && pending.slot%3<2);
                const unsigned low=((clear?0:memory[pending.slot][point])+pending.delta[2*lane])&0x1ffff;
                const unsigned high=((clear?0:memory[pending.slot][point+256])+pending.delta[2*lane+1])&0x1ffff;
                if(dut.updated_low[lane]!=low || dut.updated_high[lane]!=high)
                    throw std::runtime_error("mixed-state old+delta is wrong before replacement");
            }
        }
        dut.clk=1;dut.eval();
        if(!dut.reset && (dut.fault || bool(dut.read_response_valid)!=bool(dut.read_valid)))throw std::runtime_error("mixed-state protocol/response fault");
        if(!dut.reset && dut.read_response_valid)for(unsigned point=0;point<512;++point) {
            if(dut.read_code[point]!=expected_read[point])throw std::runtime_error("mixed BRAM/LUTRAM front read mismatch");
            ++reads;
        }
        if(pending.valid) {
            for(unsigned lane=0;lane<64;++lane) {
                const unsigned native=pending.chunk*64+lane,point=native/4+(native%4)*64;
                const bool clear=pending.load || (pending.initial && pending.slot%3<2);
                for(unsigned fold=0;fold<2;++fold) {
                    auto &word=memory[pending.slot][point+fold*256];
                    word=pending.replace?pending.replacement[2*lane+fold]:((clear?0:word)+pending.delta[2*lane+fold])&0x1ffff;
                }
            }
            valid[pending.slot]=pending.chunk==0?1:valid[pending.slot]|(1u<<pending.chunk);++writes;
        }
        pending=next;
        if(next.valid) {
            if(chunk==3){active=false;if(initialize<36)++initialize;}else ++chunk;
        }
    }
    dut.final();std::cout<<"PASS mixed state writes="<<writes<<" coefficient_reads="<<reads<<" old-plus-delta/replacement/reset\n";
}

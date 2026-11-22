#include "VPaperStreamingKeyCache.h"
#include <array>
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>

constexpr unsigned steps=75;
static std::uint64_t coefficient(unsigned step,unsigned slot,unsigned lane)
{
    const std::uint64_t real=(0x9e3779b9u*(lane+1)^0x12345u*step^0x7654321u*slot)&0xfffffff;
    const std::uint64_t imag=(0x85ebca6bu*(lane+3)^0x54321u*step^0x1234567u*slot)&0xfffffff;
    return real|(imag<<28);
}
int main()
{
    VPaperStreamingKeyCache dut;
    std::mt19937 random(277);
    unsigned load_step=0,completed=0,read_ordinal=0,checked=0;
    std::array<unsigned,4> accepted{};
    auto tick=[&] {
        dut.clk=0;dut.eval();
        const bool response=dut.read_valid && !dut.reset;
        const unsigned expected_step=dut.read_step+1000*(read_ordinal/(steps*144)),expected_slot=2*dut.read_row+dut.read_half;
        const auto takes=dut.load_valid&dut.load_ready;
        const bool starts=dut.load_start_valid && dut.load_start_ready;
        dut.clk=1;dut.eval();
        if(!dut.reset) {
            if(dut.fault || bool(dut.response_valid)!=response)throw std::runtime_error("key cache valid/fault mismatch");
            if(response)for(unsigned lane=0;lane<384;++lane) {
                const auto value=coefficient(expected_step,expected_slot,lane);
                if((dut.key_real[lane/128][lane%128]&0xfffffff)!=(value&0xfffffff) ||
                   (dut.key_imag[lane/128][lane%128]&0xfffffff)!=(value>>28))
                    throw std::runtime_error("key cache BRAM packing/order mismatch");
                ++checked;
            }
            if(starts)accepted={};
            else for(unsigned p=0;p<4;++p)accepted[p]+=(takes>>p)&1;
            if(dut.loaded_valid) {
                if(dut.loaded_step!=completed%steps)throw std::runtime_error("key cache load completion order mismatch");
                ++completed;load_step=(load_step+1)%steps;
            }
        }
    };
    auto reset=[&] {
        dut.reset=1;dut.read_valid=0;dut.read_retire=0;dut.load_valid=0;dut.load_start_valid=0;
        tick();dut.reset=0;load_step=0;completed=0;read_ordinal=0;accepted={};
    };
    auto loader=[&] {
        dut.load_start_valid=completed<2*steps;dut.load_step=load_step;dut.load_valid=0;
        dut.clk=0;dut.eval();
        for(unsigned port=0;port<4;++port) {
            for(unsigned w=0;w<16;++w)dut.load_data[port][w]=0;
            if(!((dut.load_ready>>port)&1) || random()%20==0)continue;
            dut.load_valid|=1u<<port;
            const unsigned groups=port==3?10:11,slot=accepted[port]/groups;
            const unsigned group=port+4*(accepted[port]%groups);
            for(unsigned lane=0;lane<9;++lane) {
                const auto value=coefficient(load_step+1000*(completed/steps),slot,group*9+lane);
                for(unsigned bit=0;bit<56;++bit)if((value>>bit)&1)
                    dut.load_data[port][(lane*56+bit)/32]|=1u<<((lane*56+bit)%32);
            }
        }
    };
    reset();
    unsigned cycles=0;
    while(read_ordinal<2*steps*144 && cycles<30000) {
        loader();
        dut.read_valid=completed>=8 || read_ordinal!=0;
        const unsigned step=(read_ordinal/144)%steps,context=(read_ordinal/12)%12,slot=read_ordinal%12;
        dut.read_step=step;dut.read_row=slot/2;dut.read_half=slot%2;
        dut.read_retire=dut.read_valid && context==11 && slot==11;
        dut.clk=0;dut.eval();
        if(dut.read_valid && !dut.read_ready) {
            std::cerr<<"cycle="<<cycles<<" read_step="<<step<<" completed="<<completed<<" loading="<<load_step<<'\n';
            throw std::runtime_error("key loader did not sustain 144-cycle step schedule");
        }
        tick();read_ordinal+=dut.read_valid;++cycles;
    }
    if(read_ordinal!=2*steps*144 || completed!=2*steps)throw std::runtime_error("key cache did not complete wraparound workload");
    // Aborting a partial key load must invalidate stale RAM payload.
    reset();
    for(unsigned i=0;i<50;++i){loader();tick();}
    reset();dut.read_step=0;dut.read_row=0;dut.read_half=0;dut.clk=0;dut.eval();
    if(dut.initial_ready || dut.read_ready)throw std::runtime_error("key cache retained validity after reset");
    dut.final();std::cout<<"PASS key cache steps="<<steps<<" epochs=2 contexts=12 complex_values="<<checked<<" cycles="<<cycles<<" wrap/reset/stalled-loader\n";
}

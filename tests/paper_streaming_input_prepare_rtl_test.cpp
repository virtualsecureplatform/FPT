#include "VPaperStreamingInputPrepare17.h"
#include <array>
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>

static unsigned mod_switch(std::uint32_t value)
{return ((std::uint64_t(value)+(1u<<21))>>22)&1023;}
static unsigned quantize(std::uint32_t value)
{
    const auto quotient=value>>15,remainder=value&32767;
    return (quotient+(remainder>16384 || (remainder==16384 && quotient%2)))&0x1ffff;
}
int main()
{
    VPaperStreamingInputPrepare17 dut;
    std::mt19937 random(281);
    std::array<std::uint32_t,512> lookup;
    std::array<std::array<std::uint32_t,587>,12> inputs;
    unsigned checked=0,exponents=0,ordinal=0;
    auto tick=[&](bool check_fault=true) {
        dut.clk=0;dut.eval();
        if(!dut.reset && dut.exponent_valid) {
            const unsigned context=dut.tlwe_context,step=dut.tlwe_word;
            if(!dut.exponent_ready || dut.exponent_context!=context || dut.exponent_step!=step ||
               dut.exponent_value!=mod_switch(inputs[context][step]))
                throw std::runtime_error("public-input exponent mismatch");
            ++exponents;
        }
        if(!dut.reset && dut.body_valid && dut.body_ready) {
            const unsigned context=ordinal/512,point=ordinal%512;
            const unsigned source=(point+mod_switch(inputs[context][586]))%1024;
            const unsigned value=quantize(lookup[source%512]);
            const unsigned expected=source>=512?(0u-value)&0x1ffff:value;
            if(dut.body_context!=context || dut.body_point!=point || dut.body_code!=expected ||
               bool(dut.body_last)!=(ordinal==6143))throw std::runtime_error("initial body differs from public Torus32 rotation/RNE");
            ++ordinal;++checked;
        }
        dut.clk=1;dut.eval();
        if(!dut.reset && check_fault && dut.fault)throw std::runtime_error("input preparation protocol fault");
    };
    auto reset=[&] {
        dut.reset=1;dut.start=0;dut.lookup_valid=0;dut.tlwe_valid=0;dut.body_ready=0;dut.exponent_ready=0;
        tick();dut.reset=0;ordinal=0;
    };
    auto load=[&] {
        for(unsigned point=0;point<512;++point)lookup[point]=random();
        constexpr std::uint32_t edges[]={0,0x3fff,0x4000,0x4001,0x7fff,0x8000,0xbfff,0xc000,0xffffffff};
        for(unsigned point=0;point<std::size(edges);++point)lookup[point]=edges[point];
        for(auto &input:inputs)for(auto &value:input)value=random();
        constexpr unsigned rotations[]={0,1,63,64,127,128,255,256,511,512,513,1023};
        for(unsigned context=0;context<12;++context) {
            inputs[context][586]=rotations[context]<<22;
            inputs[context][0]=edges[context%std::size(edges)];
        }
        for(unsigned point=0;point<512;) {
            dut.lookup_valid=random()%4!=0;dut.lookup_index=point;dut.lookup_value=lookup[point];
            dut.clk=0;dut.eval();const bool accepted=dut.lookup_valid && dut.lookup_ready;tick();point+=accepted;
        }
        dut.lookup_valid=0;
        for(unsigned index=0;index<12*587;) {
            dut.tlwe_valid=random()%4!=0;dut.exponent_ready=random()%3!=0;
            dut.tlwe_context=index/587;dut.tlwe_word=index%587;dut.tlwe_value=inputs[index/587][index%587];
            dut.clk=0;dut.eval();const bool accepted=dut.tlwe_valid && dut.tlwe_ready;tick();index+=accepted;
        }
        dut.tlwe_valid=0;dut.clk=0;dut.eval();
        if(!dut.start_ready)throw std::runtime_error("loaded input preparation frame is not ready");
    };
    auto start_frame=[&] {
        // A producer may already offer the next LUT when this frame starts.
        // That word must wait until all current-body reads have completed.
        dut.lookup_valid=1;dut.lookup_index=0;dut.lookup_value=~lookup[0];dut.start=1;
        dut.clk=0;dut.eval();if(dut.lookup_ready)throw std::runtime_error("lookup accepted while frame start snapshots its contents");
        tick();dut.start=0;dut.lookup_valid=0;
    };
    reset();
    for(unsigned batch=0;batch<2;++batch) {
        load();start_frame();
        if(batch==1) {
            dut.body_ready=1;for(unsigned i=0;i<100;++i)tick();reset();load();
            start_frame();
        }
        unsigned cycles=0;
        bool was_stalled=false;unsigned held_code=0,held_context=0,held_point=0;
        while(!dut.done && cycles<20000) {
            dut.clk=0;dut.body_ready=cycles%509<100?0:random()%3!=0;dut.eval();
            if(was_stalled && (!dut.body_valid || dut.body_code!=held_code || dut.body_context!=held_context || dut.body_point!=held_point))
                throw std::runtime_error("input preparation changed a stalled coefficient");
            was_stalled=dut.body_valid && !dut.body_ready;
            held_code=dut.body_code;held_context=dut.body_context;held_point=dut.body_point;
            tick();++cycles;
        }
        if(!dut.done || dut.busy || ordinal!=6144)throw std::runtime_error("initial body preparation did not drain");
        ordinal=0;
    }
    reset();dut.exponent_ready=1;dut.tlwe_valid=1;dut.tlwe_context=13;dut.tlwe_word=0;
    tick(false);if(!dut.fault)throw std::runtime_error("invalid input context was not rejected");
    dut.final();std::cout<<"PASS input preparation coefficients="<<checked<<" exponents="<<exponents<<" RNE/rotation/stalls/reset/bounds\n";
}

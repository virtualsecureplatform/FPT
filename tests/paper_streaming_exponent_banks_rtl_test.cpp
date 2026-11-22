#include "VPaperStreamingExponentBanks.h"
#include <iostream>
#include <random>
#include <stdexcept>
constexpr unsigned steps=7,words=12*steps;
static unsigned value(unsigned frame,unsigned context,unsigned step)
{return (frame*313+context*17+step*11)&1023;}
int main()
{
    VPaperStreamingExponentBanks dut;
    std::mt19937 random(293);
    unsigned load_frame=0,load_index=0,read_frame=0,read_index=0,checked=0;
    auto tick=[&] {
        dut.clk=0;dut.eval();
        const bool load=dut.load_valid && dut.load_ready && !dut.reset;
        const bool read=dut.read_valid && !dut.reset;
        const unsigned expected=value(read_frame,read_index%12,read_index/12);
        dut.clk=1;dut.eval();
        if(!dut.reset && dut.fault)throw std::runtime_error("exponent banks protocol fault");
        if(read) {
            if(dut.read_value!=expected)throw std::runtime_error("exponent bank transpose/ownership mismatch");
            ++read_index;++checked;
        }
        if(load && ++load_index==words){load_index=0;++load_frame;}
    };
    auto drive_loader=[&] {
        dut.load_valid=load_frame<4 && random()%5!=0;
        dut.load_context=load_index/steps;dut.load_step=load_index%steps;
        dut.load_value=value(load_frame,load_index/steps,load_index%steps);
    };
    dut.reset=1;dut.load_valid=0;dut.read_valid=0;dut.activate=0;dut.retire=0;tick();dut.reset=0;
    for(read_frame=0;read_frame<4;++read_frame) {
        dut.read_valid=0;dut.retire=0;
        for(unsigned wait=0;!dut.activate_ready && wait<1000;++wait){drive_loader();tick();}
        if(!dut.activate_ready)throw std::runtime_error("complete exponent frame did not become ready");
        dut.activate=1;drive_loader();tick();dut.activate=0;read_index=0;
        while(read_index<words) {
            drive_loader();dut.read_valid=random()%3!=0;dut.read_address=read_index;
            tick();
        }
        dut.read_valid=0;dut.retire=1;drive_loader();tick();dut.retire=0;
    }
    dut.reset=1;dut.load_valid=0;tick();dut.reset=0;dut.clk=0;dut.eval();
    if(dut.activate_ready)throw std::runtime_error("reset exposed stale exponent payload");
    dut.final();std::cout<<"PASS exponent banks values="<<checked<<" frames=4 concurrent-load/transpose/reset\n";
}

#include "VPaperStreamingSampleBuffer17.h"
#include <array>
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>

using Batch=std::array<std::array<std::array<std::uint32_t,512>,3>,12>;
struct Reply {bool valid;std::uint32_t word;};
int main()
{
    VPaperStreamingSampleBuffer17 dut;
    std::array<Batch,2> batches;
    std::mt19937 random(269);
    std::deque<Reply> pending;
    unsigned checked=0;
    auto tick=[&](bool check_fault=true) {
        const bool accepted=dut.read_valid && !dut.reset;
        std::uint32_t expected=0;
        if(accepted) {
            const auto &sample=batches[dut.read_bank][dut.read_context];
            const auto word=dut.read_word;
            if(word==1024)expected=sample[2][0]<<15;
            else {
                const auto point=word%512;
                const auto code=sample[word/512][point==0?0:512-point];
                expected=(point==0?code:(0u-code)&0x1ffffu)<<15;
            }
        }
        dut.clk=0;dut.eval();dut.clk=1;dut.eval();
        if(dut.reset)pending.clear();else pending.push_back({accepted,expected});
        Reply reply{false,0};
        if(pending.size()>=3){reply=pending.front();pending.pop_front();}
        if(bool(dut.response_valid)!=reply.valid || (reply.valid && dut.response_word!=reply.word))
            throw std::runtime_error("sample extraction/read latency mismatch");
        checked+=reply.valid;
        if(check_fault && !dut.reset && dut.fault)throw std::runtime_error("sample buffer protocol fault");
    };
    auto reset=[&] {
        dut.reserve_valid=0;dut.capture_valid=0;dut.read_valid=0;dut.release_valid=0;
        dut.reset=1;tick();dut.reset=0;
    };
    auto randomize=[&](unsigned bank) {
        for(auto &context:batches[bank])for(auto &component:context)for(auto &code:component)
            code=random()&0x1ffff;
    };
    auto reserve=[&](unsigned bank) {
        if(!(dut.available&(1u<<bank)))throw std::runtime_error("sample bank is not available");
        dut.reserve_valid=1;dut.reserve_bank=bank;tick();dut.reserve_valid=0;
    };
    auto capture=[&](unsigned bank,unsigned ordinal) {
        const unsigned context=ordinal/12,component=(ordinal/4)%3,chunk=ordinal%4;
        dut.capture_valid=1;dut.capture_context=context;dut.capture_component=component;dut.capture_chunk=chunk;
        for(unsigned lane=0;lane<64;++lane) {
            const unsigned native=chunk*64+lane,point=native/4+(native%4)*64;
            dut.capture_low[lane]=batches[bank][context][component][point];
            dut.capture_high[lane]=batches[bank][context][component][point+256];
        }
    };
    auto drain=[&] {dut.read_valid=0;dut.capture_valid=0;for(unsigned i=0;i<4;++i)tick();};
    auto release=[&](unsigned bank) {
        dut.release_valid=1;dut.release_bank=bank;tick();dut.release_valid=0;
        if(!(dut.available&(1u<<bank)) || (dut.ready&(1u<<bank)))throw std::runtime_error("sample release failed");
    };
    reset();
    for(unsigned iteration=0;iteration<3;++iteration) {
        randomize(0);randomize(1);reserve(0);
        for(unsigned ordinal=0;ordinal<144;++ordinal) {
            capture(0,ordinal);tick();
            if(random()%3==0){dut.capture_valid=0;tick();}
        }
        drain();
        if(dut.ready!=1)throw std::runtime_error("sample batch completion mismatch");
        reserve(1);
        unsigned next_capture=0;
        for(unsigned word=0;word<12300;++word) {
            dut.read_valid=1;dut.read_bank=0;dut.read_context=word/1025;dut.read_word=word%1025;
            if(next_capture<144)capture(1,next_capture++);else dut.capture_valid=0;
            tick();
            if(random()%4==0){dut.read_valid=0;dut.capture_valid=0;tick();}
        }
        drain();
        if(dut.ready!=3)throw std::runtime_error("overlapped sample capture incomplete");
        release(0);
        for(unsigned word=0;word<12300;++word) {
            dut.read_valid=1;dut.read_bank=1;dut.read_context=word/1025;dut.read_word=word%1025;tick();
        }
        drain();release(1);
    }
    // Reset aborts an incomplete capture and clears response validity, while
    // leaving the payload RAM unreset. Reusing the bank overwrites all masks.
    reserve(0);capture(0,0);tick();reset();
    if(dut.available!=3 || dut.ready!=0)throw std::runtime_error("sample reset did not free buffers");
    reserve(0);dut.capture_valid=1;dut.capture_context=1;dut.capture_component=0;dut.capture_chunk=0;
    tick(false);if(!dut.fault)throw std::runtime_error("out-of-order capture was accepted");
    reset();
    dut.final();std::cout<<"PASS sample buffer words="<<checked<<" batches=6 overlap/reset/order\n";
}

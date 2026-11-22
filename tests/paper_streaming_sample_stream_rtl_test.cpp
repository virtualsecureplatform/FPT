#include "VPaperStreamingSampleStream.h"
#include <array>
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>

struct Read {bool valid;std::uint32_t value;};
static std::uint32_t word(unsigned bank,unsigned index)
{return (0x9e3779b9u*(index+1))^(bank?0xa5a55a5au:0x12345678u);}
int main()
{
    VPaperStreamingSampleStream dut;
    std::mt19937 random(271);
    std::deque<Read> pipe;
    unsigned requested=0,transferred=0,beats=0,checked=0;
    unsigned bank=0;
    bool stalled=false;
    std::array<std::uint32_t,16> held{};
    std::uint64_t held_keep=0;bool held_last=false;
    auto tick=[&] {
        dut.clk=0;dut.eval();
        Read request{bool(dut.read_valid && !dut.reset),0};
        if(request.valid) {
            if(dut.read_bank!=bank || dut.read_context!=requested/1025 || dut.read_word!=requested%1025)
                throw std::runtime_error("sample stream read order mismatch");
            request.value=word(bank,requested++);
        }
        if(dut.reset)pipe.clear();else pipe.push_back(request);
        Read reply{false,0};
        if(pipe.size()>=4){reply=pipe.front();pipe.pop_front();}
        dut.response_valid=reply.valid;dut.response_word=reply.value;dut.eval();
        if(!dut.reset) {
            if(stalled) {
                if(!dut.output_valid || dut.output_keep!=held_keep || bool(dut.output_last)!=held_last)
                    throw std::runtime_error("sample stream changed stalled metadata");
                for(unsigned i=0;i<16;++i)if(dut.output_data[i]!=held[i])
                    throw std::runtime_error("sample stream changed stalled data");
            }
            if(dut.output_valid && dut.output_ready) {
                const bool last=transferred+16>=12300;
                if(bool(dut.output_last)!=last || dut.output_keep!=(last?0x0000ffffffffffffULL:~0ULL))
                    throw std::runtime_error("sample stream final byte enable mismatch");
                const unsigned count=last?12:16;
                for(unsigned i=0;i<count;++i) {
                    if(dut.output_data[i]!=word(bank,transferred++))throw std::runtime_error("sample stream data mismatch");
                    ++checked;
                }
                ++beats;
            }
            stalled=dut.output_valid && !dut.output_ready;
            if(stalled) {
                for(unsigned i=0;i<16;++i)held[i]=dut.output_data[i];
                held_keep=dut.output_keep;held_last=dut.output_last;
            }
        } else stalled=false;
        dut.clk=1;dut.eval();
        if(!dut.reset && dut.fault)throw std::runtime_error("sample stream protocol fault");
        if(!dut.reset && dut.release_valid && (transferred!=12300 || dut.release_bank!=bank))
            throw std::runtime_error("sample stream released an unread bank");
    };
    auto reset=[&] {
        dut.reset=1;dut.start=0;dut.output_ready=0;tick();dut.reset=0;
        requested=0;transferred=0;beats=0;
    };
    dut.sample_ready=3;reset();
    for(unsigned batch=0;batch<4;++batch) {
        bank=batch%2;dut.start_bank=bank;
        dut.clk=0;dut.eval();if(!dut.start_ready)throw std::runtime_error("sample stream did not admit batch");
        dut.start=1;tick();dut.start=0;
        if(batch==1) {
            for(unsigned i=0;i<300;++i){dut.output_ready=random()%2;tick();}
            reset();dut.start=1;tick();dut.start=0;
        }
        unsigned elapsed=0;
        while(!dut.done && elapsed<100000) {
            dut.output_ready=elapsed%509<100?0:random()%3!=0;
            tick();++elapsed;
        }
        if(!dut.done || dut.busy || requested!=12300 || transferred!=12300 || beats!=769 || !dut.release_valid)
            throw std::runtime_error("sample stream completion count mismatch");
        std::cout<<"batch="<<batch<<" words="<<transferred<<" beats="<<beats<<" cycles="<<elapsed<<'\n';
        for(unsigned i=0;i<4;++i)tick();
        requested=0;transferred=0;beats=0;
    }
    dut.final();std::cout<<"PASS sample stream words="<<checked<<" stalls/reset/final-keep\n";
}

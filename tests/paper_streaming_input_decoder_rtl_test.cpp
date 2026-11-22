#include "VPaperStreamingInputDecoder.h"
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>
constexpr unsigned words=7556,beats=(words+15)/16;
static std::uint32_t value(unsigned frame,unsigned word)
{return 0x9e3779b9u*(word+1)^(frame*0x1234567u);}
int main()
{
    VPaperStreamingInputDecoder dut;
    std::mt19937 random(317);
    unsigned input=0,output=0,done=0,checked=0;
    bool pending=false;
    auto tick=[&](bool check_fault=true) {
        dut.clk=0;dut.eval();
        const bool accepted=dut.input_valid && dut.input_ready && !dut.reset;
        const bool lookup=dut.lookup_valid && dut.lookup_ready && !dut.reset;
        const bool tlwe=dut.tlwe_valid && dut.tlwe_ready && !dut.reset;
        if(lookup || tlwe) {
            const unsigned frame=output/words,index=output%words;
            if(lookup!=(index<512))throw std::runtime_error("DMA decoder lookup/TLWE boundary mismatch");
            if(lookup && (dut.lookup_index!=index || dut.lookup_value!=value(frame,index)))
                throw std::runtime_error("DMA lookup word mismatch");
            if(tlwe && (dut.tlwe_context!=(index-512)/587 || dut.tlwe_word!=(index-512)%587 || dut.tlwe_value!=value(frame,index)))
                throw std::runtime_error("DMA TLWE metadata/data mismatch");
            ++output;++checked;
        }
        dut.clk=1;dut.eval();
        if(accepted){++input;pending=false;}
        if(dut.reset){input=0;output=0;done=0;pending=false;}
        else {done+=dut.frame_done;if(check_fault && dut.fault)throw std::runtime_error("DMA input decoder fault");}
    };
    auto reset=[&] {
        dut.reset=1;dut.input_valid=0;dut.lookup_ready=0;dut.tlwe_ready=0;tick();dut.reset=0;
    };
    reset();
    bool recovered=false;
    for(unsigned cycle=0;done<4 && cycle<100000;++cycle) {
        if(!recovered && output>800){reset();recovered=true;}
        if(!pending && input<4*beats && random()%5!=0) {
            const unsigned frame=input/beats,beat=input%beats;
            for(unsigned word=0;word<16;++word) {
                const unsigned index=beat*16+word;
                dut.input_data[word]=index<words?value(frame,index):0;
            }
            dut.input_last=beat+1==beats;pending=true;
        }
        dut.input_valid=pending;dut.lookup_ready=cycle%509<100?0:random()%3!=0;dut.tlwe_ready=random()%3!=0;tick();
    }
    if(done!=4 || output!=4*words || !recovered)throw std::runtime_error("DMA decoder frame count/drain mismatch");
    reset();dut.input_valid=1;dut.input_last=1;
    for(unsigned word=0;word<16;++word)dut.input_data[word]=0;
    tick(false);if(!dut.fault)throw std::runtime_error("early DMA TLAST was accepted");
    reset();
    for(unsigned beat=0;beat<beats;) {
        dut.input_valid=1;dut.input_last=beat+1==beats;dut.lookup_ready=1;dut.tlwe_ready=1;
        for(unsigned word=0;word<16;++word)dut.input_data[word]=beat*16+word<words?value(0,beat*16+word):0;
        if(beat+1==beats)dut.input_data[15]=1;
        dut.clk=0;dut.eval();const bool take=dut.input_ready;tick(false);beat+=take;
    }
    if(!dut.fault)throw std::runtime_error("nonzero DMA padding was accepted");
    dut.final();std::cout<<"PASS input decoder words="<<checked<<" frames=4 stalls/reset/TLAST/padding\n";
}

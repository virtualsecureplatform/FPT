#include "VPaperStreamingKeyUnpack.h"
#include <array>
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>
#include <vector>

struct Beat {std::vector<std::uint64_t> words;bool last;};
int main()
{
    VPaperStreamingKeyUnpack dut;
    std::mt19937_64 random(173);
    std::deque<std::uint8_t> bytes;
    std::deque<Beat> expected;
    unsigned accepted=0,checked=0,frame_beats=0;
    for(unsigned cycle=0;cycle<10000;++cycle) {
        dut.clk=0;dut.reset=cycle%997==0;
        dut.input_valid=random()%4!=0;dut.output_ready=random()%3!=0;
        dut.input_last=frame_beats==83;
        for(unsigned j=0;j<16;++j)dut.input_data[j]=random();
        dut.eval();
        if(dut.reset){expected.clear();bytes.clear();frame_beats=0;}
        else {
            if(bool(dut.output_valid)!=!expected.empty())throw std::runtime_error("unpack valid mismatch");
            if(dut.output_valid) {
                const auto &beat=expected.front();
                if(dut.output_count!=beat.words.size() || bool(dut.output_last)!=beat.last || dut.frame_error)
                    throw std::runtime_error("unpack framing/count mismatch");
                for(unsigned word=0;word<beat.words.size();++word) {
                    std::uint64_t value=0;
                    for(unsigned bit=0;bit<56;++bit) {
                        const unsigned index=word*56+bit;
                        value|=std::uint64_t((dut.output_words[index/32]>>(index%32))&1)<<bit;
                    }
                    if(value!=beat.words[word])throw std::runtime_error("unpack payload mismatch");
                }
                if(dut.output_ready){checked+=beat.words.size();expected.pop_front();}
            }
            if(dut.input_valid && dut.input_ready) {
                for(unsigned byte=0;byte<64;++byte)
                    bytes.push_back((dut.input_data[byte/4]>>(8*(byte%4)))&255);
                Beat beat{{},bool(dut.input_last)};
                while(bytes.size()>=7) {
                    std::uint64_t value=0;
                    for(unsigned byte=0;byte<7;++byte){value|=std::uint64_t(bytes.front())<<(8*byte);bytes.pop_front();}
                    beat.words.push_back(value);
                }
                expected.push_back(beat);++accepted;
                if(dut.input_last){if(!bytes.empty())throw std::runtime_error("oracle alignment");frame_beats=0;}
                else ++frame_beats;
            }
        }
        dut.clk=1;dut.eval();
    }
    dut.final();std::cout<<"PASS key unpack RTL input_beats="<<accepted<<" complex_words="<<checked<<'\n';
}

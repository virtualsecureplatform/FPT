#include "VPaperStreamingMacTile.h"
#include <array>
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>

#ifndef TEST_LANES
#define TEST_LANES 4
#endif
#ifndef TEST_ACC_WIDTH
#define TEST_ACC_WIDTH 48
#endif
constexpr unsigned lanes=TEST_LANES,points=2*lanes;
struct Beat {unsigned context,component,chunk;std::array<std::int64_t,lanes/2> real,imag;};
static std::int64_t round_even(std::int64_t raw,unsigned shift)
{
    const auto divisor=1LL<<shift;
    auto quotient=raw/divisor,remainder=raw%divisor;
    const auto magnitude=remainder<0?-remainder:remainder;
    if(magnitude>divisor/2 || (magnitude==divisor/2 && quotient%2!=0))quotient+=raw<0?-1:1;
    return quotient;
}
int main()
{
    VPaperStreamingMacTile dut;
    std::mt19937_64 random(191);
    std::deque<Beat> expected;
    std::array<std::array<std::array<std::int64_t,2>,points>,3> sums{};
    unsigned phase=0,transaction=0,beats=0,frames=0,consecutive=0;
    bool previous_valid=false;
    for(unsigned cycle=0;cycle<6200;++cycle) {
        dut.clk=0;dut.reset=cycle%997==0;
        dut.input_valid=(cycle<6000 || phase!=0) && (phase!=0 || random()%4!=0);
        dut.input_context=transaction%12;
        std::array<std::int64_t,lanes> ar,ai;
        std::array<std::array<std::int64_t,lanes>,3> br,bi;
        for(unsigned j=0;j<lanes;++j) {
            ar[j]=std::int64_t(random()%(1ULL<<26))-(1LL<<25);
            ai[j]=std::int64_t(random()%(1ULL<<26))-(1LL<<25);
            dut.spectrum_real[j]=ar[j];dut.spectrum_imag[j]=ai[j];
            for(unsigned c=0;c<3;++c) {
                br[c][j]=std::int64_t(random()%(1ULL<<25))-(1LL<<24);
                bi[c][j]=std::int64_t(random()%(1ULL<<25))-(1LL<<24);
                dut.key_real[c][j]=br[c][j];dut.key_imag[c][j]=bi[c][j];
            }
        }
        dut.eval();
        if(dut.reset){phase=0;transaction=0;expected.clear();previous_valid=false;}
        else {
            if(dut.fault)throw std::runtime_error("MAC tile fault on legal schedule");
            if(dut.output_valid) {
                if(expected.empty())throw std::runtime_error("unexpected MAC tile output");
                const auto beat=expected.front();expected.pop_front();
                if(dut.output_context!=beat.context || dut.output_component!=beat.component || dut.output_chunk!=beat.chunk)
                    throw std::runtime_error("MAC tile PISO ordering/tag mismatch");
                for(unsigned j=0;j<lanes/2;++j)
                    if(std::uint32_t(dut.output_real[j])!=std::uint32_t(beat.real[j]) ||
                       std::uint32_t(dut.output_imag[j])!=std::uint32_t(beat.imag[j]))
                        throw std::runtime_error("MAC tile spectrum mismatch");
                ++beats;if(previous_valid)++consecutive;
            }
            previous_valid=dut.output_valid;
            if(dut.input_valid) {
                if(phase==0)sums={};
                for(unsigned c=0;c<3;++c)for(unsigned j=0;j<lanes;++j) {
                    auto &sum=sums[c][(phase%2)*lanes+j];
                    sum[0]+=round_even(ar[j]*br[c][j]-ai[j]*bi[c][j],62-TEST_ACC_WIDTH);
                    sum[1]+=round_even(ar[j]*bi[c][j]+ai[j]*br[c][j],62-TEST_ACC_WIDTH);
                }
                if(phase==11) {
                    for(unsigned c=0;c<3;++c)for(unsigned chunk=0;chunk<4;++chunk) {
                        Beat beat{transaction%12,c,chunk,{},{}};
                        for(unsigned j=0;j<lanes/2;++j) {
                            const auto sum=sums[c][chunk*(lanes/2)+j];
                            beat.real[j]=round_even(sum[0],TEST_ACC_WIDTH-38);beat.imag[j]=round_even(sum[1],TEST_ACC_WIDTH-38);
                        }
                        expected.push_back(beat);
                    }
                    phase=0;++transaction;++frames;
                } else ++phase;
            }
        }
        dut.clk=1;dut.eval();
    }
    if(!expected.empty() || beats==0 || consecutive<beats/2)throw std::runtime_error("MAC tile drain incomplete");
    dut.final();std::cout<<"PASS MAC tile frames="<<frames<<" beats="<<beats<<" consecutive="<<consecutive<<'\n';
}

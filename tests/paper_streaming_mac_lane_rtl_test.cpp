#include "VPaperStreamingMacLane.h"
#include <array>
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>

struct Command {bool valid;unsigned row,address;__int128 real,imag;};
#ifndef TEST_ACC_WIDTH
#define TEST_ACC_WIDTH 48
#endif
constexpr unsigned accumulator_bits=TEST_ACC_WIDTH;
static std::int64_t rounded(__int128 value,bool &overflow)
{
    constexpr std::int64_t divisor=1LL<<(62-accumulator_bits),limit=1LL<<(accumulator_bits-1);
    auto quotient=value/divisor,remainder=value%divisor;
    const auto magnitude=remainder<0?-remainder:remainder;
    if(magnitude>divisor/2 || (magnitude==divisor/2 && quotient%2!=0))quotient+=value<0?-1:1;
    overflow|=quotient < -__int128(limit) || quotient>=limit;
    auto raw=std::uint64_t(quotient)&((1ULL<<accumulator_bits)-1);
    return std::int64_t(raw)-((raw&(1ULL<<(accumulator_bits-1)))?(1LL<<accumulator_bits):0);
}
static std::int64_t add(std::int64_t a,std::int64_t b,bool &overflow)
{
    const auto sum=a+b;
    overflow|=sum<-(1LL<<(accumulator_bits-1)) || sum>=(1LL<<(accumulator_bits-1));
    const auto raw=std::uint64_t(sum)&((1ULL<<accumulator_bits)-1);
    return std::int64_t(raw)-((raw&(1ULL<<(accumulator_bits-1)))?(1LL<<accumulator_bits):0);
}
int main()
{
    VPaperStreamingMacLane dut;
    std::mt19937_64 random(181);
    std::deque<Command> pipe;
    std::array<std::array<std::int64_t,2>,4> sums{};
    std::array<bool,4> initialized{};
    unsigned issued=0,updates=0,results=0,overflows=0;
    for(unsigned cycle=0;cycle<10006;++cycle) {
        dut.clk=0;dut.reset=cycle%997==0;
        dut.input_valid=cycle<10000 && random()%4!=0;
        const unsigned row=(issued/2)%6,address=((issued/12)%2)*2+issued%2;
        dut.input_row=row;dut.input_bank=address/2;dut.input_half=address%2;
        auto ar=std::int64_t(static_cast<std::int32_t>(random()));
        auto ai=std::int64_t(static_cast<std::int32_t>(random()));
        auto br=std::int64_t(random()%(1ULL<<28))-(1LL<<27);
        auto bi=std::int64_t(random()%(1ULL<<28))-(1LL<<27);
        if((issued/12)%7==0) {
            ar=ai=(issued/12)%14==0?-(1LL<<31):(1LL<<31)-1;
            br=bi=-(1LL<<27);
        }
        dut.ar=ar;dut.ai=ai;dut.br=br;dut.bi=bi;
        dut.eval();dut.clk=1;dut.eval();
        Command expected{};bool overflow=false;
        if(dut.reset){pipe.clear();initialized.fill(false);issued=0;}
        else {
            pipe.push_back({bool(dut.input_valid),row,address,__int128(ar)*br-__int128(ai)*bi,
                            __int128(ar)*bi+__int128(ai)*br});
            if(dut.input_valid)++issued;
            if(pipe.size()>=5){expected=pipe.front();pipe.pop_front();}
            if(expected.valid) {
                const auto real=rounded(expected.real,overflow),imag=rounded(expected.imag,overflow);
                if(expected.row==0){sums[expected.address]={0,0};initialized[expected.address]=true;}
                if(!initialized[expected.address])throw std::runtime_error("oracle missing first row");
                auto &sum=sums[expected.address];
                sum={add(sum[0],real,overflow),add(sum[1],imag,overflow)};
                ++updates;overflows+=overflow;
            }
        }
        if(bool(dut.result_valid)!=bool(expected.valid && expected.row==5) || bool(dut.overflow)!=overflow)
            throw std::runtime_error("MAC valid/reset/overflow mismatch");
        if(dut.result_valid) {
            if(dut.result_bank!=expected.address/2 || dut.result_half!=expected.address%2)
                throw std::runtime_error("MAC result bank mismatch");
            ++results;
        }
        for(unsigned slot=0;slot<4;++slot)if(initialized[slot]) {
            dut.read_bank=slot/2;dut.read_half=slot%2;dut.eval();
            constexpr auto mask=(1ULL<<accumulator_bits)-1;
            if((dut.read_real&mask)!=(std::uint64_t(sums[slot][0])&mask) ||
               (dut.read_imag&mask)!=(std::uint64_t(sums[slot][1])&mask))
                throw std::runtime_error("MAC accumulator/readback mismatch");
        }
    }
    if(overflows==0)throw std::runtime_error("MAC overflow cases not exercised");
    dut.final();std::cout<<"PASS MAC RTL updates="<<updates<<" completed_halves="<<results<<" overflows="<<overflows<<'\n';
}

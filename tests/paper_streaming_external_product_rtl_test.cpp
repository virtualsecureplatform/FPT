#include "VPaperStreamingExternalProduct.h"
#include "fpt/paper_streaming_external_product.hpp"
#include "fpt/paper_key_packing.hpp"
#include <algorithm>
#include <cstdint>
#include <deque>
#include <iostream>
#include <memory>
#include <random>
#include <stdexcept>
#include <vector>

namespace ps=fpt::paper_streaming;
namespace pk=fpt::paper_key_packing;
#ifndef TEST_ACC_WIDTH
#define TEST_ACC_WIDTH 48
#endif
#ifndef TEST_LEG_GUARD
#define TEST_LEG_GUARD -1
#endif
#ifndef TEST_KEY_READ_LATENCY
#define TEST_KEY_READ_LATENCY 0
#endif
struct Transaction {
    std::array<ps::Polynomial,6> digits;
    ps::KeyRows key;
    std::array<ps::Polynomial,3> expected;
    unsigned start_cycle;
};

int main()
{
    VPaperStreamingExternalProduct dut;
    std::mt19937_64 random(223);
    ps::TransformSchedule forward{{9,23},26,{}},inverse{{20,12},26,{}};
    forward.stage_data=std::array<fpt::FixedFormat,4>{{{11,21},{13,19},{15,17},{17,15}}};
    inverse.stage_data=std::array<fpt::FixedFormat,4>{{{20,12},{21,11},{22,10},{23,9}}};
    if constexpr(TEST_LEG_GUARD>=0){forward.leg_guard_bits=TEST_LEG_GUARD;inverse.leg_guard_bits=TEST_LEG_GUARD;}
    const ps::ExternalSchedule schedule{forward,inverse,{{17,15},{7,21},{26,TEST_ACC_WIDTH-26}}};
    std::vector<std::unique_ptr<Transaction>> transactions;
    std::deque<unsigned> expected;
    struct KeyRequest {bool valid;unsigned transaction,phase;};
    std::deque<KeyRequest> key_pipe;
    unsigned input_phase=0,sent=0,key_phase=0,key_transaction=0,output_phase=0,retired=0;
    unsigned checked=0,completed=0,generated=0,min_latency=10000,max_latency=0;
    for(unsigned cycle=0;cycle<4500;++cycle) {
        dut.clk=0;dut.reset=cycle%997==0;
        dut.input_valid=!dut.reset && (cycle<4200 || input_phase!=0) && (input_phase!=0 || random()%8!=0);
        dut.key_valid=0;
        if(dut.reset) {
            key_pipe.clear();transactions.clear();expected.clear();input_phase=0;sent=0;key_phase=0;
            key_transaction=0;output_phase=0;retired=0;
        }
        if(dut.input_valid && input_phase==0) {
            auto transaction=std::make_unique<Transaction>();
            transaction->start_cycle=cycle;
            for(auto &row:transaction->digits)for(auto &value:row)
                value=generated%23==0?0:(std::int64_t(random()%256)-128)*(1LL<<23);
            for(auto &row:transaction->key)for(auto &component:row)for(auto &value:component)
                value={std::int64_t(random()%(1ULL<<25))-(1LL<<24),
                       std::int64_t(random()%(1ULL<<25))-(1LL<<24)};
            fpt::QuantizationStats stats;
            transaction->expected=ps::external_product(transaction->digits,transaction->key,schedule,stats,true);
            if(stats.overflows)throw std::runtime_error("external-product test input overflows model");
            transactions.push_back(std::move(transaction));++generated;
        }
        for(unsigned lane=0;lane<128;++lane) {
            const auto point=(input_phase%2)*128+lane;
            dut.digit_low[lane]=dut.input_valid?transactions.at(sent)->digits[input_phase/2][point]/(1LL<<23):0;
            dut.digit_high[lane]=dut.input_valid?transactions.at(sent)->digits[input_phase/2][point+256]/(1LL<<23):0;
        }
        dut.eval();
        if(!dut.reset) {
            KeyRequest request{bool(dut.key_request),key_transaction,key_phase};
            if(dut.key_request) {
                if(dut.key_row!=key_phase/2 || dut.key_half!=key_phase%2 ||
                   dut.key_context!=key_transaction%12 || dut.key_step!=(key_transaction/12)%586)
                    throw std::runtime_error("consumer key schedule mismatch");
                if(++key_phase==12){key_phase=0;++key_transaction;}
            }
            key_pipe.push_back(request);
            KeyRequest response{false,0,0};
            if(key_pipe.size()>TEST_KEY_READ_LATENCY){response=key_pipe.front();key_pipe.pop_front();}
            if(response.valid) {
                const auto &transaction=*transactions.at(response.transaction);
                for(unsigned c=0;c<3;++c)for(unsigned lane=0;lane<128;++lane) {
                    const unsigned index=(response.phase%2)*128+lane;
                    const unsigned point=index/4+(index%4)*64;
                    const auto packed=pk::encode(transaction.key[response.phase/2][c][point]);
                    std::uint64_t word=0;
                    for(unsigned byte=0;byte<7;++byte)word|=std::uint64_t(packed[byte])<<(8*byte);
                    dut.key_real[c][lane]=word&((1ULL<<28)-1);dut.key_imag[c][lane]=word>>28;
                }
                dut.key_valid=1;
            }
        }
        dut.eval();
        if(!dut.reset) {
            if(dut.fault)throw std::runtime_error("external-product pipeline fault on legal schedule");
            if(dut.output_valid) {
                if(expected.empty() || expected.front()!=retired)
                    throw std::runtime_error("unexpected external-product output");
                if(dut.output_component!=output_phase/4 || dut.output_chunk!=output_phase%4 ||
                   dut.output_context!=retired%12 || dut.output_step!=(retired/12)%586)
                    throw std::runtime_error("external-product output ordering mismatch");
                const auto &transaction=*transactions.at(retired);
                if(output_phase==0) {
                    const auto latency=cycle-transaction.start_cycle;
                    min_latency=std::min(min_latency,latency);max_latency=std::max(max_latency,latency);
                }
                for(unsigned lane=0;lane<64;++lane) {
                    const unsigned index=(output_phase%4)*64+lane;
                    const unsigned point=index/4+(index%4)*64;
                    const auto &polynomial=transaction.expected[output_phase/4];
                    const auto low=std::uint32_t(polynomial[point])<<15;
                    const auto high=std::uint32_t(polynomial[point+256])<<15;
                    if(dut.output_low[lane]!=low || dut.output_high[lane]!=high) {
                        std::cerr<<"cycle="<<cycle<<" transaction="<<retired<<" component="<<output_phase/4
                                 <<" point="<<point<<'\n';
                        throw std::runtime_error("external-product Torus result differs from model");
                    }
                    checked+=2;
                }
                if(++output_phase==12){output_phase=0;expected.pop_front();++retired;++completed;}
            }
            if(dut.input_valid && ++input_phase==12){input_phase=0;expected.push_back(sent++);}
        }
        dut.clk=1;dut.eval();
    }
    if(!expected.empty() || input_phase || output_phase || key_phase || key_transaction!=sent || completed<200)
        throw std::runtime_error("external-product pipeline drain incomplete");
    if(min_latency!=max_latency || max_latency+11>=144)
        throw std::runtime_error("external-product latency exceeds fixed feedback envelope");
    dut.final();std::cout<<"PASS external-product transactions="<<completed<<" coefficients="<<checked
                         <<" first_output_latency="<<min_latency<<" last_output_latency="<<max_latency+11<<'\n';
}

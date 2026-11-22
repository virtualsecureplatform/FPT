#ifdef TEST_KEYED
#include "VPaperStreamingKeyedBlindRotate.h"
using Device=VPaperStreamingKeyedBlindRotate;
#elif defined(TEST_BUFFERED)
#include "VPaperStreamingBufferedBlindRotate.h"
using Device=VPaperStreamingBufferedBlindRotate;
#else
#include "VPaperStreamingBlindRotate.h"
using Device=VPaperStreamingBlindRotate;
#endif
#include "fpt/paper_streaming_cmux.hpp"
#include "fpt/paper_key_packing.hpp"
#include <filesystem>
#include <fstream>
#include <array>
#include <cstdint>
#include <deque>
#include <iostream>
#include <memory>
#include <random>
#include <stdexcept>
#include <vector>
#ifndef TEST_STEPS
#define TEST_STEPS 3
#endif
namespace ps=fpt::paper_streaming;
static std::uint32_t read_word(std::istream &file)
{
    std::uint32_t value=0;
    for(unsigned byte=0;byte<4;++byte){const auto v=file.get();if(v<0)throw std::runtime_error("truncated encrypted fixture");value|=std::uint32_t(v)<<(8*byte);}
    return value;
}
int main(int argc,char **argv)
{
    constexpr unsigned steps=TEST_STEPS;
    if(argc>2)throw std::runtime_error("usage: RTL-test [BATCH_FIXTURE]");
    const std::filesystem::path fixture=argc==2?argv[1]:"";
    std::ifstream key_file;
    if(!fixture.empty()) {
        if(steps!=586)throw std::runtime_error("encrypted fixtures require the full 586-step geometry");
        key_file.open(fixture.parent_path()/"key.bin",std::ios::binary);
        for(auto expected:{0x4650544bu,1u,586u,512u,2u,28u,7u,18902016u})
            if(read_word(key_file)!=expected)throw std::runtime_error("encrypted key fixture header mismatch");
    }
    Device dut;
    std::mt19937_64 random(257);
    std::vector<std::unique_ptr<ps::KeyRows>> keys;
    for(unsigned step=0;step<steps;++step) {
        auto key=std::make_unique<ps::KeyRows>();
        for(auto &row:*key)for(auto &component:row)for(auto &value:component) {
            if(fixture.empty())value=step%23==0?fpt::FixedComplex{0,0}:fpt::FixedComplex{
                std::int64_t(random()%(1ULL<<25))-(1LL<<24),std::int64_t(random()%(1ULL<<25))-(1LL<<24)};
            else {
                fpt::paper_key_packing::Encoded bytes;
                for(auto &byte:bytes){const auto v=key_file.get();if(v<0)throw std::runtime_error("truncated encrypted key");byte=v;}
                value=fpt::paper_key_packing::decode(bytes);
            }
        }
        keys.push_back(std::move(key));
    }
    ps::TransformSchedule forward{{9,23},26,{}},inverse{{20,12},26,{}};
    forward.stage_data=std::array<fpt::FixedFormat,4>{{{11,21},{13,19},{15,17},{17,15}}};
    inverse.stage_data=std::array<fpt::FixedFormat,4>{{{20,12},{21,11},{22,10},{23,9}}};
    forward.leg_guard_bits=4;inverse.leg_guard_bits=4;
    const ps::ExternalSchedule schedule{forward,inverse,{{17,15},{7,21},{26,14}}};
    unsigned key_beats=0,cycles=0,checked=0;
    dut.start=0;dut.exponent_load_valid=0;dut.body_load_valid=0;
#ifndef TEST_KEYED
    dut.key_valid=0;
#endif
#ifndef TEST_BUFFERED
    dut.debug_read_valid=0;
#endif
#ifdef TEST_BUFFERED
    dut.start_bank=0;
#ifndef TEST_KEYED
    dut.read_valid=0;dut.release_valid=0;
#endif
    std::deque<std::pair<bool,std::uint32_t>> replies;
    std::uint32_t expected_read_word=0;
    unsigned output_checked=0;
#ifdef TEST_STREAMED
    dut.stream_start=0;dut.stream_bank=0;dut.output_accept=0;
    std::array<std::uint32_t,12300> stream_expected;
    unsigned stream_index=0;
#endif
#endif
#ifdef TEST_KEYED
    std::array<unsigned,4> key_stream_ordinal{};
    std::array<bool,4> key_pending{};
#endif
    auto tick=[&] {
        dut.clk=0;
#ifndef TEST_KEYED
        dut.key_valid=0;
#else
        dut.key_stream_valid=0;dut.key_stream_last=0;
        if(!dut.reset)for(unsigned port=0;port<4;++port) {
            if(!key_pending[port] && random()%20!=0)key_pending[port]=true;
            const unsigned groups=port==3?10:11;
            const unsigned ordinal=key_stream_ordinal[port],step=ordinal/(12*groups);
            const unsigned slot=(ordinal/groups)%12,group=port+4*(ordinal%groups);
            for(unsigned w=0;w<16;++w)dut.key_stream_data[port][w]=0;
            for(unsigned item=0;item<9;++item) {
                const unsigned lane=group*9+item;
                if(lane>=384)continue;
                const unsigned native=(slot%2)*128+lane%128,point=native/4+(native%4)*64;
                const auto value=(*keys[step])[slot/2][lane/128][point];
                const std::uint64_t packed=(std::uint64_t(value.real)&0xfffffff)|((std::uint64_t(value.imag)&0xfffffff)<<28);
                for(unsigned bit=0;bit<56;++bit)if((packed>>bit)&1)
                    dut.key_stream_data[port][(item*56+bit)/32]|=1u<<((item*56+bit)%32);
            }
            if(key_pending[port])dut.key_stream_valid|=1u<<port;
            if(ordinal+1==steps*12*groups)dut.key_stream_last|=1u<<port;
        }
#endif
        dut.eval();
        if(!dut.reset && dut.key_request) {
            const unsigned step=key_beats/144,context=(key_beats/12)%12,row=(key_beats%12)/2,half=key_beats%2;
            if(step>=steps || dut.key_step!=step || dut.key_context!=context || dut.key_row!=row || dut.key_half!=half)
                throw std::runtime_error("feedback core key schedule mismatch");
#ifndef TEST_KEYED
            for(unsigned c=0;c<3;++c)for(unsigned lane=0;lane<128;++lane) {
                const unsigned index=half*128+lane,point=index/4+(index%4)*64;
                const auto value=(*keys[step])[row][c][point];
                dut.key_real[c][lane]=value.real;dut.key_imag[c][lane]=value.imag;
            }
            dut.key_valid=1;
#endif
            ++key_beats;
        }
        dut.eval();
#ifdef TEST_STREAMED
        if(!dut.reset && dut.output_valid && dut.output_accept) {
            const bool last=stream_index+16>=12300;
            if(bool(dut.output_last)!=last || dut.output_keep!=(last?0x0000ffffffffffffULL:~0ULL))
                throw std::runtime_error("feedback stream byte enable mismatch");
            for(unsigned i=0;i<(last?12u:16u);++i) {
                if(dut.output_data[i]!=stream_expected.at(stream_index++))
                    throw std::runtime_error("feedback stream sample mismatch");
                ++output_checked;
            }
        }
#endif
#ifdef TEST_KEYED
        const unsigned key_accepted=dut.key_stream_valid&dut.key_stream_ready;
#endif
        dut.clk=1;dut.eval();++cycles;
#ifdef TEST_KEYED
        if(dut.reset){key_stream_ordinal={};key_pending={};}
        else for(unsigned port=0;port<4;++port)if((key_accepted>>port)&1) {
            key_stream_ordinal[port]=(key_stream_ordinal[port]+1)%(steps*12*(port==3?10:11));
            key_pending[port]=false;
        }
#endif
#if defined(TEST_BUFFERED) && !defined(TEST_STREAMED)
        if(dut.reset)replies.clear();else replies.emplace_back(dut.read_valid,expected_read_word);
        std::pair<bool,std::uint32_t> reply{false,0};
        if(replies.size()>=3){reply=replies.front();replies.pop_front();}
        if(bool(dut.response_valid)!=reply.first || (reply.first && dut.response_word!=reply.second))
            throw std::runtime_error("buffered feedback sample extraction mismatch");
        output_checked+=reply.first;
#endif
        if(!dut.reset && dut.fault)throw std::runtime_error("blind-rotation feedback core fault");
    };
    auto reset=[&] {
        dut.reset=1;dut.start=0;dut.exponent_load_valid=0;dut.body_load_valid=0;
#ifndef TEST_BUFFERED
        dut.debug_read_valid=0;
#else
 #ifndef TEST_KEYED
        dut.read_valid=0;dut.release_valid=0;
#endif
#ifdef TEST_STREAMED
        dut.stream_start=0;dut.output_accept=0;stream_index=0;
#endif
#endif
        tick();dut.reset=0;key_beats=0;
    };
    reset();
    std::array<ps::Ciphertext,12> initial,expected;
    std::array<std::array<unsigned,steps>,12> exponents;
    for(unsigned batch=0;batch<2;++batch) {
        if(fixture.empty()) {
        for(unsigned context=0;context<12;++context) {
            initial[context]={};
            for(auto &word:initial[context][2])word=std::uint32_t(random()&0x1ffff)<<15;
            for(unsigned step=0;step<steps;++step)
                exponents[context][step]=(context+step)%19==0?0:unsigned(random()%1024);
        }
        expected=initial;
        fpt::QuantizationStats stats;
        for(unsigned step=0;step<steps;++step)for(unsigned context=0;context<12;++context)
            expected[context]=ps::cmux(expected[context],exponents[context][step],*keys[step],schedule,stats);
        if(stats.overflows)throw std::runtime_error("feedback test data overflows arithmetic model");
        } else {
            std::ifstream file(fixture,std::ios::binary);
            for(auto value:{0x46505442u,1u,586u,12u,26u,4u,40u})
                if(read_word(file)!=value)throw std::runtime_error("encrypted batch fixture header mismatch");
            if(read_word(file)>1)throw std::runtime_error("unsupported encrypted fixture lookup");
            for(auto &state:initial)for(auto &component:state)for(auto &value:component)value=read_word(file);
            for(auto &context:exponents)for(auto &value:context)value=read_word(file);
            for(auto &state:expected)for(auto &component:state)for(auto &value:component)value=read_word(file);
            std::array<std::array<std::uint32_t,587>,12> raw;
            std::array<std::uint32_t,512> lookup;
            for(auto &input:raw)for(auto &value:input)value=read_word(file);
            for(auto &value:lookup)value=read_word(file);
            if(file.get()!=std::char_traits<char>::eof())throw std::runtime_error("encrypted fixture has trailing data");
            auto mod_switch=[](std::uint32_t value){return ((std::uint64_t(value)+(1u<<21))>>22)&1023;};
            for(unsigned context=0;context<12;++context) {
                for(unsigned step=0;step<586;++step)if(exponents[context][step]!=mod_switch(raw[context][step]))
                    throw std::runtime_error("encrypted fixture exponent is not the public-input modulus switch");
                for(unsigned point=0;point<512;++point) {
                    const unsigned source=(point+mod_switch(raw[context][586]))%1024;
                    const auto value=source>=512?0u-lookup[source%512]:lookup[source];
                    if(initial[context][0][point] || initial[context][1][point] || initial[context][2][point]!=value || (value&32767))
                        throw std::runtime_error("encrypted fixture initialization is not the public-input rotation");
                }
            }
        }
        auto load=[&] {
#ifndef TEST_BUFFERED
            dut.debug_read_valid=0;
#endif
            dut.start=0;key_beats=0;
            for(unsigned context=0;context<12;++context)for(unsigned step=0;step<steps;++step) {
                dut.exponent_load_valid=1;dut.exponent_load_context=context;
                dut.exponent_load_step=step;dut.exponent_load_value=exponents[context][step];tick();
            }
            dut.exponent_load_valid=0;
            for(unsigned context=0;context<12;++context)for(unsigned chunk=0;chunk<4;++chunk) {
                dut.body_load_valid=1;dut.body_load_context=context;dut.body_load_chunk=chunk;
                for(unsigned lane=0;lane<64;++lane) {
                    const unsigned index=chunk*64+lane,point=index/4+(index%4)*64;
                    dut.body_load_low[lane]=initial[context][2][point]>>15;
                    dut.body_load_high[lane]=initial[context][2][point+256]>>15;
                }
                tick();
            }
            dut.body_load_valid=0;dut.clk=0;dut.eval();
#ifdef TEST_KEYED
            unsigned prefetch_wait=0;
            while(!dut.start_ready && prefetch_wait<10000){tick();++prefetch_wait;}
#endif
            if(!dut.start_ready)throw std::runtime_error("feedback core did not admit initialized batch");
        };
        load();
        if(batch==1 && steps>=3) {
            dut.start=1;tick();dut.start=0;
            for(unsigned i=0;i<300;++i)tick();
            reset();load(); // Recover after in-flight writes without clearing RAM payload.
        }
#ifdef TEST_BUFFERED
        dut.start_bank=batch%2;
#endif
        const auto start_cycle=cycles;
        dut.start=1;tick();dut.start=0;
        unsigned elapsed=0;
        while(!dut.done && elapsed<steps*144+256){tick();++elapsed;}
        if(!dut.done || dut.busy || key_beats!=steps*144)throw std::runtime_error("feedback core completion/drain mismatch");
#ifdef TEST_BUFFERED
        if(!(dut.output_ready&(1u<<(batch%2))))throw std::runtime_error("sample output not ready at completion");
#ifdef TEST_STREAMED
        stream_index=0;
        for(unsigned ordinal=0;ordinal<12300;++ordinal) {
            const unsigned context=ordinal/1025,word=ordinal%1025,point=word%512;
            if(word==1024)stream_expected[ordinal]=expected[context][2][0];
            else {
                const auto value=expected[context][word/512][point==0?0:512-point];
                stream_expected[ordinal]=point==0?value:0u-value;
            }
        }
        dut.stream_bank=batch%2;dut.clk=0;dut.eval();
        if(!dut.stream_start_ready)throw std::runtime_error("feedback DMA did not admit sample bank");
        dut.stream_start=1;tick();dut.stream_start=0;
        unsigned stream_cycles=0;
        while(!dut.stream_done && stream_cycles<30000) {
            dut.output_accept=stream_cycles%509<100?0:random()%3!=0;tick();++stream_cycles;
        }
        if(!dut.stream_done || dut.stream_busy || stream_index!=12300)throw std::runtime_error("feedback DMA did not finish");
        // The release pulse is consumed by the sample buffer one cycle later.
        tick();
        if(!(dut.output_available&(1u<<(batch%2))))throw std::runtime_error("feedback DMA did not release sample bank");
#else
        for(unsigned ordinal=0;ordinal<12300;++ordinal) {
            const unsigned context=ordinal/1025,word=ordinal%1025;
            dut.read_valid=1;dut.read_bank=batch%2;dut.read_context=context;dut.read_word=word;
            if(word==1024)expected_read_word=expected[context][2][0];
            else {
                const unsigned point=word%512;
                const auto value=expected[context][word/512][point==0?0:512-point];
                expected_read_word=point==0?value:0u-value;
            }
            tick();
            if(random()%4==0){dut.read_valid=0;tick();}
        }
        dut.read_valid=0;for(unsigned i=0;i<4;++i)tick();
        dut.release_valid=1;dut.release_bank=batch%2;tick();dut.release_valid=0;
 #endif
        checked=output_checked;
#else
        for(unsigned context=0;context<12;++context)for(unsigned c=0;c<3;++c) {
            dut.clk=0;dut.debug_read_valid=1;dut.debug_context=context;dut.debug_component=c;dut.eval();
            if(!dut.debug_ready)throw std::runtime_error("completed ciphertext is not readable");
            for(unsigned point=0;point<512;++point) {
                if((std::uint32_t(dut.debug_code[point])<<15)!=expected[context][c][point]) {
                    std::cerr<<"batch="<<batch<<" context="<<context<<" component="<<c<<" point="<<point<<'\n';
                    throw std::runtime_error("feedback ciphertext differs from full CMUX model");
                }
                ++checked;
            }
        }
        dut.debug_read_valid=0;
#endif
        std::cout<<"batch="<<batch<<" steps="<<steps<<" cycles="<<cycles-start_cycle<<" key_beats="<<key_beats<<std::endl;
    }
    dut.final();std::cout<<"fixture="<<(fixture.empty()?"synthetic":fixture.string())<<'\n';
    std::cout<<"PASS feedback steps="<<steps<<" contexts=12 batches=2 coefficients="<<checked<<'\n';
}

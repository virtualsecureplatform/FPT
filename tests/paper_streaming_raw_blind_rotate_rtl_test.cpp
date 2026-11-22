#include "VPaperStreamingRawBlindRotate.h"
#include "fpt/paper_streaming_bootstrap.hpp"
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
#define TEST_STEPS 128
#endif
namespace ps=fpt::paper_streaming;
constexpr unsigned steps=TEST_STEPS,batches=3;
using Inputs=std::array<std::array<std::uint32_t,steps+1>,12>;
static unsigned mod_switch(std::uint32_t value)
{return ((std::uint64_t(value)+(1u<<21))>>22)&1023;}
static std::uint32_t lookup_round(std::uint32_t value)
{
    const unsigned q=value>>15,r=value&32767;
    return ((q+(r>16384 || (r==16384 && q%2)))&0x1ffff)<<15;
}
static std::uint32_t read_word(std::istream &file)
{
    std::uint32_t result=0;
    for(unsigned byte=0;byte<4;++byte){const auto v=file.get();if(v<0)throw std::runtime_error("truncated raw encrypted fixture");result|=std::uint32_t(v)<<(8*byte);}
    return result;
}
int main(int argc,char **argv)
{
    if(argc>2)throw std::runtime_error("usage: raw-RTL-test [BATCH_FIXTURE]");
    const std::filesystem::path fixture=argc==2?argv[1]:"";
    std::ifstream key_file;
    if(!fixture.empty()) {
        if(steps!=586)throw std::runtime_error("raw encrypted fixture requires 586 steps");
        key_file.open(fixture.parent_path()/"key.bin",std::ios::binary);
        for(auto value:{0x4650544bu,1u,586u,512u,2u,28u,7u,18902016u})
            if(read_word(key_file)!=value)throw std::runtime_error("raw encrypted key header mismatch");
    }
    VPaperStreamingRawBlindRotate dut;
    std::mt19937_64 random(307);
    std::vector<std::unique_ptr<ps::KeyRows>> keys;
    for(unsigned step=0;step<steps;++step) {
        auto key=std::make_unique<ps::KeyRows>();
        for(auto &row:*key)for(auto &component:row)for(auto &value:component) {
            if(fixture.empty())value={std::int64_t(random()%(1ULL<<25))-(1LL<<24),std::int64_t(random()%(1ULL<<25))-(1LL<<24)};
            else {
                fpt::paper_key_packing::Encoded bytes;
                for(auto &byte:bytes){const auto v=key_file.get();if(v<0)throw std::runtime_error("truncated raw encrypted key");byte=v;}
                value=fpt::paper_key_packing::decode(bytes);
            }
        }
        keys.push_back(std::move(key));
    }
    std::array<Inputs,batches> inputs;
    std::array<std::array<std::uint32_t,512>,batches> lookup;
    std::array<std::array<ps::OutputTlwe,12>,batches> expected;
    ps::TransformSchedule forward{{9,23},26,{}},inverse{{20,12},26,{}};
    forward.stage_data=std::array<fpt::FixedFormat,4>{{{11,21},{13,19},{15,17},{17,15}}};
    inverse.stage_data=std::array<fpt::FixedFormat,4>{{{20,12},{21,11},{22,10},{23,9}}};
    forward.leg_guard_bits=4;inverse.leg_guard_bits=4;
    const ps::ExternalSchedule schedule{forward,inverse,{{17,15},{7,21},{26,14}}};
    for(unsigned frame=0;frame<batches;++frame) {
        if(!fixture.empty()) {
            const auto path=fixture.parent_path()/(frame%2?"batch-ramp8.bin":"batch-bool.bin");
            std::ifstream file(path,std::ios::binary);
            for(auto value:{0x46505442u,1u,586u,12u,26u,4u,40u})
                if(read_word(file)!=value)throw std::runtime_error("raw encrypted batch header mismatch");
            if(read_word(file)!=frame%2)throw std::runtime_error("raw encrypted lookup type mismatch");
            std::array<ps::Ciphertext,12> initial,golden;
            for(auto &state:initial)for(auto &component:state)for(auto &value:component)value=read_word(file);
            for(unsigned i=0;i<12*586;++i)(void)read_word(file);
            for(auto &state:golden)for(auto &component:state)for(auto &value:component)value=read_word(file);
            for(auto &input:inputs[frame])for(auto &value:input)value=read_word(file);
            for(auto &value:lookup[frame])value=read_word(file);
            if(file.get()!=std::char_traits<char>::eof())throw std::runtime_error("raw encrypted fixture has trailing bytes");
            for(unsigned context=0;context<12;++context) {
                for(unsigned point=0;point<512;++point) {
                    const unsigned source=(point+mod_switch(inputs[frame][context][steps]))%1024;
                    const auto q=lookup_round(lookup[frame][source%512]);
                    if(initial[context][0][point] || initial[context][1][point] || initial[context][2][point]!=(source>=512?0u-q:q))
                        throw std::runtime_error("raw encrypted initial body is not the public-input rotation");
                }
                expected[frame][context]=ps::sample_extract(golden[context]);
            }
            std::cout<<"encrypted_reference_ready frame="<<frame<<std::endl;
            continue;
        }
        for(auto &value:lookup[frame])value=random();
        for(auto &input:inputs[frame])for(auto &value:input)value=random();
        for(unsigned context=0;context<12;++context) {
            ps::Ciphertext state{};
            for(unsigned point=0;point<512;++point) {
                const unsigned source=(point+mod_switch(inputs[frame][context][steps]))%1024;
                const auto value=lookup_round(lookup[frame][source%512]);
                state[2][point]=source>=512?0u-value:value;
            }
            fpt::QuantizationStats stats;
            for(unsigned step=0;step<steps;++step)
                state=ps::cmux(state,mod_switch(inputs[frame][context][step]),*keys[step],schedule,stats);
            if(stats.overflows)throw std::runtime_error("raw input test overflows arithmetic model");
            expected[frame][context]=ps::sample_extract(state);
        }
    }
    unsigned input_frame=0,input_index=0,started=0,completed=0,streamed=0,stream_index=0,key_beats=0,checked=0;
    bool input_lookup=true,recovered=false;
    int streaming=-1;
    std::array<unsigned,4> key_ordinal{};
    std::array<bool,4> key_pending{};
    std::array<unsigned,batches> start_cycle{};
    std::deque<unsigned> output_queue;
    auto reset=[&] {
        dut.reset=1;dut.start=0;dut.stream_start=0;dut.lookup_valid=0;dut.tlwe_valid=0;dut.key_stream_valid=0;dut.output_accept=0;
        dut.clk=0;dut.eval();dut.clk=1;dut.eval();dut.reset=0;
        input_frame=0;input_index=0;input_lookup=true;started=0;completed=0;streamed=0;streaming=-1;stream_index=0;key_beats=0;checked=0;
        key_ordinal={};key_pending={};output_queue.clear();start_cycle={};
    };
    reset();
    unsigned cycle=0;
    while(streamed<batches && cycle<steps*144*batches+80000) {
        if(!recovered && started==1 && cycle-start_cycle[0]==300){reset();recovered=true;}
        dut.clk=0;dut.start=0;dut.stream_start=0;dut.start_bank=started%2;
        dut.lookup_valid=0;dut.tlwe_valid=0;
        if(input_frame<batches) {
            if(input_lookup) {
                dut.lookup_valid=random()%5!=0;dut.lookup_index=input_index;dut.lookup_value=lookup[input_frame][input_index];
            } else {
                dut.tlwe_valid=random()%5!=0;dut.tlwe_context=input_index/(steps+1);dut.tlwe_word=input_index%(steps+1);
                dut.tlwe_value=inputs[input_frame][dut.tlwe_context][dut.tlwe_word];
            }
        }
        dut.key_stream_valid=0;dut.key_stream_last=0;
        for(unsigned port=0;port<4;++port) {
            const unsigned groups=port==3?10:11,ordinal=key_ordinal[port];
            if(!key_pending[port] && random()%20!=0) {
                const unsigned step=ordinal/(12*groups),slot=(ordinal/groups)%12,group=port+4*(ordinal%groups);
                for(unsigned w=0;w<16;++w)dut.key_stream_data[port][w]=0;
                for(unsigned item=0;item<9;++item) {
                    const unsigned lane=group*9+item;if(lane>=384)continue;
                    const unsigned native=(slot%2)*128+lane%128,point=native/4+(native%4)*64;
                    const auto value=(*keys[step])[slot/2][lane/128][point];
                    const std::uint64_t packed=(std::uint64_t(value.real)&0xfffffff)|((std::uint64_t(value.imag)&0xfffffff)<<28);
                    for(unsigned bit=0;bit<56;++bit)if((packed>>bit)&1)
                        dut.key_stream_data[port][(item*56+bit)/32]|=1u<<((item*56+bit)%32);
                }
                key_pending[port]=true;
            }
            if(key_pending[port])dut.key_stream_valid|=1u<<port;
            if(ordinal+1==steps*12*groups)dut.key_stream_last|=1u<<port;
        }
        dut.output_accept=cycle%509<70?0:random()%4!=0;
        if(!output_queue.empty())dut.stream_bank=output_queue.front()%2;
        dut.eval();
        if(started<batches && dut.start_ready) {
            dut.start=1;start_cycle[started]=cycle;++started;key_beats=0;
        }
        if(streaming<0 && !output_queue.empty() && dut.stream_start_ready) {
            dut.stream_start=1;streaming=output_queue.front();output_queue.pop_front();stream_index=0;
        }
        dut.eval();
        const unsigned key_accepted=dut.key_stream_valid&dut.key_stream_ready;
        const bool input_accepted=input_lookup?(dut.lookup_valid && dut.lookup_ready):(dut.tlwe_valid && dut.tlwe_ready);
        if(dut.key_request) {
            if(dut.key_step!=key_beats/144 || dut.key_context!=(key_beats/12)%12 || dut.key_row!=(key_beats%12)/2 || dut.key_half!=key_beats%2)
                throw std::runtime_error("raw engine key consumer sequence mismatch");
            ++key_beats;
        }
        if(dut.output_valid && dut.output_accept) {
            if(streaming<0)throw std::runtime_error("raw engine output has no batch owner");
            const bool last=stream_index+16>=12300;
            if(bool(dut.output_last)!=last || dut.output_keep!=(last?0x0000ffffffffffffULL:~0ULL))
                throw std::runtime_error("raw engine output framing mismatch");
            for(unsigned lane=0;lane<(last?12u:16u);++lane) {
                const auto value=expected[streaming][stream_index/1025][stream_index%1025];
                if(dut.output_data[lane]!=value) {
                    std::cerr<<"frame="<<streaming<<" word="<<stream_index<<" got="<<dut.output_data[lane]<<" expected="<<value<<" cycle="<<cycle<<" starts="<<started<<" completed="<<completed<<'\n';
                    throw std::runtime_error("raw-input prepared/overlapped ciphertext differs from model");
                }
                ++stream_index;++checked;
            }
        }
        dut.clk=1;dut.eval();++cycle;
        if(dut.fault)throw std::runtime_error("raw engine protocol/arithmetic fault");
        for(unsigned port=0;port<4;++port)if((key_accepted>>port)&1) {
            key_ordinal[port]=(key_ordinal[port]+1)%(steps*12*(port==3?10:11));key_pending[port]=false;
        }
        if(input_accepted) {
            const unsigned count=input_lookup?512:12*(steps+1);
            if(++input_index==count){input_index=0;if(input_lookup)input_lookup=false;else {input_lookup=true;++input_frame;}}
        }
        if(dut.done) {
            if(key_beats!=steps*144 || completed>=started)throw std::runtime_error("raw engine completion/key count mismatch");
            std::cout<<"compute_done frame="<<completed<<" cycle="<<cycle<<std::endl;
            output_queue.push_back(completed++);
        }
        if(dut.stream_done) {
            if(streaming!=int(streamed) || stream_index!=12300)throw std::runtime_error("raw engine DMA completion order mismatch");
            ++streamed;streaming=-1;
        }
    }
    if(streamed!=batches || completed!=batches || !recovered || checked!=36900)
        throw std::runtime_error("raw engine did not finish all overlapped batches");
    for(unsigned frame=1;frame<batches;++frame) {
        const auto period=start_cycle[frame]-start_cycle[frame-1];
        if(period>steps*144+128)throw std::runtime_error("raw input loading did not overlap the compute schedule");
        if constexpr(steps==586)if(period>84507)throw std::runtime_error("full raw batch period misses 28.4 PBS/ms at 200 MHz");
        std::cout<<"batch_period="<<period<<" steps="<<steps<<'\n';
    }
    dut.final();std::cout<<"fixture="<<(fixture.empty()?"synthetic":fixture.string())<<'\n';
    std::cout<<"PASS raw-input backend batches=3 words="<<checked<<" overlap/reset/loader-and-output-stalls\n";
}

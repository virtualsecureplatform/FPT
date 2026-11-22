#include "VPaperStreamingDmaEngine.h"
#include "fpt/paper_dma_fixture.hpp"
#include <deque>
#include <iostream>
#include <random>
namespace dma=fpt::paper_dma_layout;
struct Transfer {unsigned frame,offset,bytes;};
int main(int argc,char **argv)
{
    if(argc!=2 && argc!=3)throw std::runtime_error("encrypted fixture and optional distinct-key fixture required");
    const auto fixture=fpt::paper_dma_fixture::load_repeated(std::filesystem::path(argv[1]).parent_path(),3);
    const auto alternate=argc==3?fpt::paper_dma_fixture::load_repeated(std::filesystem::path(argv[2]).parent_path(),3):fpt::paper_dma_fixture::Data{};
    if(argc==3 && alternate.keys==fixture.keys)throw std::runtime_error("alternate DMA job must use a distinct encrypted key");
    VPaperStreamingDmaEngine dut;
    std::mt19937 random(8591);
    unsigned checked=0;
    auto edge=[&]{dut.clk=0;dut.eval();dut.clk=1;dut.eval();};
    auto reset=[&]{dut.reset=1;dut.start=0;dut.command_ready=0;dut.status_valid=0;dut.dma_error=0;
        dut.input_valid=0;dut.key_valid=0;dut.output_ready=0;edge();edge();dut.reset=0;};
    reset();
    auto run=[&](const fpt::paper_dma_fixture::Data &data,unsigned count,bool abort) {
        const auto inputs=dma::pack(data.frames);
        std::array<unsigned,6> sent{},received{};
        std::array<std::deque<Transfer>,6> transfers;
        std::array<std::deque<unsigned>,6> statuses;
        std::vector<std::uint8_t> output(count*dma::output_stride,0xa5);
        std::array<std::uint64_t,6> base{0x100000,0x10000000,0x11000000,0x12000000,0x13000000,0x200000};
        std::array<bool,5> presenting{};
        unsigned last_write_response=0,compute_seen=0;
        dut.batch_count=count;dut.input_pointer=base[0];dut.output_pointer=base[5];
        for(unsigned p=0;p<4;++p)dut.key_pointer[p]=base[p+1];
        dut.start=1;edge();dut.start=0;
        for(unsigned cycle=1;cycle<600000;++cycle) {
            dut.clk=0;dut.command_ready=0;dut.status_valid=0;dut.input_valid=0;dut.key_valid=0;
            dut.input_last=0;dut.key_last=0;dut.output_ready=!transfers[5].empty() && random()%5!=0;
            for(unsigned p=0;p<6;++p) {
                if(transfers[p].size()<2 && random()%4!=0)dut.command_ready|=1u<<p;
                dut.status_data[p]=0x80;
                if(!statuses[p].empty() && statuses[p].front()<=cycle) {
                    statuses[p].pop_front();dut.status_valid|=1u<<p;++received[p];
                    if(p==5)last_write_response=cycle;
                }
                if(p==5 || transfers[p].empty())continue;
                // Once asserted, valid, payload and TLAST remain stable until accepted.
                // Fixed compute consumes 132 beats per 144 clocks on ports
                // 0..2. Keep key-loader bubbles inside that bandwidth budget;
                // arbitrary input/output stalls remain legal independently.
                presenting[p]=presenting[p] || random()%(p==0?7:50)!=0;
                if(!presenting[p])continue;
                const auto &t=transfers[p].front();
                const auto &image=p==0?inputs:data.keys[p-1];
                const unsigned offset=t.offset+(p==0?t.frame*dma::input_stride:0);
                for(unsigned w=0;w<16;++w) {
                    const auto value=dma::get(image,offset+w*4);
                    if(p==0)dut.input_data[w]=value;else dut.key_data[p-1][w]=value;
                }
                const bool last=t.offset+64>=t.bytes;
                if(p==0){dut.input_valid=1;dut.input_last=last;}
                else {dut.key_valid|=1u<<(p-1);if(last)dut.key_last|=1u<<(p-1);}
            }
            dut.eval();
            for(unsigned p=0;p<6;++p) {
                if((dut.command_valid&dut.command_ready)>>p&1) {
                    const auto &cmd=dut.command_data[p];
                    const std::uint64_t address=std::uint64_t(cmd[1])|(std::uint64_t(cmd[2])<<32);
                    const unsigned bytes=p==0?dma::input_stride:p==5?dma::output_words*4:data.keys[p-1].size();
                    const auto expected=base[p]+(p==0?sent[p]*dma::input_stride:p==5?sent[p]*dma::output_stride:0);
                    if(sent[p]>=count || address!=expected || cmd[0]!=(0x40800000u|bytes) || cmd[3])
                        throw std::runtime_error("DMA descriptor mismatch");
                    transfers[p].push_back({sent[p]++,0,bytes});
                }
            }
            for(unsigned p=0;p<5;++p) {
                const bool accepted=p==0?dut.input_valid&&dut.input_ready:((dut.key_valid&dut.key_ready)>>(p-1)&1);
                if(!accepted)continue;
                auto &t=transfers[p].front();t.offset+=64;presenting[p]=false;
                if(t.offset>=t.bytes){transfers[p].pop_front();statuses[p].push_back(cycle+3);}
            }
            if(dut.output_valid && dut.output_ready) {
                auto &t=transfers[5].front();const unsigned remaining=t.bytes-t.offset;
                const unsigned valid=std::min(64u,remaining);
                const std::uint64_t keep=valid==64?~0ULL:(1ULL<<valid)-1;
                if(dut.output_keep!=keep || bool(dut.output_last)!=(remaining<=64))
                    throw std::runtime_error("DMA output byte enables/framing mismatch");
                for(unsigned b=0;b<valid;++b)output[t.frame*dma::output_stride+t.offset+b]=dut.output_data[b/4]>>(8*(b%4));
                t.offset+=valid;
                if(t.offset==t.bytes){transfers[5].pop_front();statuses[5].push_back(cycle+50);}
            }
            dut.clk=1;dut.eval();
            if(dut.computes_started && !compute_seen)compute_seen=cycle;
            if(abort && compute_seen && cycle>compute_seen+300){reset();return;}
            if(dut.status&0x1ff)throw std::runtime_error("DMA engine fault status="+std::to_string(dut.status));
            if(dut.done) {
                if(!last_write_response || cycle<last_write_response)throw std::runtime_error("completion precedes write status");
                for(unsigned p=0;p<6;++p)if(sent[p]!=count || received[p]!=count || !transfers[p].empty())
                    throw std::runtime_error("DMA channel failed to drain");
                const auto samples=dma::unpack(output,count);
                for(unsigned f=0;f<count;++f) {
                    if(samples[f]!=data.expected[f])throw std::runtime_error("encrypted DMA output mismatch frame="+std::to_string(f));
                    for(unsigned b=dma::output_words*4;b<dma::output_stride;++b)
                        if(output[f*dma::output_stride+b]!=0xa5)throw std::runtime_error("DMA wrote padding bytes");
                    checked+=dma::output_words;
                }
                std::cout<<"job frames="<<count<<" cycles="<<dut.cycles<<" first="<<dut.first_compute_cycle<<" last="<<dut.last_compute_cycle<<std::endl;
                return;
            }
        }
        throw std::runtime_error("DMA engine job timed out");
    };
    run(fixture,3,true);run(fixture,3,false);run(argc==3?alternate:fixture,2,false);
    dut.final();
    std::cout<<"PASS encrypted DMA boundary words="<<checked<<" distinct_inputs="<<(argc==3?48:24)
             <<" keys="<<(argc==3?2:1)<<" repeated_frames=5 stalls/reset/restart/status-drain\n";
}

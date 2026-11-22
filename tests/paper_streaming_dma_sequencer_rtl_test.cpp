#include "VPaperStreamingDmaSequencer.h"
#include <array>
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>
constexpr unsigned input_stride=30272,output_stride=49216;
int main()
{
    VPaperStreamingDmaSequencer dut;
    std::mt19937 random(331);
    unsigned checked=0;
    auto edge=[&] {dut.clk=0;dut.eval();dut.clk=1;dut.eval();};
    auto reset=[&] {
        dut.reset=1;dut.start=0;dut.status_valid=0;dut.dma_error=0;dut.input_frame_done=0;dut.key_frame_done=0;
        dut.compute_done=0;dut.stream_done=0;dut.compute_ready=0;dut.stream_ready=0;dut.command_ready=0;
        edge();dut.reset=0;
    };
    reset();
    auto run=[&](unsigned count,bool abort=false) {
        std::array<unsigned,6> sent{},received{};
        std::array<std::deque<unsigned>,6> statuses;
        std::array<std::deque<unsigned>,5> data;
        std::array<unsigned,4> key_frames{};
        unsigned input_frames=0,started=0,computed=0,streamed=0,output_frames=0;
        unsigned compute_due=0,stream_due=0,last_write_response=0;
        bool computing=false,streaming=false;
        std::array<std::array<std::uint32_t,4>,6> held{};
        unsigned held_mask=0;
        const std::uint64_t input_base=0x100000,output_base=0x400000;
        std::array<std::uint64_t,4> key_base;
        dut.batch_count=count;dut.input_pointer=input_base;dut.output_pointer=output_base;
        for(unsigned port=0;port<4;++port){key_base[port]=0x10000000ULL+port*0x1000000ULL;dut.key_pointer[port]=key_base[port];}
        dut.start=1;edge();dut.start=0;
        for(unsigned cycle=1;cycle<2000;++cycle) {
            dut.clk=0;
            // A caller may edit the next job's arguments while this job runs.
            dut.batch_count=99;dut.input_pointer=0xdead0000;dut.output_pointer=0xbeef0000;
            for(auto &pointer:dut.key_pointer)pointer=0xfeed0000;
            dut.command_ready=0;dut.status_valid=0;dut.input_frame_done=0;dut.key_frame_done=0;
            dut.compute_done=0;dut.stream_done=0;
            for(unsigned port=0;port<6;++port) {
                if(random()%3!=0)dut.command_ready|=1u<<port;
                dut.status_data[port]=0x80;
                if(!statuses[port].empty() && statuses[port].front()<=cycle) {
                    statuses[port].pop_front();dut.status_valid|=1u<<port;++received[port];
                    if(port==5)last_write_response=cycle;
                }
                if(port<5 && !data[port].empty() && data[port].front()<=cycle) {
                    data[port].pop_front();
                    if(port==0){dut.input_frame_done=1;++input_frames;}
                    else {dut.key_frame_done|=1u<<(port-1);++key_frames[port-1];}
                }
            }
            if(computing && compute_due==cycle){dut.compute_done=1;computing=false;++computed;}
            if(streaming && stream_due==cycle) {
                dut.stream_done=1;streaming=false;++output_frames;
                statuses[5].push_back(cycle+(output_frames==count?50:4));
            }
            bool keys_ready=true;for(auto frames:key_frames)keys_ready&=frames>started;
            dut.compute_ready=!computing && input_frames>started && keys_ready;
            dut.stream_ready=!streaming;
            dut.eval();
            if(held_mask)for(unsigned port=0;port<6;++port)if((held_mask>>port)&1) {
                if(!((dut.command_valid>>port)&1))throw std::runtime_error("DMA command valid dropped under backpressure");
                for(unsigned word=0;word<4;++word)if(dut.command_data[port][word]!=held[port][word])
                    throw std::runtime_error("DMA command changed under backpressure");
            }
            held_mask=dut.command_valid&~dut.command_ready;
            for(unsigned port=0;port<6;++port) {
                if((held_mask>>port)&1)for(unsigned word=0;word<4;++word)held[port][word]=dut.command_data[port][word];
                if(!((dut.command_valid&dut.command_ready)>>port&1))continue;
                const auto &cmd=dut.command_data[port];
                const std::uint64_t address=std::uint64_t(cmd[1])|(std::uint64_t(cmd[2])<<32);
                const std::uint64_t expected_address=port==0?input_base+sent[port]*input_stride:
                    port==5?output_base+sent[port]*output_stride:key_base[port-1];
                const unsigned bytes=port==0?input_stride:port==5?49200:(port==4?120:132)*586*64;
                if(address!=expected_address || cmd[0]!=(0x40800000u|bytes) || cmd[3]!=0 || sent[port]>=count)
                    throw std::runtime_error("DMA command address/length/framing mismatch");
                ++sent[port];++checked;
                if(port<5) {
                    unsigned due=cycle+4+random()%8;
                    if(!data[port].empty())due=std::max(due,data[port].back()+1);
                    data[port].push_back(due);statuses[port].push_back(due+2);
                }
            }
            if(dut.compute_start) {
                if(computing || dut.compute_bank!=started%2)throw std::runtime_error("DMA compute scheduler ownership mismatch");
                computing=true;compute_due=cycle+40;++started;
            }
            if(dut.stream_start) {
                if(streaming || dut.stream_bank!=streamed%2)throw std::runtime_error("DMA output scheduler ownership mismatch");
                streaming=true;stream_due=cycle+20;++streamed;
            }
            dut.clk=1;dut.eval();
            if(abort && cycle==25){reset();return;}
            if(dut.errors)throw std::runtime_error("DMA sequencer fault on legal status/stream schedule");
            if(dut.done) {
                if(computed!=count || output_frames!=count || started!=count || !dut.idle || dut.busy || last_write_response==0 || cycle<last_write_response)
                    throw std::runtime_error("DMA job completed before final write response");
                for(unsigned port=0;port<6;++port)if(sent[port]!=count || received[port]!=count)
                    throw std::runtime_error("DMA job channel count mismatch");
                if(dut.computes_started!=count || dut.computes_done!=count || dut.streams_done!=count || dut.input_frames!=count)
                    throw std::runtime_error("DMA completion counters mismatch");
                return;
            }
        }
        throw std::runtime_error("DMA sequencer job timeout");
    };
    run(3);run(2);run(3,true);run(2);
    dut.batch_count=0;dut.input_pointer=0;dut.output_pointer=0;
    for(auto &pointer:dut.key_pointer)pointer=0;
    dut.start=1;edge();dut.start=0;edge();
    if(!dut.done || !(dut.errors&0x80) || dut.command_valid)throw std::runtime_error("invalid DMA job count was accepted");
    reset();
    dut.final();std::cout<<"PASS DMA sequencer commands="<<checked<<" status-drain/pointer-snapshot/stalls/multi-job/reset\n";
}

#include "PaperStreamingRuntime.hpp"
#include "fpt/paper_dma_board_fixture.hpp"
#include <fstream>
#include <iomanip>
#include <iostream>
#include <set>

// This driver records board evidence only. The Python launcher verifies build,
// routed timing/DRC/resources and fixture provenance before programming a board.
// Repeating fixture frames measures sustained operation, not fresh error trials.
int main(int argc,char **argv) try {
    if(argc<3 || argc>6)throw std::invalid_argument("usage: paper_qualify KERNEL.xclbin FIXTURE_SET_DIRECTORY [FRAMES_PER_KEY=4096] [DEVICE_INDEX=0] [EXPECTED_BDF=0000:0a:00.1]");
    const std::filesystem::path directory=argv[2];
    const unsigned frames=argc>3?std::stoul(argv[3]):4096;
    if(frames<11 || frames>4096)throw std::invalid_argument("board validation requires 11..4096 frames per key");
    std::ifstream seed_file(directory/"seeds.txt");
    std::vector<unsigned> seeds;
    std::set<unsigned> distinct;
    unsigned seed;
    while(seed_file>>seed) {
        if(!distinct.insert(seed).second)throw std::invalid_argument("duplicate board fixture seed");
        seeds.push_back(seed);
    }
    if(!seed_file.eof() || seeds.size()!=32)throw std::invalid_argument("board validation requires exactly 32 fixture keys");
    fpt::paper_runtime::Runtime runtime(argv[1],argc>4?std::stoul(argv[4]):0,argc>5?argv[5]:"0000:0a:00.1");
    const auto clocks_before=runtime.clock_frequencies_mhz();
    if(clocks_before[0]!=200)throw std::runtime_error("kernel clock is not 200 MHz");
    std::cout<<std::setprecision(12);
    std::cout<<"{\"type\":\"device\",\"bdf\":\""<<runtime.bdf()<<"\",\"clock_mhz\":[";
    for(unsigned i=0;i<4;++i)std::cout<<(i?",":"")<<clocks_before[i];
    std::cout<<"],\"frames_per_key\":"<<frames<<"}"<<std::endl;
    std::uint64_t total_bootstraps=0,total_cycles=0,total_words=0,total_distinct_inputs=0;
    double total_launch_seconds=0,minimum_kernel_rate=1e9;
    for(auto current_seed:seeds) {
        const auto data=fpt::paper_dma_board_fixture::load_cycle(directory/("seed"+std::to_string(current_seed)),frames);
        std::array<std::span<const std::uint8_t>,4> images;
        for(unsigned p=0;p<4;++p)images[p]=data.keys[p];
        runtime.load_keys(images);
        const auto result=runtime.run(data.frames);
        if(result.samples!=data.expected)throw std::runtime_error("encrypted board result mismatch at seed "+std::to_string(current_seed));
        if(result.cycles==0 || result.last_compute_cycle<=result.first_compute_cycle)
            throw std::runtime_error("invalid board performance counters");
        const auto clocks_after=runtime.clock_frequencies_mhz();
        if(clocks_after[0]!=clocks_before[0])throw std::runtime_error("kernel clock changed during validation");
        const std::uint64_t bootstraps=std::uint64_t(frames)*12,words=std::uint64_t(frames)*12300;
        const double kernel_rate=double(bootstraps)*200000.0/result.cycles;
        const double launch_rate=double(bootstraps)/(result.launch_seconds*1000);
        const double period=double(result.last_compute_cycle-result.first_compute_cycle)/(frames-1);
        total_bootstraps+=bootstraps;total_cycles+=result.cycles;total_words+=words;
        total_distinct_inputs+=data.distinct_inputs;
        total_launch_seconds+=result.launch_seconds;minimum_kernel_rate=std::min(minimum_kernel_rate,kernel_rate);
        std::cout<<"{\"type\":\"key\",\"seed\":"<<current_seed<<",\"bootstraps\":"<<bootstraps
                 <<",\"distinct_inputs\":"<<data.distinct_inputs<<",\"words_compared\":"<<words
                 <<",\"cycles\":"<<result.cycles<<",\"compute_period_cycles\":"<<period
                 <<",\"kernel_pbs_per_ms\":"<<kernel_rate<<",\"launch_pbs_per_ms\":"<<launch_rate
                 <<",\"launch_seconds\":"<<result.launch_seconds<<",\"repeated_fixture\":true}"<<std::endl;
    }
    std::cout<<"{\"type\":\"summary\",\"keys\":"<<seeds.size()<<",\"bootstraps\":"<<total_bootstraps
             <<",\"distinct_inputs\":"<<total_distinct_inputs<<",\"words_compared\":"<<total_words
             <<",\"cycles\":"<<total_cycles<<",\"kernel_pbs_per_ms\":"<<double(total_bootstraps)*200000.0/total_cycles
             <<",\"minimum_key_kernel_pbs_per_ms\":"<<minimum_kernel_rate
             <<",\"launch_pbs_per_ms\":"<<double(total_bootstraps)/(total_launch_seconds*1000)
             <<",\"launch_seconds\":"<<total_launch_seconds<<",\"board_test_passed\":true,\"hardware_qualified\":false}"<<std::endl;
    return 0;
} catch(const std::exception &error) {std::cerr<<error.what()<<'\n';return 1;}

#include "PaperStreamingRuntime.hpp"
#include "fpt/paper_dma_fixture.hpp"
#include <iostream>
int main(int argc,char **argv) try {
    if(argc<3 || argc>5)throw std::invalid_argument("usage: paper_fixture KERNEL.xclbin FIXTURE_DIRECTORY [FRAMES=3] [DEVICE_INDEX=0]");
    const unsigned count=argc>3?std::stoul(argv[3]):3;
    const auto data=fpt::paper_dma_fixture::load_repeated(argv[2],count);
    fpt::paper_runtime::Runtime runtime(argv[1],argc>4?std::stoul(argv[4]):0);
    std::array<std::span<const std::uint8_t>,4> images;
    for(unsigned p=0;p<4;++p)images[p]=data.keys[p];
    runtime.load_keys(images);
    const auto result=runtime.run(data.frames);
    if(result.samples!=data.expected)throw std::runtime_error("encrypted fixture output mismatch");
    std::cout<<"PASS frames="<<count<<" bootstraps="<<count*12<<" distinct_inputs="<<data.distinct_inputs
             <<" cycles="<<result.cycles<<" first_compute_cycle="<<result.first_compute_cycle
             <<" last_compute_cycle="<<result.last_compute_cycle<<" launch_seconds="<<result.launch_seconds
             <<" repeated_fixture=true hardware_qualified=false\n";
    return 0;
} catch(const std::exception &error) {std::cerr<<error.what()<<'\n';return 1;}

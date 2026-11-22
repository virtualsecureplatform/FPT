#include "fpt/paper_dma_board_fixture.hpp"
#include "fpt/paper_streaming_bootstrap.hpp"
#include <iostream>
int main(int argc,char **argv) try {
    if(argc!=2)throw std::invalid_argument("usage: board-fixture-test KEY_FIXTURE_DIRECTORY");
    const std::filesystem::path directory=argv[1];
    const auto data=fpt::paper_dma_board_fixture::load_cycle(directory,4096);
    std::ifstream index(directory/"frames.txt");
    unsigned frames=0;
    for(std::string name;std::getline(index,name);++frames) {
        std::ifstream file(directory/name,std::ios::binary);
        for(unsigned i=0;i<8+12*3*512+12*586;++i)(void)fpt::paper_dma_fixture::word(file);
        for(unsigned context=0;context<12;++context) {
            fpt::paper_streaming::Ciphertext state;
            for(auto &component:state)for(auto &value:component)value=fpt::paper_dma_fixture::word(file);
            if(data.expected[frames][context]!=fpt::paper_streaming::sample_extract(state))
                throw std::runtime_error("board fixture extraction differs from the primary model");
        }
        for(unsigned context=0;context<12;++context)for(unsigned point=0;point<587;++point)
            if(data.frames[frames].inputs[context][point]!=fpt::paper_dma_fixture::word(file))
                throw std::runtime_error("board fixture raw TLWE mismatch");
        for(unsigned point=0;point<512;++point)
            if(data.frames[frames].lookup[point]!=fpt::paper_dma_fixture::word(file))
                throw std::runtime_error("board fixture raw lookup mismatch");
        if(file.get()!=std::char_traits<char>::eof())throw std::runtime_error("unexpected trailing board fixture data");
    }
    if(frames<11 || data.distinct_inputs!=frames*12)throw std::runtime_error("board distinct-input count mismatch");
    for(unsigned frame=0;frame<4096;++frame)
        if(data.expected[frame]!=data.expected[frame%frames] || data.frames[frame].inputs!=data.frames[frame%frames].inputs || data.frames[frame].lookup!=data.frames[frame%frames].lookup)
            throw std::runtime_error("board fixture 4096-frame cycle mismatch");
    std::cout<<"PASS board fixture loader distinct_inputs="<<data.distinct_inputs<<" frames=4096 primary_sample_extraction/raw_inputs/cycle\n";
    return 0;
} catch(const std::exception &error) {std::cerr<<error.what()<<'\n';return 1;}

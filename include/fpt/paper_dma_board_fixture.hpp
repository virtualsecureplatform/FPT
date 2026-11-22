#pragma once
#include "fpt/paper_dma_fixture.hpp"
#include <set>

namespace fpt::paper_dma_board_fixture {
// Eleven or more distinct batches per key provide correctness coverage.
// Additional requested frames cycle over that set for sustained measurements.
inline paper_dma_fixture::Data load_cycle(const std::filesystem::path &directory,unsigned count)
{
    namespace dma=paper_dma_layout;
    if(count==0 || count>4096)throw std::invalid_argument("invalid board fixture frame count");
    std::ifstream index(directory/"frames.txt");
    std::vector<std::string> names;
    std::set<std::string> unique_names;
    for(std::string name;std::getline(index,name);) {
        if(name.empty() || std::filesystem::path(name).filename()!=std::filesystem::path(name) || !unique_names.insert(name).second)
            throw std::runtime_error("invalid board fixture frame index");
        names.push_back(name);
    }
    if(!index.eof() || names.size()<11 || names.size()>256 || names[0]!="batch-bool.bin" || names[1]!="batch-ramp8.bin")
        throw std::runtime_error("board fixture needs at least eleven indexed batches");
    auto initial=paper_dma_fixture::load_repeated(directory,2);
    auto frames=std::move(initial.frames);
    auto expected=std::move(initial.expected);
    frames.resize(names.size());expected.resize(names.size());
    for(unsigned frame=2;frame<names.size();++frame) {
        std::ifstream file(directory/names[frame],std::ios::binary);
        for(auto value:{0x46505442u,1u,586u,12u,26u,4u,40u,frame%2})
            if(paper_dma_fixture::word(file)!=value)throw std::runtime_error("board fixture batch header mismatch");
        for(unsigned i=0;i<12*3*512+12*586;++i)(void)paper_dma_fixture::word(file);
        for(unsigned context=0;context<12;++context)for(unsigned component=0;component<3;++component)
            for(unsigned point=0;point<512;++point) {
                const auto value=paper_dma_fixture::word(file);
                if(component<2)expected[frame][context][component*512+(point==0?0:512-point)]=point==0?value:0u-value;
                else if(point==0)expected[frame][context][1024]=value;
            }
        for(auto &input:frames[frame].inputs)for(auto &value:input)value=paper_dma_fixture::word(file);
        for(auto &value:frames[frame].lookup)value=paper_dma_fixture::word(file);
        if(file.get()!=std::char_traits<char>::eof())throw std::runtime_error("board fixture has trailing batch data");
    }
    std::set<dma::Tlwe> unique_inputs;
    for(const auto &frame:frames)for(const auto &input:frame.inputs)
        if(!unique_inputs.insert(input).second)throw std::runtime_error("board fixture repeats an input in its distinct set");
    paper_dma_fixture::Data result;
    result.keys=std::move(initial.keys);result.frames.resize(count);result.expected.resize(count);
    for(unsigned frame=0;frame<count;++frame){result.frames[frame]=frames[frame%frames.size()];result.expected[frame]=expected[frame%frames.size()];}
    result.distinct_inputs=12*std::min(std::size_t(count),frames.size());
    return result;
}
} // namespace fpt::paper_dma_board_fixture

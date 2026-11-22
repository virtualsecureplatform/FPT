#include "fpt/paper_streaming_mac.hpp"
#include <cassert>

int main()
{
    using namespace fpt::paper_streaming;
    SixRowMac mac({{15,14},{7,19},{26,33}});
    Frame data;std::array<Frame,3> key;
    for(unsigned j=0;j<256;++j) {
        data[j]={static_cast<int>(j)-128,static_cast<int>(j%17)-8};
        for(unsigned c=0;c<3;++c)key[c][j]={static_cast<int>(c)+1,static_cast<int>(c)-2};
    }
    fpt::QuantizationStats stats;
    bool rejected=false;
    try { (void)mac.result(); } catch(const std::logic_error &) { rejected=true; }
    assert(rejected);
    for(unsigned r=0;r<6;++r)mac.push(data,key,stats);
    for(unsigned c=0;c<3;++c)for(unsigned j=0;j<256;++j) {
        const auto &v=mac.result()[c][j];
        assert(v.real==6*(data[j].real*key[c][j].real-data[j].imag*key[c][j].imag));
        assert(v.imag==6*(data[j].real*key[c][j].imag+data[j].imag*key[c][j].real));
    }
    assert(stats.overflows==0);
    mac.reset();
    Frame zero{};for(unsigned r=0;r<6;++r)mac.push(zero,key,stats);
    for(const auto &component:mac.result())for(auto v:component)assert(v.real==0 && v.imag==0);
}

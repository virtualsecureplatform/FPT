#include "fpt/paper_key_packing.hpp"
#include "fpt/paper_grouped_key_packing.hpp"
#include <cassert>
#include <random>
#include <vector>

int main()
{
    namespace pk=fpt::paper_key_packing;
    std::mt19937_64 random(163);
    for(unsigned trial=0;trial<10000;++trial) {
        auto value=[&] {return std::int64_t(random()%(1ULL<<28))-(1LL<<27);};
        const fpt::FixedComplex input{value(),value()};
        const auto result=pk::decode(pk::encode(input));
        assert(result.real==input.real && result.imag==input.imag);
    }
    const auto encoded=pk::encode({-1,-(1LL<<27)});
    const pk::Encoded expected{0xff,0xff,0xff,0x0f,0,0,0x80};
    assert(encoded==expected);
    bool rejected=false;
    try {pk::encode({1LL<<27,0});}catch(const std::out_of_range &){rejected=true;}
    assert(rejected);
    auto get=[](unsigned step,unsigned row,unsigned component,unsigned point) {
        return fpt::FixedComplex{std::int64_t(step*8192+row*1024+component*256+point),
                                 -std::int64_t(step*8192+row*1024+component*256+point)};
    };
    std::vector<std::uint8_t> payload(pk::key_bytes,0xa5);
    pk::pack(payload,get);
    for(unsigned step:{0u,1u,585u})for(unsigned row=0;row<6;++row)
        for(unsigned c=0;c<3;++c)for(unsigned j:{0u,1u,255u}) {
            pk::Encoded word;
            const auto index=pk::offset(step,row,c,j);
            for(unsigned b=0;b<7;++b)word[b]=payload[index+b];
            const auto result=pk::decode(word),expected=get(step,row,c,j);
            assert(result.real==expected.real && result.imag==expected.imag);
        }
    assert(pk::offset(585,5,2,255)+7==payload.size());
    std::array<bool,256> seen{};
    for(unsigned point=0;point<256;++point) {
        const auto index=pk::natural_point(point,pk::SpectrumOrder::ButterflyGroups);
        assert(index<256 && !seen[index]);seen[index]=true;
    }
    pk::pack(payload,get,pk::SpectrumOrder::ButterflyGroups);
    for(unsigned point=0;point<256;++point) {
        pk::Encoded word;
        const auto index=pk::offset(585,5,2,point);
        for(unsigned b=0;b<7;++b)word[b]=payload[index+b];
        const auto result=pk::decode(word),expected=get(585,5,2,point/4+(point%4)*64);
        assert(result.real==expected.real && result.imag==expected.imag);
    }
    namespace grouped=fpt::paper_grouped_key_packing;
    std::array<std::vector<std::uint8_t>,4> images;
    std::array<std::span<std::uint8_t>,4> destinations;
    for(unsigned port=0;port<4;++port) {
        images[port].resize(grouped::key_bytes(port),0xa5);destinations[port]=images[port];
    }
    grouped::pack(destinations,get);
    for(unsigned port=0;port<4;++port)for(unsigned step=0;step<586;++step)
        for(unsigned slot=0;slot<12;++slot)for(unsigned local=0;local<grouped::groups_per_slot(port);++local) {
            const unsigned base=step*grouped::step_bytes(port)+(slot*grouped::groups_per_slot(port)+local)*64;
            assert(images[port][base+63]==0);
            for(unsigned item=0;item<9;++item) {
                const unsigned lane=(port+4*local)*9+item;
                pk::Encoded bytes;
                for(unsigned b=0;b<7;++b)bytes[b]=images[port][base+item*7+b];
                const auto decoded=pk::decode(bytes);
                if(lane>=384){assert(decoded.real==0 && decoded.imag==0);continue;}
                const unsigned native=(slot%2)*128+lane%128;
                const auto expected=get(step,slot/2,lane/128,native/4+(native%4)*64);
                assert(decoded.real==expected.real && decoded.imag==expected.imag);
            }
        }
}

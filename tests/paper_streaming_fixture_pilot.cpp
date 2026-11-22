#include "../include/fpt/paper_streaming_bootstrap.hpp"
#include "../include/fpt/paper_reference.hpp"
#include "../include/fpt/paper_key_packing.hpp"
#include "fpt/tfhepp_adapter.hpp"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <random>
#include <stdexcept>

namespace ps=fpt::paper_streaming;
namespace pr=fpt::paper_reference;
static void word(std::ostream &file,std::uint32_t value)
{for(unsigned byte=0;byte<4;++byte)file.put(char(value>>(8*byte)));}
int main(int argc,char **argv)
{
    using BP=TFHEpp::lvl01param;using D=BP::domainP;using T=BP::targetP;
    static_assert(D::n==586 && T::n==512 && T::k==2);
    if(argc!=3){std::cerr<<"usage: fixture-pilot SEED NEW_OUTPUT_DIRECTORY\n";return 1;}
    const unsigned seed=std::stoul(argv[1]);
    const std::filesystem::path output=argv[2];
    if(std::filesystem::exists(output))throw std::runtime_error("refusing to overwrite fixture directory");
    std::filesystem::create_directories(output);
    std::mt19937_64 random(seed);
    TFHEpp::Key<D> domain;TFHEpp::Key<T> target;
    for(auto &v:domain)v=random()&1;
    for(auto &v:target)v=random()&1;
    constexpr auto profile=[] {auto p=fpt::ArithmeticProfile::parameter_set_i();
        p.bootstrapping_key={7,21};p.bootstrapping_key_round_nearest_even=true;return p;}();
    auto key=std::make_unique<fpt::tfhepp::BootstrappingKeyFPT<BP>>();
    fpt::QuantizationStats key_stats;
    fpt::tfhepp::BootstrappingKeyFPTGen<BP,profile>(*key,domain,target,&key_stats,&random);
    if(key_stats.overflows)throw std::runtime_error("encrypted fixture key overflows Q7.21");
    {
        std::ofstream file(output/"key.bin",std::ios::binary);file.exceptions(std::ios::badbit|std::ios::failbit);
        for(auto value:{0x4650544bu,1u,586u,512u,2u,28u,7u,18902016u})word(file,value);
        for(unsigned step=0;step<586;++step)for(unsigned row=0;row<6;++row)
            for(unsigned component=0;component<3;++component)for(unsigned point=0;point<256;++point) {
                const auto value=(*key)[step][0][row][component][point];
                const auto bytes=fpt::paper_key_packing::encode({value.real,value.imag});
                file.write(reinterpret_cast<const char*>(bytes.data()),bytes.size());
            }
    }
    ps::TransformSchedule forward{{9,23},26,{}},inverse{{20,12},26,{}};
    forward.stage_data=std::array<fpt::FixedFormat,4>{{{11,21},{13,19},{15,17},{17,15}}};
    inverse.stage_data=std::array<fpt::FixedFormat,4>{{{20,12},{21,11},{22,10},{23,9}}};
    forward.leg_guard_bits=4;inverse.leg_guard_bits=4;
    const ps::ExternalSchedule schedule{forward,inverse,{{17,15},{7,21},{26,14}}};
    ps::KeyRows fixed_rows;pr::KeyRows reference_rows;
    auto fixed_provider=[&](unsigned step)->const auto& {
        for(unsigned row=0;row<6;++row)for(unsigned c=0;c<3;++c)for(unsigned j=0;j<256;++j) {
            const auto value=(*key)[step][0][row][c][j];fixed_rows[row][c][j]={value.real,value.imag};
        }
        return fixed_rows;
    };
    auto reference_provider=[&](unsigned step)->const auto& {
        for(unsigned row=0;row<6;++row)for(unsigned c=0;c<3;++c)for(unsigned j=0;j<256;++j) {
            const auto value=(*key)[step][0][row][c][j];
            reference_rows[row][c][j]={std::ldexp(static_cast<long double>(value.real),-21),
                                      std::ldexp(static_cast<long double>(value.imag),-21)};
        }
        return reference_rows;
    };
    auto phase=[&](const auto &sample) {
        std::uint32_t result=sample[1024];
        for(unsigned j=0;j<1024;++j)result-=sample[j]*target[j];
        return static_cast<std::int32_t>(result);
    };
    std::mt19937_64 input_random(0x4650545f46495854ULL^seed);
    std::normal_distribution<double> noise(0,D::α);
    unsigned checked=0;
    for(unsigned kind=0;kind<2;++kind) {
        std::array<ps::InputTlwe,12> inputs;
        std::array<ps::Ciphertext,12> initial,expected;
        std::array<std::array<unsigned,586>,12> exponents;
        std::array<std::uint32_t,512> lookup;
        for(unsigned j=0;j<512;++j)lookup[j]=T::μ+(kind?((j/32)%8)*(1u<<19):0);
        for(unsigned context=0;context<12;++context) {
            auto &input=inputs[context];const bool message=context%2;
            input[586]=(message?D::μ:0u-D::μ)+std::uint32_t(std::int64_t(std::ldexp(noise(input_random),32)));
            for(unsigned j=0;j<586;++j){input[j]=std::uint32_t(input_random());input[586]+=input[j]*domain[j];}
            initial[context]={};initial[context][2]=lookup;
            initial[context]=ps::rotate_ciphertext(initial[context],(1024-ps::modulus_switch(input[586]))%1024);
            expected[context]=initial[context];fpt::QuantizationStats stats;
            for(unsigned step=0;step<586;++step) {
                exponents[context][step]=ps::modulus_switch(input[step]);
                if(exponents[context][step])expected[context]=ps::cmux(expected[context],exponents[context][step],fixed_provider(step),schedule,stats);
            }
            const auto fixed_phase=phase(ps::sample_extract(expected[context]));
            const auto reference_phase=phase(pr::bootstrap(input,lookup,reference_provider));
            std::cout<<"fixture seed="<<seed<<" kind="<<kind<<" context="<<context<<" message="<<message
                     <<" fixed_phase="<<fixed_phase<<" reference_phase="<<reference_phase
                     <<" overflows="<<stats.overflows<<std::endl;
            if(stats.overflows || (fixed_phase>0)!=message || (reference_phase>0)!=message)
                throw std::runtime_error("ordinary encrypted fixture failed fixed/reference decryption");
            ++checked;
        }
        std::ofstream file(output/(kind?"batch-ramp8.bin":"batch-bool.bin"),std::ios::binary);
        file.exceptions(std::ios::badbit|std::ios::failbit);
        for(auto value:{0x46505442u,1u,586u,12u,26u,4u,40u,kind})word(file,value);
        for(const auto &state:initial)for(const auto &component:state)for(auto value:component)word(file,value);
        for(const auto &context:exponents)for(auto value:context)word(file,value);
        for(const auto &state:expected)for(const auto &component:state)for(auto value:component)word(file,value);
        for(const auto &input:inputs)for(auto value:input)word(file,value);
        for(auto value:lookup)word(file,value);
    }
    std::ofstream manifest(output/"fixture.json");
    manifest<<"{\"schema_version\":1,\"seed\":"<<seed<<",\"contexts_per_lookup\":12,\"lookups\":[\"bool\",\"ramp8\"],"
              "\"ordinary_encrypted_key\":true,\"fixed_and_independent_reference_passed\":true,\"checked\":"<<checked<<","
              "\"profile\":\"tf26guard4acc40\",\"hardware_qualified\":false,\"numerical_qualified\":false}\n";
    std::cout<<"PASS encrypted fixture cases="<<checked<<" seed="<<seed<<'\n';
}

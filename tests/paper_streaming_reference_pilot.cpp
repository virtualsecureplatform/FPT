#include "../include/fpt/paper_streaming_bootstrap.hpp"
#include "../include/fpt/paper_reference.hpp"
#include "fpt/tfhepp_adapter.hpp"
#include <iostream>
#include <memory>
#include <random>

int main(int argc,char **argv)
{
    using BP=TFHEpp::lvl01param;using D=BP::domainP;using T=BP::targetP;
    static_assert(D::n==586 && T::n==512 && T::k==2);
    if(argc!=3 && argc!=4){std::cerr<<"usage: reference-pilot SEED EVEN_TRIALS [guarded32|tf24guard4acc40]\n";return 1;}
    const std::string candidate=argc==4?argv[3]:"guarded32";
    if(candidate!="guarded32" && candidate!="tf24guard4acc40" && candidate!="tf26guard4" && candidate!="tf26guard4acc40")return 1;
    const unsigned seed=std::stoul(argv[1]),trials=std::stoul(argv[2]);
    if(trials<2 || trials>256 || trials%2)return 1;
    std::mt19937_64 key_random(seed);
    TFHEpp::Key<D> domain;TFHEpp::Key<T> target;
    for(auto &v:domain)v=key_random()&1;
    for(auto &v:target)v=key_random()&1;
    constexpr auto profile=[] {auto p=fpt::ArithmeticProfile::parameter_set_i();
        p.bootstrapping_key={7,21};p.bootstrapping_key_round_nearest_even=true;return p;}();
    auto key=std::make_unique<fpt::tfhepp::BootstrappingKeyFPT<BP>>();
    fpt::QuantizationStats key_stats;
    fpt::tfhepp::BootstrappingKeyFPTGen<BP,profile>(*key,domain,target,&key_stats,&key_random);
    fpt::paper_streaming::TransformSchedule forward{{9,23},26,{}};
    forward.stage_data=std::array<fpt::FixedFormat,4>{{{11,21},{13,19},{15,17},{17,15}}};
    fpt::paper_streaming::TransformSchedule inverse{{20,12},26,{}};
    inverse.stage_data=std::array<fpt::FixedFormat,4>{{{20,12},{21,11},{22,10},{23,9}}};
    if(candidate!="guarded32") {
        forward.twiddle_fraction=candidate=="tf24guard4acc40"?24:26;
        inverse.twiddle_fraction=forward.twiddle_fraction;
        forward.leg_guard_bits=4;inverse.leg_guard_bits=4;
    }
    const fpt::paper_streaming::ExternalSchedule schedule{forward,inverse,{forward.output_format(),{7,21},{26,candidate.find("acc40")!=std::string::npos?14:22}}};
    std::array<std::uint32_t,512> lookup;lookup.fill(T::μ);
    std::mt19937_64 input_random(0x4650545f53574550ULL ^ seed);
    std::uniform_int_distribution<std::uint32_t> uniform;
    std::normal_distribution<double> noise(0,D::α);
    unsigned failures=0;
    for(unsigned trial=0;trial<trials;++trial) {
        TFHEpp::TLWE<D> input;
        const unsigned message=trial&1;
        input[586]=(message?D::μ:0u-D::μ)+static_cast<std::uint32_t>(
            static_cast<std::int64_t>(std::ldexp(noise(input_random),32)));
        for(unsigned j=0;j<586;++j){input[j]=uniform(input_random);input[586]+=input[j]*domain[j];}
        fpt::paper_streaming::KeyRows fixed_rows;
        fpt::paper_reference::KeyRows reference_rows;
        auto fixed_provider=[&](unsigned step)->const auto& {
            for(unsigned r=0;r<6;++r)for(unsigned c=0;c<3;++c)for(unsigned j=0;j<256;++j) {
                const auto v=(*key)[step][0][r][c][j];fixed_rows[r][c][j]={v.real,v.imag};
            }
            return fixed_rows;
        };
        auto reference_provider=[&](unsigned step)->const auto& {
            for(unsigned r=0;r<6;++r)for(unsigned c=0;c<3;++c)for(unsigned j=0;j<256;++j) {
                const auto v=(*key)[step][0][r][c][j];
                reference_rows[r][c][j]={std::ldexp(static_cast<long double>(v.real),-21),
                                        std::ldexp(static_cast<long double>(v.imag),-21)};
            }
            return reference_rows;
        };
        fpt::QuantizationStats stats;
        const auto fixed=fpt::paper_streaming::bootstrap(input,lookup,fixed_provider,schedule,stats);
        const auto reference=fpt::paper_reference::bootstrap(input,lookup,reference_provider);
        auto phase=[&](const auto &output) {
            std::uint32_t value=output[1024];
            for(unsigned j=0;j<1024;++j)value-=output[j]*target[j];
            return static_cast<std::int32_t>(value);
        };
        const auto fp=phase(fixed),rp=phase(reference);
        failures+=((fp>0)!=bool(message)) || ((rp>0)!=bool(message)) || stats.overflows!=0;
        const auto delta=static_cast<std::int32_t>(std::uint32_t(fp)-std::uint32_t(rp));
        std::cout<<"reference_compare seed="<<seed<<" trial="<<trial<<" message="<<message
                 <<" fixed_phase="<<fp<<" reference_phase="<<rp<<" phase_delta="<<delta
                 <<" overflows="<<stats.overflows<<std::endl;
    }
    std::cout<<"screen_only failures="<<failures<<" trials="<<trials<<" key_overflows="<<key_stats.overflows<<'\n';
    return failures || key_stats.overflows ? 2:0;
}

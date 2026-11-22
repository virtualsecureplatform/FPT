#include "../include/fpt/paper_streaming_bootstrap.hpp"
#include "fpt/tfhepp_adapter.hpp"
#include <tfhe/tlwe.hpp>
#include <iostream>
#include <memory>
#include <random>

template<bool Wide,unsigned TransformBits=29,unsigned KeyBits=26,unsigned AccumulatorBits=59,unsigned ForwardInteger=15,bool Staged=false,bool ForwardStaged=false,bool Guarded=false,unsigned TwiddleFraction=TransformBits-6,int LegGuard=-1> int run(unsigned seed=42,unsigned trials=2)
{
    using namespace fpt::paper_streaming;
    using BP=TFHEpp::lvl01param;using D=BP::domainP;using T=BP::targetP;
    static_assert(D::n==586 && T::n==512 && T::k==2);
    std::mt19937_64 random(seed);
    TFHEpp::Key<D> domain;TFHEpp::Key<T> target;
    for(auto &v:domain)v=random()&1;
    for(auto &v:target)v=random()&1;
    constexpr auto profile=[] {auto p=fpt::ArithmeticProfile::parameter_set_i();
        if constexpr(Wide)p.bootstrapping_key={8,24};
        else p.bootstrapping_key={7,KeyBits-7};
        p.bootstrapping_key_round_nearest_even=true;return p;}();
    auto key=std::make_unique<fpt::tfhepp::BootstrappingKeyFPT<BP>>();
    fpt::QuantizationStats key_stats;
    fpt::tfhepp::BootstrappingKeyFPTGen<BP,profile>(*key,domain,target,&key_stats,&random);
    TransformDiagnostics forward_diagnostics,inverse_diagnostics;
    ExternalDiagnostics external_diagnostics;
    TransformSchedule forward{Wide?fpt::FixedFormat{18,28}:fpt::FixedFormat{ForwardInteger,TransformBits-ForwardInteger},Wide?39u:TwiddleFraction,{},&forward_diagnostics};
    if constexpr(ForwardStaged) {
        forward.data={9,23};
        forward.stage_data=std::array<fpt::FixedFormat,4>{{{11,21},{13,19},{15,17},{17,15}}};
    }
    TransformSchedule inverse{Wide?fpt::FixedFormat{27,28}:fpt::FixedFormat{23,TransformBits-23},Wide?39u:TwiddleFraction,{},&inverse_diagnostics};
    if constexpr(Staged) {
        inverse.data={19,13};
        inverse.stage_data=std::array<fpt::FixedFormat,4>{{{20,12},{21,11},{22,10},{23,9}}};
        if constexpr(Guarded)inverse.data={20,12};
    }
    if constexpr(LegGuard>=0) {forward.leg_guard_bits=LegGuard;inverse.leg_guard_bits=LegGuard;}
    const ExternalSchedule schedule{forward,inverse,{forward.output_format(),profile.bootstrapping_key,Wide?fpt::FixedFormat{26,36}:fpt::FixedFormat{26,AccumulatorBits-26}},&external_diagnostics};
    std::array<std::uint32_t,512> lookup;lookup.fill(T::μ);
    unsigned passed=0;
    std::uint64_t overflows=0;
    std::mt19937_64 input_random(0x4650545f53574550ULL ^ seed);
    std::uniform_int_distribution<std::uint32_t> uniform;
    std::normal_distribution<double> noise(0,D::α);
    for(unsigned trial=0;trial<trials;++trial) {
        const unsigned message=trial&1;
        TFHEpp::TLWE<D> input;
        input[586]=(message?D::μ:0u-D::μ)+static_cast<std::uint32_t>(static_cast<std::int64_t>(std::ldexp(noise(input_random),32)));
        for(unsigned j=0;j<586;++j) {
            input[j]=uniform(input_random);input[586]+=input[j]*domain[j];
        }
        KeyRows rows;
        auto provider=[&](unsigned step)->const KeyRows& {
            for(unsigned r=0;r<6;++r)for(unsigned c=0;c<3;++c)
                for(unsigned j=0;j<256;++j) {
                    const auto value=(*key)[step][0][r][c][j];
                    rows[r][c][j]={value.real,value.imag};
                }
            return rows;
        };
        fpt::QuantizationStats stats;
        const auto output=bootstrap(input,lookup,provider,schedule,stats);
        std::uint32_t phase=output[1024];
        for(unsigned j=0;j<1024;++j)phase-=output[j]*target[j];
        const bool decrypted=static_cast<std::int32_t>(phase)>0;
        passed+=decrypted==bool(message);
        overflows+=stats.overflows;
        std::cout<<"seed="<<seed<<" trial="<<trial<<" message="<<message<<" decrypted="<<decrypted<<" phase="<<static_cast<std::int32_t>(phase)<<" overflows="<<stats.overflows<<std::endl;
    }
    std::cout<<"screen_only seed="<<seed<<" correct="<<passed<<'/'<<trials<<" key_overflows="<<key_stats.overflows<<std::endl;
    std::cerr<<"diagnostics seed="<<seed<<" forward=";
    for(auto v:forward_diagnostics.overflows)std::cerr<<v<<',';
    std::cerr<<" inverse=";
    for(auto v:inverse_diagnostics.overflows)std::cerr<<v<<',';
    std::cerr<<" mac="<<external_diagnostics.mac_overflows<<" inverse_input="<<external_diagnostics.inverse_input_overflows<<'\n';
    return passed==trials && key_stats.overflows==0 && overflows==0?0:2;
}

int main(int argc,char **argv)
{
    if(argc==1)return run<false>();
    if(argc==2 && std::string(argv[1])=="wide")return run<true>();
    if(argc==2 && std::string(argv[1])=="compact32")return run<false,32,28,48>();
    if(argc==5 && (std::string(argv[1])=="compact32" || std::string(argv[1])=="headroom32" || std::string(argv[1])=="staged32" || std::string(argv[1])=="fullstaged32" || std::string(argv[1])=="guarded32" || std::string(argv[1])=="tf24full" || std::string(argv[1])=="tf24guard4" || std::string(argv[1])=="tf24guard4acc40" || std::string(argv[1])=="tf26guard4" || std::string(argv[1])=="tf26guard4acc40")) {
        try {
            const int start=std::stoi(argv[2]),keys=std::stoi(argv[3]),trials=std::stoi(argv[4]);
            if(start<0 || start>1000000 || keys<1 || keys>256 || trials<2 || trials>256 || trials%2)throw std::invalid_argument("invalid screen range");
            int result=0;
            for(int k=0;k<keys;++k) {
                const int code=std::string(argv[1])=="tf26guard4acc40" ? run<false,32,28,40,17,true,true,true,26,4>(start+k,trials):
                    std::string(argv[1])=="tf26guard4" ? run<false,32,28,48,17,true,true,true,26,4>(start+k,trials):
                    std::string(argv[1])=="tf24guard4acc40" ? run<false,32,28,40,17,true,true,true,24,4>(start+k,trials):
                    std::string(argv[1])=="tf24guard4" ? run<false,32,28,48,17,true,true,true,24,4>(start+k,trials):
                    std::string(argv[1])=="tf24full" ? run<false,32,28,48,17,true,true,true,24>(start+k,trials):
                    std::string(argv[1])=="guarded32" ? run<false,32,28,48,17,true,true,true>(start+k,trials):
                    std::string(argv[1])=="fullstaged32" ? run<false,32,28,48,17,true,true>(start+k,trials):
                    std::string(argv[1])=="staged32" ? run<false,32,28,48,17,true>(start+k,trials):
                    std::string(argv[1])=="headroom32" ? run<false,32,28,48,17>(start+k,trials):run<false,32,28,48>(start+k,trials);
                if(code)result=2;
            }
            return result;
        } catch(const std::exception &e) {std::cerr<<e.what()<<'\n';return 1;}
    }
    std::cerr<<"usage: encrypted-pilot [wide|compact32|headroom32|staged32|fullstaged32|guarded32 SEED_START KEYS EVEN_TRIALS]\n";return 1;
}

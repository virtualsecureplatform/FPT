#include "../include/fpt/paper_streaming_bootstrap.hpp"
#include "../include/fpt/paper_reference.hpp"
#include "fpt/tfhepp_adapter.hpp"
#include <iostream>
#include <memory>
#include <random>
namespace ps=fpt::paper_streaming;
namespace pr=fpt::paper_reference;

// Exploration-only extension: the 32-bit accumulator geometries need an
// explicit left shift before the inverse, and some 59-bit accumulators need
// an exact left shift of the product. Do not change the validated RTL model.
static std::int64_t scale(__int128 value,int discard,fpt::FixedFormat destination,fpt::QuantizationStats &stats)
{
    if(discard>=0)return ps::requantize(value,unsigned(discard),destination,stats);
    if(discard< -60)throw std::invalid_argument("unsupported geometry scale");
    return fpt::wrap_signed(value*(__int128{1}<<unsigned(-discard)),destination,&stats);
}
static ps::Ciphertext geometry_cmux(const ps::Ciphertext &state,unsigned exponent,const ps::KeyRows &key,
                                   const ps::ExternalSchedule &schedule,fpt::QuantizationStats &stats)
{
    const auto digits=ps::decompose_difference(state,exponent,schedule.forward.data);
    std::array<ps::Frame,3> sums{};
    const auto accumulator=schedule.mac.accumulator;
    const int product_shift=schedule.mac.spectrum.fractional_bits+schedule.mac.key.fractional_bits-accumulator.fractional_bits;
    for(unsigned row=0;row<6;++row) {
        const auto spectrum=ps::negacyclic_forward(digits[row],schedule.forward,stats);
        for(unsigned component=0;component<3;++component)for(unsigned point=0;point<256;++point) {
            const auto &a=spectrum[point],&b=key[row][component][point];
            const auto product=ps::gauss_product(a.real,a.imag,b.real,b.imag);
            const auto real=scale(product.real,product_shift,accumulator,stats);
            const auto imag=scale(product.imag,product_shift,accumulator,stats);
            auto &sum=sums[component][point];
            sum.real=fpt::wrap_signed(__int128{sum.real}+real,accumulator,&stats);
            sum.imag=fpt::wrap_signed(__int128{sum.imag}+imag,accumulator,&stats);
        }
    }
    ps::Ciphertext result;
    const int input_shift=accumulator.fractional_bits-schedule.inverse.data.fractional_bits;
    for(unsigned component=0;component<3;++component) {
        ps::Frame input;
        for(unsigned point=0;point<256;++point)input[point]={
            scale(sums[component][point].real,input_shift,schedule.inverse.data,stats),
            scale(sums[component][point].imag,input_shift,schedule.inverse.data,stats)};
        const auto update=ps::negacyclic_inverse(input,schedule.inverse,0,stats,true);
        const auto fraction=schedule.inverse.output_format().fractional_bits+8;
        for(unsigned point=0;point<512;++point)result[component][point]=state[component][point]+ps::torus_word(update[point],fraction);
    }
    return result;
}
template<unsigned KeyBits> int screen(unsigned seed,unsigned trials)
{
    using BP=TFHEpp::lvl01param;using D=BP::domainP;using T=BP::targetP;
    static_assert(D::n==586 && T::n==512 && T::k==2);
    std::mt19937_64 random(seed);
    TFHEpp::Key<D> domain;TFHEpp::Key<T> target;
    for(auto &value:domain)value=random()&1;
    for(auto &value:target)value=random()&1;
    constexpr auto profile=[] {auto p=fpt::ArithmeticProfile::parameter_set_i();
        p.bootstrapping_key={7,KeyBits-7};p.bootstrapping_key_round_nearest_even=true;return p;}();
    auto key=std::make_unique<fpt::tfhepp::BootstrappingKeyFPT<BP>>();
    fpt::QuantizationStats key_stats;
    fpt::tfhepp::BootstrappingKeyFPTGen<BP,profile>(*key,domain,target,&key_stats,&random);
    ps::KeyRows rows;pr::KeyRows reference_rows;
    auto provider=[&](unsigned step)->const auto& {
        for(unsigned row=0;row<6;++row)for(unsigned component=0;component<3;++component)for(unsigned point=0;point<256;++point) {
            const auto value=(*key)[step][0][row][component][point];rows[row][component][point]={value.real,value.imag};
        }
        return rows;
    };
    auto reference_provider=[&](unsigned step)->const auto& {
        for(unsigned row=0;row<6;++row)for(unsigned component=0;component<3;++component)for(unsigned point=0;point<256;++point) {
            const auto value=(*key)[step][0][row][component][point];
            reference_rows[row][component][point]={std::ldexp(static_cast<long double>(value.real),-int(KeyBits-7)),
                                                  std::ldexp(static_cast<long double>(value.imag),-int(KeyBits-7))};
        }
        return reference_rows;
    };
    auto phase=[&](const auto &sample) {
        std::uint32_t result=sample[1024];
        for(unsigned point=0;point<1024;++point)result-=sample[point]*target[point];
        return std::int32_t(result);
    };
    std::mt19937_64 input_random(0x4650545f47454f4dULL^seed);
    std::normal_distribution<double> noise(0,D::α);
    for(unsigned trial=0;trial<trials;++trial) {
        const bool message=trial&1;const unsigned lookup_kind=(trial/2)&1;
        ps::InputTlwe input;
        input[586]=(message?D::μ:0u-D::μ)+std::uint32_t(std::int64_t(std::ldexp(noise(input_random),32)));
        for(unsigned point=0;point<586;++point){input[point]=std::uint32_t(input_random());input[586]+=input[point]*domain[point];}
        std::array<std::uint32_t,512> lookup;
        for(unsigned point=0;point<512;++point)lookup[point]=T::μ+(lookup_kind?((point/32)%8)*(1u<<19):0);
        const auto reference_phase=phase(pr::bootstrap(input,lookup,reference_provider));
        for(unsigned transform:{29u,30u,32u})for(unsigned accumulator:{32u,40u,48u,59u}) {
            ps::TransformSchedule forward{{9,int(transform)-9},26,{}},inverse{{20,int(transform)-20},26,{}};
            forward.stage_data=std::array<fpt::FixedFormat,4>{{{11,int(transform)-11},{13,int(transform)-13},{15,int(transform)-15},{17,int(transform)-17}}};
            inverse.stage_data=std::array<fpt::FixedFormat,4>{{{20,int(transform)-20},{21,int(transform)-21},{22,int(transform)-22},{23,int(transform)-23}}};
            forward.leg_guard_bits=4;inverse.leg_guard_bits=4;
            const ps::ExternalSchedule schedule{forward,inverse,{forward.output_format(),profile.bootstrapping_key,{26,int(accumulator)-26}}};
            ps::Ciphertext state{};state[2]=lookup;
            state=ps::rotate_ciphertext(state,(1024-ps::modulus_switch(input[586]))%1024);
            fpt::QuantizationStats stats;
            for(unsigned step=0;step<586;++step) {
                const auto exponent=ps::modulus_switch(input[step]);
                if(exponent)state=geometry_cmux(state,exponent,provider(step),schedule,stats);
            }
            const auto output=ps::sample_extract(state);const auto fixed_phase=phase(output);
            if(transform==32 && KeyBits==28 && accumulator==40) {
                fpt::QuantizationStats primary_stats;
                if(output!=ps::bootstrap(input,lookup,provider,schedule,primary_stats) || stats.overflows!=primary_stats.overflows)
                    throw std::runtime_error("geometry extension differs from validated primary schedule");
            }
            std::cout<<"geometry T="<<transform<<" K="<<KeyBits<<" A="<<accumulator<<" seed="<<seed
                     <<" trial="<<trial<<" lookup="<<lookup_kind<<" message="<<message<<" decrypted="<<(fixed_phase>0)
                     <<" phase="<<fixed_phase<<" reference_phase="<<reference_phase<<" overflows="<<stats.overflows
                     <<" key_overflows="<<key_stats.overflows<<std::endl;
        }
    }
    return 0;
}
int main(int argc,char **argv) try {
    if(argc!=4)throw std::invalid_argument("usage: geometry-pilot KEY_BITS SEED TRIALS_MULTIPLE_OF_4");
    const unsigned bits=std::stoul(argv[1]),seed=std::stoul(argv[2]),trials=std::stoul(argv[3]);
    if(seed>1000000 || trials<4 || trials>256 || trials%4)throw std::invalid_argument("invalid geometry screen range");
    if(bits==26)return screen<26>(seed,trials);
    if(bits==27)return screen<27>(seed,trials);
    if(bits==28)return screen<28>(seed,trials);
    throw std::invalid_argument("key width outside declared geometry grid");
} catch(const std::exception &error) {std::cerr<<error.what()<<'\n';return 1;}

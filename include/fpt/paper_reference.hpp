#pragma once
// Independent floating-point oracle. No fixed-point FFT, MAC, decomposition,
// or rotation implementation is shared with the experimental RTL model.
#include <array>
#include <cmath>
#include <complex>
#include <cstdint>
#include <numbers>

namespace fpt::paper_reference {
using Complex=std::complex<long double>;
using Spectrum=std::array<Complex,256>;
using Polynomial=std::array<long double,512>;
using Ciphertext=std::array<std::array<std::uint32_t,512>,3>;
using KeyRows=std::array<std::array<Spectrum,3>,6>;

inline Spectrum fft(Spectrum values,bool inverse)
{
    // Radix-2 DIF followed by an explicit bit reversal, unlike the radix-4 DIT model.
    for(unsigned length=256;length>=2;length/=2)
        for(unsigned base=0;base<256;base+=length)
            for(unsigned j=0;j<length/2;++j) {
                const auto a=values[base+j],b=values[base+j+length/2];
                values[base+j]=a+b;
                values[base+j+length/2]=(a-b)*std::polar(1.L,
                    (inverse?2:-2)*std::numbers::pi_v<long double>*j/length);
            }
    Spectrum ordered;
    for(unsigned i=0;i<256;++i) {
        unsigned reversed=0;
        for(unsigned bit=0;bit<8;++bit)reversed=(reversed<<1)|((i>>bit)&1);
        ordered[reversed]=values[i]/(inverse?256.L:1.L);
    }
    return ordered;
}

inline Spectrum forward(const Polynomial &polynomial)
{
    Spectrum folded;
    for(unsigned j=0;j<256;++j)
        folded[j]=Complex(polynomial[j],polynomial[j+256])*
            std::polar(1.L,std::numbers::pi_v<long double>*j/512);
    return fft(folded,false);
}

inline Polynomial inverse(const Spectrum &spectrum)
{
    const auto folded=fft(spectrum,true);
    Polynomial polynomial;
    for(unsigned j=0;j<256;++j) {
        const auto coefficient=folded[j]*std::polar(1.L,-std::numbers::pi_v<long double>*j/512);
        polynomial[j]=coefficient.real();polynomial[j+256]=coefficient.imag();
    }
    return polynomial;
}

inline Ciphertext rotate(const Ciphertext &input,unsigned exponent)
{
    Ciphertext result{};
    for(unsigned c=0;c<3;++c)for(unsigned destination=0;destination<512;++destination) {
        const auto source=(destination+1024-exponent)%1024;
        const auto value=input[c][source%512];
        result[c][destination]=source>=512?0u-value:value;
    }
    return result;
}

inline Ciphertext cmux(const Ciphertext &state,unsigned exponent,const KeyRows &key)
{
    const auto shifted=rotate(state,exponent);
    std::array<Spectrum,3> sums{};
    for(unsigned row=0;row<6;++row) {
        Polynomial digits;
        for(unsigned j=0;j<512;++j) {
            const auto difference=std::uint32_t(shifted[row/2][j]-state[row/2][j]);
            const auto biased=std::uint32_t(difference+0x80808000u);
            digits[j]=static_cast<int>((biased>>(row%2==0?24:16))&255)-128;
        }
        const auto transformed=forward(digits);
        for(unsigned c=0;c<3;++c)for(unsigned j=0;j<256;++j)
            sums[c][j]+=transformed[j]*key[row][c][j];
    }
    Ciphertext result;
    for(unsigned c=0;c<3;++c) {
        const auto polynomial=inverse(sums[c]);
        for(unsigned j=0;j<512;++j)
            result[c][j]=state[c][j]+static_cast<std::uint32_t>(
                static_cast<std::int64_t>(std::round(std::ldexp(polynomial[j],32))));
    }
    return result;
}

template<class Provider>
inline std::array<std::uint32_t,1025> bootstrap(
    const std::array<std::uint32_t,587> &input,
    const std::array<std::uint32_t,512> &lookup,Provider &&provider)
{
    auto modulus_switch=[](std::uint32_t x) {return unsigned((std::uint64_t(x)+2097152)/4194304%1024);};
    Ciphertext state{};state[2]=lookup;
    state=rotate(state,(1024-modulus_switch(input[586]))%1024);
    for(unsigned step=0;step<586;++step) {
        const auto exponent=modulus_switch(input[step]);
        if(exponent)state=cmux(state,exponent,provider(step));
    }
    std::array<std::uint32_t,1025> output;
    for(unsigned c=0;c<2;++c)for(unsigned j=0;j<512;++j)
        output[c*512+j]=j==0?state[c][0]:0u-state[c][512-j];
    output[1024]=state[2][0];return output;
}
} // namespace fpt::paper_reference

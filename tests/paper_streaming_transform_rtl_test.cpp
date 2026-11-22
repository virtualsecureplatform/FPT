#include "VPaperStreamingTransform.h"
#include "fpt/paper_streaming_fft.hpp"
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>

#ifndef TEST_INVERSE
#define TEST_INVERSE 0
#define TEST_LANES 128
#endif
#ifndef TEST_NATIVE
#define TEST_NATIVE 0
#endif
#ifndef TEST_DIGIT_REVERSED
#define TEST_DIGIT_REVERSED 0
#endif
constexpr unsigned lanes=TEST_LANES,beats=256/lanes;
int main()
{
    namespace ps=fpt::paper_streaming;
    VPaperStreamingTransform dut;
    std::mt19937_64 random(211);
    std::deque<ps::Frame> expected;
    ps::Frame input{},golden{};
    unsigned input_phase=0,output_phase=0,checked=0,frames=0;
    for(unsigned cycle=0;cycle<2000;++cycle) {
        dut.clk=0;dut.reset=cycle%389==0;
        dut.input_valid=(cycle<1900 || input_phase!=0) && (input_phase!=0 || random()%4!=0);
        if(!dut.reset && dut.input_valid && input_phase==0) {
            fpt::QuantizationStats stats;
            if constexpr(TEST_INVERSE) {
                for(auto &v:input)v={std::int64_t(random()%(1ULL<<27))-(1LL<<26),
                                     std::int64_t(random()%(1ULL<<27))-(1LL<<26)};
                ps::TransformSchedule schedule{{20,12},26,{}};
                schedule.stage_data=std::array<fpt::FixedFormat,4>{{{20,12},{21,11},{22,10},{23,9}}};
                const auto polynomial=ps::negacyclic_inverse(input,schedule,0,stats,true);
                for(unsigned j=0;j<256;++j)golden[j]={polynomial[j],polynomial[j+256]};
            } else {
                ps::Polynomial polynomial;
                for(auto &v:polynomial)v=(std::int64_t(random()%256)-128)*(1LL<<23);
                for(unsigned j=0;j<256;++j)input[j]={polynomial[j],polynomial[j+256]};
                ps::TransformSchedule schedule{{9,23},26,{}};
                schedule.stage_data=std::array<fpt::FixedFormat,4>{{{11,21},{13,19},{15,17},{17,15}}};
                golden=ps::negacyclic_forward(polynomial,schedule,stats);
            }
            if(stats.overflows)throw std::runtime_error("transform test input exceeds arithmetic schedule");
            if constexpr(TEST_NATIVE) {
                const auto natural_input=input,natural_output=golden;
                for(unsigned j=0;j<256;++j) {
                    const unsigned point=j/4+(j%4)*64;
                    if constexpr(TEST_INVERSE)input[j]=natural_input[point];
                    golden[j]=natural_output[point];
                }
            }
        }
        for(unsigned j=0;j<lanes;++j) {
            unsigned point=input_phase*lanes+j;
            if constexpr(TEST_DIGIT_REVERSED)point=(point%4)*64+((point/4)%4)*16+((point/16)%4)*4+point/64;
            dut.input_real[j]=input[point].real;
            dut.input_imag[j]=input[point].imag;
        }
        dut.eval();
        if(dut.reset){expected.clear();input_phase=0;output_phase=0;}
        else {
            if(dut.fault)throw std::runtime_error("streaming transform raised a fault");
            if(dut.output_valid) {
                if(expected.empty())throw std::runtime_error("unexpected transform frame");
                for(unsigned j=0;j<lanes;++j) {
                    const auto value=expected.front()[output_phase*lanes+j];
                    if(std::uint32_t(dut.output_real[j])!=std::uint32_t(value.real) ||
                       std::uint32_t(dut.output_imag[j])!=std::uint32_t(value.imag)) {
                        std::cerr<<"cycle="<<cycle<<" point="<<output_phase*lanes+j
                                 <<" got="<<std::int32_t(dut.output_real[j])<<','<<std::int32_t(dut.output_imag[j])
                                 <<" expected="<<value.real<<','<<value.imag<<'\n';
                        throw std::runtime_error("streaming transform differs from fixed-point model");
                    }
                    ++checked;
                }
                if(++output_phase==beats){output_phase=0;expected.pop_front();++frames;}
            }
            if(dut.input_valid && ++input_phase==beats){input_phase=0;expected.push_back(golden);}
        }
        dut.clk=1;dut.eval();
    }
    if(!expected.empty() || output_phase || frames<100)throw std::runtime_error("streaming transform drain incomplete");
    dut.final();std::cout<<"PASS transform inverse="<<TEST_INVERSE<<" frames="<<frames<<" complex_values="<<checked<<'\n';
}

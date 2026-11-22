#pragma once
#include "fpt/paper_streaming_fft.hpp"

namespace fpt::paper_streaming {
struct MacSchedule {
    FixedFormat spectrum{15,14};
    FixedFormat key{7,19};
    FixedFormat accumulator{26,33};
};

// Arithmetic state only. Hardware uses two local banks of this spectrum;
// there is no separate mirrored feedback or drain representation.
class SixRowMac {
    MacSchedule schedule_;
    unsigned rows_ = 0;
    std::array<Frame,3> sums_{};
public:
    explicit SixRowMac(MacSchedule schedule) : schedule_(schedule)
    {
        if (!schedule.spectrum.valid() || !schedule.key.valid() ||
            !schedule.accumulator.valid() ||
            schedule.spectrum.fractional_bits + schedule.key.fractional_bits <
                schedule.accumulator.fractional_bits)
            throw std::invalid_argument("invalid streaming MAC format");
    }
    void reset() { rows_=0; }
    unsigned rows() const { return rows_; }
    void push(const Frame &spectrum, const std::array<Frame,3> &key,
              QuantizationStats &stats)
    {
        if(rows_==6)throw std::logic_error("streaming MAC transaction complete");
        const auto shift=schedule_.spectrum.fractional_bits+
            schedule_.key.fractional_bits-schedule_.accumulator.fractional_bits;
        for(unsigned c=0;c<3;++c)for(unsigned j=0;j<256;++j) {
            const auto p=gauss_product(spectrum[j].real,spectrum[j].imag,key[c][j].real,key[c][j].imag);
            const auto real=requantize(p.real,shift,schedule_.accumulator,stats);
            const auto imag=requantize(p.imag,shift,schedule_.accumulator,stats);
            auto &sum=sums_[c][j];
            sum.real=wrap_signed((rows_?__int128{sum.real}:0)+real,schedule_.accumulator,&stats);
            sum.imag=wrap_signed((rows_?__int128{sum.imag}:0)+imag,schedule_.accumulator,&stats);
        }
        ++rows_;
    }
    const std::array<Frame,3> &result() const
    {
        if(rows_!=6)throw std::logic_error("streaming MAC transaction incomplete");
        return sums_;
    }
};
} // namespace fpt::paper_streaming

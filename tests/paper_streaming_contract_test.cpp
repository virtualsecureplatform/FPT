#include "fpt/paper_streaming_contract.hpp"
#include <cassert>
#include <random>

int main()
{
    using namespace fpt::paper_streaming;
    const auto candidates = candidate_geometries();
    assert(candidates.size() == 36);
    for (const auto &candidate : candidates) assert(!candidate.numerical_qualified);
    Resources limit{631200, 1099200, 6592, 606, 0, 0};
    assert(limit.admitted());
    ++limit.ramb18;
    assert(!limit.admitted());
    assert((Resources{0, 0, 0, 598, 0, 1}.admitted()));
    assert(!(Resources{0, 0, 0, 599, 0, 1}.admitted()));
    assert(Scale{}.terminal_normalization() == 8);
    assert((Scale{1, 2}.terminal_normalization() == 5));
    bool rejected = false;
    try { (void)Scale{5, 4}.terminal_normalization(); }
    catch (const std::invalid_argument &) { rejected = true; }
    assert(rejected);
    fpt::QuantizationStats stats;
    for (int raw = -256; raw <= 256; ++raw) {
        const long double scaled = static_cast<long double>(raw) / 8;
        const auto expected = static_cast<std::int64_t>(std::nearbyint(scaled));
        assert(requantize(raw, 3, {10, 0}, stats) == expected);
    }
    assert(stats.overflows == 0);
    assert(requantize(127, 0, {7, 0}, stats) == -1);
    assert(stats.overflows == 1);
    std::mt19937_64 random(42);
    for (int i = 0; i < 10000; ++i) {
        auto operand = [&] { return static_cast<std::int64_t>(random() % (1ULL << 32)) - (1LL << 31); };
        const auto ar = operand(), ai = operand(), br = operand(), bi = operand();
        const auto product = gauss_product(ar, ai, br, bi);
        assert(product.real == __int128{ar} * br - __int128{ai} * bi);
        assert(product.imag == __int128{ar} * bi + __int128{ai} * br);
        const std::array<ComplexProduct, 4> input{{{ar, ai}, {br, bi}, {ai, br}, {bi, ar}}};
        const auto transformed = butterfly4(input, false);
        const auto recovered = butterfly4(transformed, true);
        for (unsigned lane = 0; lane < 4; ++lane) {
            assert(recovered[lane].real == 4 * input[lane].real);
            assert(recovered[lane].imag == 4 * input[lane].imag);
        }
        const std::array<ComplexProduct, 4> impulse{{{1, 0}, {0, 0}, {0, 0}, {0, 0}}};
        for (const auto &value : butterfly4(impulse, false)) {
            assert(value.real == 1 && value.imag == 0);
        }
    }
}

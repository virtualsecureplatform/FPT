#include "FptKernelLayout.hpp"
#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>
#include <xrt/xrt_kernel.h>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <string>

// Queued, distinct input/output buffers with nonzero initialized accumulators.
// Zero keys isolate invocation ordering/prefetch from arithmetic precision.
int main(int argc, char** argv) try {
  using namespace fpt::u280;
  if (argc < 2 || argc > 3) throw std::runtime_error("usage: fpt_chained KERNEL.xclbin [batches=8]");
  const auto batches = argc == 3 ? std::stoul(argv[2]) : 8;
  if (batches < 2 || batches > 1024) throw std::runtime_error("batches must be 2..1024");
  const char* emulationMode=std::getenv("XCL_EMULATION_MODE");
  const bool hwEmulation=emulationMode && std::string(emulationMode)=="hw_emu";
  const auto timeout=std::chrono::minutes(hwEmulation ? 60 : 5);
  std::cout<<"CHAINED_TEST_START mode="<<(hwEmulation ? "hw_emu" : "hw")
    <<" batches="<<batches<<" timeout_minutes="<<timeout.count()<<std::endl;
  xrt::device device{0};
  const auto uuid = device.load_xclbin(argv[1]);
  xrt::kernel kernel{device, uuid, "FptBlindRotateKernel", xrt::kernel::cu_access_mode::exclusive};
  std::vector<xrt::bo> keys;
  for (int i=1; i<=4; ++i) {
    keys.emplace_back(device,kKeyBufferBytes,static_cast<xrt::memory_group>(kernel.group_id(i)));
    std::fill_n(keys.back().map<std::uint64_t*>(),kKeyBufferBytes/8,0);
    keys.back().sync(XCL_BO_SYNC_BO_TO_DEVICE);
  }
  std::vector<xrt::bo> inputs, outputs;
  std::vector<std::unique_ptr<xrt::run>> runs;
  std::vector<std::vector<std::uint32_t>> expected;
  for (std::size_t b=0; b<batches; ++b) {
    inputs.emplace_back(device,kInputBytes,static_cast<xrt::memory_group>(kernel.group_id(0)));
    outputs.emplace_back(device,kOutputBytes,static_cast<xrt::memory_group>(kernel.group_id(5)));
    std::array<std::uint32_t,kContexts> tv{}, bodies{};
    std::array<std::array<std::uint32_t,kDimension>,kContexts> masks{};
    expected.emplace_back(kOutputWords,0);
    for (std::size_t c=0; c<kContexts; ++c) {
      tv[c]=0x10000u+static_cast<std::uint32_t>(b*101+c*17);
      bodies[c]=static_cast<std::uint32_t>(((b+c)*71)%2048)<<21;
      masks[c].fill(static_cast<std::uint32_t>(((b+c)*37)%2048)<<21);
      const auto exponent=(0u-(bodies[c]>>21))&2047u;
      const bool negative=((exponent>>10)!=0)^((exponent&1023u)!=0);
      expected.back()[c*1025+1024]=negative ? 0u-tv[c] : tv[c];
    }
    const auto packed=pack_input_batch(tv,masks,bodies);
    std::copy(packed.begin(),packed.end(),inputs.back().map<std::uint32_t*>());
    std::fill_n(outputs.back().map<std::uint32_t*>(),kOutputWords,0xdeadbeefu);
    inputs.back().sync(XCL_BO_SYNC_BO_TO_DEVICE);
    outputs.back().sync(XCL_BO_SYNC_BO_TO_DEVICE);
    runs.emplace_back(std::make_unique<xrt::run>(kernel));
    runs.back()->set_arg(0,inputs.back());
    for (int k=0;k<4;++k) runs.back()->set_arg(k+1,keys[k]);
    runs.back()->set_arg(5,outputs.back());
  }
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
  const auto acceptedBefore=kernel.read_register(0x58);
  const auto prefetchedBefore=kernel.read_register(0x5c);
  const auto completedBefore=kernel.read_register(0x60);
#pragma GCC diagnostic pop
  const auto start=std::chrono::steady_clock::now();
  for (auto& run:runs) run->start(); // No per-batch wait on the submission path.
  std::cout<<"CHAINED_TEST_SUBMITTED batches="<<batches<<std::endl;
  for (std::size_t b=0; b<runs.size(); ++b) {
    if (runs[b]->wait(timeout)!=ERT_CMD_STATE_COMPLETED)
      throw std::runtime_error("chained completion timeout at batch="+std::to_string(b));
    std::cout<<"CHAINED_TEST_COMPLETED batch="<<b<<std::endl;
  }
  const double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
  for (std::size_t b=0;b<batches;++b) {
    outputs[b].sync(XCL_BO_SYNC_BO_FROM_DEVICE);
    const auto actual=outputs[b].map<const std::uint32_t*>();
    for (std::size_t i=0;i<kOutputWords;++i)
      if(actual[i]!=expected[b][i]) throw std::runtime_error("batch="+std::to_string(b)+" word="+std::to_string(i)+" mismatch");
  }
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
  const auto status=kernel.read_register(0x30);
  const auto accepted=kernel.read_register(0x58)-acceptedBefore;
  const auto prefetched=kernel.read_register(0x5c)-prefetchedBefore;
  const auto completed=kernel.read_register(0x60)-completedBefore;
#pragma GCC diagnostic pop
  if(status&1u) throw std::runtime_error("chained DMA failure");
  if(accepted!=batches || completed!=batches || prefetched==0)
    throw std::runtime_error("chained overlap not demonstrated: accepted="+std::to_string(accepted)+" prefetched="+std::to_string(prefetched)+" completed="+std::to_string(completed));
  std::cout<<"CHAINED_TEST_PASS batches="<<batches<<" mode="<<(hwEmulation ? "hw_emu" : "hw")
    <<" words_per_batch="<<kOutputWords
    <<" accepted="<<accepted<<" prefetched_while_active="<<prefetched<<" completed="<<completed
    <<" host_elapsed_ms="<<ms;
  if (!hwEmulation) std::cout<<" results_per_ms="<<(batches*kContexts/ms);
  std::cout<<std::endl;
} catch (const std::exception& error) {
  std::cerr<<"Chained test failed: "<<error.what()<<'\n'; return 1;
}

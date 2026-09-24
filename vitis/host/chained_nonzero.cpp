#include "FptKernelLayout.hpp"
#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>
#include <xrt/xrt_kernel.h>
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <limits>
#include <memory>
#include <string>

// Four queued golden jobs alternate key placement and reverse context order.
// Inactive key rows are zero, so the validated single-CMUX oracle extends to
// n=630 without changing its result. All input/output allocations are distinct.
int main(int argc, char** argv) try {
  using namespace fpt::u280;
  if (argc != 4) throw std::runtime_error("usage: fpt_chained_nonzero XCLBIN ORACLE_VECTORS RTL_EXPECTED");
  std::ifstream fixture(argv[2]), golden(argv[3]);
  auto read = [](std::istream& file) {
    std::int64_t value;
    if (!(file >> value)) throw std::runtime_error("missing vector value");
    return value;
  };
  auto word = [&](std::istream& file) {
    const auto value = read(file);
    if (value < 0 || value > std::numeric_limits<std::uint32_t>::max())
      throw std::runtime_error("invalid torus word");
    return static_cast<std::uint32_t>(value);
  };
  std::array<std::uint32_t,kContexts> tv{}, activeMasks{}, bodies{};
  for (std::size_t c=0; c<kContexts; ++c) {
    tv[c]=word(fixture); activeMasks[c]=word(fixture); bodies[c]=word(fixture);
    if ((activeMasks[c]&((1u<<21)-1)) || (bodies[c]&((1u<<21)-1)))
      throw std::runtime_error("fixture inputs must be modulus switched");
  }
  std::array<std::array<ComplexKeyLane,2>,2048> key{};
  bool nonzeroKey=false;
  for (auto& point:key) for (auto& component:point) {
    const auto real=read(fixture), imag=read(fixture);
    if (real<-(1<<26) || real>=(1<<26) || imag<-(1<<26) || imag>=(1<<26))
      throw std::runtime_error("invalid 27-bit key");
    component={static_cast<std::int32_t>(real),static_cast<std::int32_t>(imag)};
    nonzeroKey |= real!=0 || imag!=0;
  }
  std::vector<std::uint32_t> expected(kOutputWords);
  for (auto& value:expected) { word(fixture); value=word(golden); }
  std::string extra;
  if (!nonzeroKey || (fixture>>extra) || (golden>>extra)) throw std::runtime_error("invalid fixture");
  const char* mode=std::getenv("XCL_EMULATION_MODE");
  const bool emulation=mode && std::string(mode)=="hw_emu";
  const auto timeout=std::chrono::minutes(emulation ? 60 : 5);
  xrt::device device{0};
  const auto uuid=device.load_xclbin(argv[1]);
  xrt::kernel kernel{device,uuid,"FptBlindRotateKernel",xrt::kernel::cu_access_mode::exclusive};
  std::array<std::vector<xrt::bo>,2> keys;
  for (std::size_t set=0; set<2; ++set) {
    for (int stream=0; stream<4; ++stream) {
      keys[set].emplace_back(device,kKeyBufferBytes,static_cast<xrt::memory_group>(kernel.group_id(stream+1)));
      std::fill_n(keys[set].back().map<std::uint64_t*>(),kKeyBufferBytes/8,0);
    }
    const std::size_t active=set ? 629 : 0;
    for (std::size_t beat=0; beat<128; ++beat) for (std::size_t stream=0; stream<2; ++stream) {
      std::array<ComplexKeyLane,16> lanes{};
      for (std::size_t lane=0; lane<16; ++lane) {
        const auto scalar=stream*16+lane;
        const auto point=(beat/32)*512+(beat%32)*16+scalar/2;
        lanes[lane]=key[point][scalar%2];
      }
      const auto packed=pack_key_beat(lanes);
      keys[set][stream*2].map<Axi512Word*>()[active*128+beat]=packed.first;
      keys[set][stream*2+1].map<Axi512Word*>()[active*128+beat]=packed.second;
    }
    for (auto& buffer:keys[set]) buffer.sync(XCL_BO_SYNC_BO_TO_DEVICE);
  }
  std::vector<xrt::bo> inputs, outputs;
  std::vector<std::unique_ptr<xrt::run>> runs;
  for (std::size_t batch=0; batch<4; ++batch) {
    inputs.emplace_back(device,kInputBytes,static_cast<xrt::memory_group>(kernel.group_id(0)));
    outputs.emplace_back(device,kOutputBytes+64,static_cast<xrt::memory_group>(kernel.group_id(5)));
    std::array<std::uint32_t,kContexts> batchTv{}, batchBodies{};
    std::array<std::array<std::uint32_t,kDimension>,kContexts> masks{};
    for (std::size_t c=0; c<kContexts; ++c) {
      const auto source=batch%2 ? kContexts-1-c : c;
      batchTv[c]=tv[source]; batchBodies[c]=bodies[source];
      masks[c][batch%2 ? 629 : 0]=activeMasks[source];
    }
    const auto packed=pack_input_batch(batchTv,masks,batchBodies);
    std::copy(packed.begin(),packed.end(),inputs.back().map<std::uint32_t*>());
    std::fill_n(outputs.back().map<std::uint32_t*>(),kOutputWords+16,0xdeadbeefu);
    inputs.back().sync(XCL_BO_SYNC_BO_TO_DEVICE); outputs.back().sync(XCL_BO_SYNC_BO_TO_DEVICE);
    runs.emplace_back(std::make_unique<xrt::run>(kernel));
    runs.back()->set_arg(0,inputs.back());
    for (int i=0;i<4;++i) runs.back()->set_arg(i+1,keys[batch%2][i]);
    runs.back()->set_arg(5,outputs.back());
  }
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
  const auto acceptedBefore=kernel.read_register(0x58);
  const auto prefetchedBefore=kernel.read_register(0x5c);
  const auto completedBefore=kernel.read_register(0x60);
#pragma GCC diagnostic pop
  for (auto& run:runs) run->start();
  std::cout<<"CHAINED_NONZERO_SUBMITTED batches=4 mode="<<(emulation ? "hw_emu" : "hw")<<std::endl;
  for (std::size_t batch=0;batch<4;++batch) {
    if (runs[batch]->wait(timeout)!=ERT_CMD_STATE_COMPLETED) throw std::runtime_error("queued golden timeout");
    outputs[batch].sync(XCL_BO_SYNC_BO_FROM_DEVICE);
    const auto actual=outputs[batch].map<const std::uint32_t*>();
    for (std::size_t i=0;i<kOutputWords;++i) {
      const auto c=i/1025, offset=i%1025;
      const auto source=batch%2 ? kContexts-1-c : c;
      if (actual[i]!=expected[source*1025+offset])
        throw std::runtime_error("batch="+std::to_string(batch)+" word="+std::to_string(i)+" mismatch");
    }
    for (std::size_t i=kOutputWords;i<kOutputWords+16;++i)
      if(actual[i]!=0xdeadbeefu) throw std::runtime_error("output guard overwritten");
    std::cout<<"CHAINED_NONZERO_BATCH_PASS batch="<<batch<<" words="<<kOutputWords<<std::endl;
  }
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
  if ((kernel.read_register(0x30)&1u) || kernel.read_register(0x58)-acceptedBefore!=4 ||
      kernel.read_register(0x60)-completedBefore!=4 || kernel.read_register(0x5c)-prefetchedBefore!=3)
    throw std::runtime_error("queued golden status/overlap mismatch");
#pragma GCC diagnostic pop
  std::cout<<"CHAINED_NONZERO_PASS batches=4 words=65600 alternating_keys=2 guarded_outputs=4"<<std::endl;
} catch (const std::exception& error) {
  std::cerr<<"Chained nonzero test failed: "<<error.what()<<std::endl; return 1;
}

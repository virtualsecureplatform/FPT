#pragma once
#include "fpt/paper_dma_layout.hpp"
#include "fpt/paper_grouped_key_packing.hpp"
#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>
#include <xrt/xrt_kernel.h>
#include <chrono>
#include <string>

namespace fpt::paper_runtime {
// Keys stay resident across jobs. Inputs and outputs retain the raw Torus32 API.
class Runtime {
    xrt::device device_;
    xrt::uuid uuid_;
    xrt::kernel kernel_;
    std::array<xrt::bo,4> keys_;
    bool loaded_=false;
    static xrt::uuid load_image(xrt::device &device,const std::string &image,const std::string &expected_bdf) {
        if(!expected_bdf.empty() && device.get_info<xrt::info::device::bdf>()!=expected_bdf)
            throw std::runtime_error("selected device does not match the expected U280 BDF");
        return device.load_xclbin(image);
    }
    std::uint32_t read(unsigned offset) {
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
        return kernel_.read_register(offset);
#pragma GCC diagnostic pop
    }
public:
    struct Result {
        std::vector<std::array<paper_dma_layout::Sample,12>> samples;
        std::uint32_t cycles,first_compute_cycle,last_compute_cycle;
        double launch_seconds;
    };
    Runtime(const std::string &image,unsigned device_index=0,const std::string &expected_bdf={})
      :device_(device_index),uuid_(load_image(device_,image,expected_bdf)),
       kernel_(device_,uuid_,"FptPaperStreamingKernel",xrt::kernel::cu_access_mode::exclusive) {
        if(read(0x7c)!=paper_dma_layout::profile_id)throw std::runtime_error("paper streaming kernel profile mismatch");
        for(unsigned p=0;p<4;++p)keys_[p]=xrt::bo(device_,paper_grouped_key_packing::key_bytes(p),kernel_.group_id(p+1));
    }
    std::string bdf() const {return device_.get_info<xrt::info::device::bdf>();}
    std::array<unsigned,4> clock_frequencies_mhz() const {
        xclDeviceInfo2 info{};
        if(xclGetDeviceInfo2(device_,&info)!=0)throw std::runtime_error("cannot read device clock frequencies");
        return {info.mOCLFrequency[0],info.mOCLFrequency[1],info.mOCLFrequency[2],info.mOCLFrequency[3]};
    }
    void load_keys(const std::array<std::span<const std::uint8_t>,4> &images) {
        for(unsigned p=0;p<4;++p)if(images[p].size()!=paper_grouped_key_packing::key_bytes(p))
            throw std::invalid_argument("incorrect grouped key image size");
        loaded_=false;
        for(unsigned p=0;p<4;++p) {
            std::copy(images[p].begin(),images[p].end(),keys_[p].map<std::uint8_t*>());
            keys_[p].sync(XCL_BO_SYNC_BO_TO_DEVICE);
        }
        loaded_=true;
    }
    Result run(std::span<const paper_dma_layout::InputFrame> frames) {
        if(!loaded_)throw std::logic_error("load keys before starting a job");
        const auto packed=paper_dma_layout::pack(frames);
        xrt::bo input(device_,packed.size(),kernel_.group_id(0));
        xrt::bo output(device_,frames.size()*paper_dma_layout::output_stride,kernel_.group_id(5));
        std::copy(packed.begin(),packed.end(),input.map<std::uint8_t*>());
        input.sync(XCL_BO_SYNC_BO_TO_DEVICE);
        const auto begin=std::chrono::steady_clock::now();
        auto execution=kernel_(input,keys_[0],keys_[1],keys_[2],keys_[3],output,std::uint32_t(frames.size()));
        const auto state=execution.wait(std::chrono::seconds(120));
        const auto end=std::chrono::steady_clock::now();
        if(state==ERT_CMD_STATE_TIMEOUT) {
            // XRT wait does not cancel on timeout. Abort synchronously before
            // the input/output buffer objects leave scope.
            execution.abort();
            throw std::runtime_error("paper streaming kernel timed out; run aborted");
        }
        if(state!=ERT_CMD_STATE_COMPLETED)throw std::runtime_error("paper streaming kernel failed to complete");
        const auto status=read(0x30);
        if(status&0x800001ffu)throw std::runtime_error("paper streaming kernel DMA/engine error: "+std::to_string(status));
        Result result;
        result.cycles=read(0x40);result.first_compute_cycle=read(0x64);result.last_compute_cycle=read(0x68);
        if(read(0x58)!=frames.size() || read(0x5c)!=frames.size() || read(0x60)!=frames.size())
            throw std::runtime_error("paper streaming kernel completion counter mismatch");
        result.launch_seconds=std::chrono::duration<double>(end-begin).count();
        output.sync(XCL_BO_SYNC_BO_FROM_DEVICE);
        result.samples=paper_dma_layout::unpack({output.map<const std::uint8_t*>(),output.size()},frames.size());
        return result;
    }
};
} // namespace fpt::paper_runtime

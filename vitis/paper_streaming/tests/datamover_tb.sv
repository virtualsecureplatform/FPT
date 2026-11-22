`timescale 1ns/1ps
// Exercise the exact generated 512-bit DMA configuration with AXI stalls,
// 4 KiB boundaries, partial final writes and non-OKAY memory responses.
module datamover_tb;
  reg clk=0;always #2.5 clk=~clk;
  reg resetn=0,inject_error=0;
  reg read_command_valid=0,write_command_valid=0;
  wire read_command_ready,write_command_ready;
  reg [103:0] read_command,write_command;
  wire read_status_valid,write_status_valid,read_error,write_error;
  wire [7:0] read_status,write_status;
  wire arvalid,arready,rvalid,rready,rlast;
  wire [63:0] araddr;
  wire [7:0] arlen;
  wire [2:0] arsize;
  wire [1:0] arburst;
  wire [511:0] rdata,read_data;
  wire read_valid,read_last,read_ready;
  wire [63:0] read_keep;
  wire awvalid,awready,wvalid,wready,wlast,bvalid,bready;
  wire [63:0] awaddr,wstrb;
  wire [7:0] awlen;
  wire [2:0] awsize;
  wire [1:0] awburst;
  wire [511:0] wdata,write_data;
  wire write_valid,write_ready,write_last;
  wire [63:0] write_keep;
  integer cycle=0,read_left=0,write_left=0,response_delay=0;
  integer read_words=0,write_beats=0,bytes_written=0;
  integer read_statuses=0,write_statuses=0,bursts_read=0,bursts_written=0;
  reg [63:0] read_address=0,write_address=0,read_base=0,write_base=0;
  reg source_active=0;
  reg [31:0] random_state=32'h84bf39a1;
  reg csr_awvalid=0,csr_wvalid=0,csr_bready=0,csr_arvalid=0,csr_rready=0;
  reg [6:0] csr_awaddr,csr_araddr;
  reg [31:0] csr_wdata;
  reg [3:0] csr_wstrb;
  wire csr_awready,csr_wready,csr_bvalid,csr_arready,csr_rvalid,csr_start;
  wire [31:0] csr_rdata,csr_batches;
  wire [1:0] csr_rresp,csr_bresp;
  wire [63:0] csr_input;
  reg csr_ap_ready=0,csr_ap_done=0;
  FptPaperStreamingKernel_control_s_axi control(
    .ACLK(clk),.ARESET(!resetn),.ACLK_EN(1'b1),
    .AWVALID(csr_awvalid),.AWREADY(csr_awready),.AWADDR(csr_awaddr),
    .WVALID(csr_wvalid),.WREADY(csr_wready),.WDATA(csr_wdata),.WSTRB(csr_wstrb),
    .BVALID(csr_bvalid),.BREADY(csr_bready),.BRESP(csr_bresp),
    .ARVALID(csr_arvalid),.ARREADY(csr_arready),.ARADDR(csr_araddr),
    .RVALID(csr_rvalid),.RREADY(csr_rready),.RDATA(csr_rdata),.RRESP(csr_rresp),.interrupt(),
    .ap_start(csr_start),.ap_continue(),.ap_done(csr_ap_done),.ap_ready(csr_ap_ready),.ap_idle(1'b1),
    .input_ptr(csr_input),.key_low_ptr(),.key_high_ptr(),.key_low1_ptr(),.key_high1_ptr(),.output_ptr(),.batch_count(csr_batches),
    .kernel_status(32'h123),.debug_key_beats(32'd1234),.debug_key_starved_cycles(32'd0),
    .debug_key_bank_blocked_cycles(32'd4),.debug_run_cycles(32'd279736),
    .chain_accepted(32'd3),.chain_prefetched(32'd3),.chain_completed(32'd3),
    .preparation_cycles(32'd13754),.setup_cycles(32'd182712),.compute_cycles(32'd168958),
    .output_tail_cycles(32'd97024),.dma_wait_cycles(32'd0));
  task automatic csr_write(input [6:0] address,input [31:0] value,input [3:0] strobes);
    begin
      @(negedge clk);csr_awaddr=address;csr_awvalid=1;
      do @(posedge clk);while(!csr_awready);
      @(negedge clk);csr_awvalid=0;
      repeat(2)@(negedge clk);
      csr_wdata=value;csr_wstrb=strobes;csr_wvalid=1;
      do @(posedge clk);while(!csr_wready);
      @(negedge clk);csr_wvalid=0;
      repeat(3)begin
        @(negedge clk);
        if(!csr_bvalid || csr_bresp!=0)$fatal(1,"AXI Lite write response failed to hold");
      end
      csr_bready=1;@(posedge clk);@(negedge clk);csr_bready=0;
    end
  endtask
  task automatic csr_read(input [6:0] address,input [31:0] expected);
    begin
      @(negedge clk);csr_araddr=address;csr_arvalid=1;
      do @(posedge clk);while(!csr_arready);
      @(negedge clk);csr_arvalid=0;
      repeat(3)begin
        @(negedge clk);
        if(!csr_rvalid || csr_rresp!=0 || csr_rdata!==expected)
          $fatal(1,"AXI Lite read/hold mismatch address=%h value=%h expected=%h",address,csr_rdata,expected);
      end
      csr_rready=1;@(posedge clk);@(negedge clk);csr_rready=0;
    end
  endtask
  task automatic check_control;
    begin
      csr_read(7'h7c,32'h46505432);csr_read(7'h78,1);
      csr_write(7'h78,32'hff001234,4'b0011);csr_read(7'h78,32'h1234);
      if(csr_batches!=32'h1234)$fatal(1,"batch count output mismatch");
      csr_write(7'h78,3,4'b1111);
      csr_write(7'h10,32'h89abcdef,4'b1111);csr_write(7'h14,32'h01234567,4'b1111);
      csr_write(7'h10,32'h00ff0000,4'b0100);
      if(csr_input!==64'h0123456789ffcdef)$fatal(1,"AXI Lite pointer strobes mismatch");
      csr_read(7'h10,32'h89ffcdef);csr_read(7'h14,32'h01234567);
      csr_read(7'h30,32'h123);csr_read(7'h40,32'd279736);
      csr_read(7'h58,3);csr_read(7'h5c,3);csr_read(7'h60,3);
      csr_read(7'h64,32'd13754);csr_read(7'h68,32'd182712);
      csr_write(7'h00,1,4'b0001);
      if(!csr_start)$fatal(1,"AP start was not held pending acceptance");
      csr_ap_ready=1;@(posedge clk);@(negedge clk);
      if(csr_start)$fatal(1,"AP start was not cleared after acceptance");
      csr_ap_ready=0;csr_ap_done=1;@(posedge clk);@(negedge clk);csr_ap_done=0;
      csr_read(7'h00,32'he);csr_read(7'h00,32'h4);
      $display("PASS_PAPER_CONTROL profile pointer_strobes batch_count metrics start done read_backpressure write_backpressure");
    end
  endtask
  function automatic [31:0] pattern(input [63:0] address);
    pattern=32'(address>>2)^32'h78563412;
  endfunction
  function automatic [511:0] pattern_beat(input [63:0] address);
    integer word_index;
    for(word_index=0;word_index<16;word_index=word_index+1)
      pattern_beat[word_index*32+:32]=pattern(address+64'(word_index*4));
  endfunction
  assign arready=resetn && read_left==0 && random_state[0];
  assign rvalid=resetn && read_left!=0;
  assign rlast=read_left==1;
  assign rdata=pattern_beat(read_address);
  assign read_ready=resetn && random_state[2:1]!=0;
  assign awready=resetn && write_left==0 && response_delay==0 && random_state[3];
  assign wready=resetn && write_left!=0 && random_state[5:4]!=0;
  assign bvalid=resetn && response_delay==1;
  // Source payload remains stable until accepted. AXI memory independently
  // stalls WREADY; the final beat contains exactly 48 useful bytes.
  assign write_valid=source_active && write_beats<769;
  assign write_last=write_beats==768;
  assign write_keep=write_last?64'h0000ffffffffffff:64'hffffffffffffffff;
  assign write_data=pattern_beat(write_base+64'(write_beats*64));
  axi_datamover_mm2s reader(
    .m_axi_mm2s_aclk(clk),.m_axi_mm2s_aresetn(resetn),.mm2s_err(read_error),
    .m_axis_mm2s_cmdsts_aclk(clk),.m_axis_mm2s_cmdsts_aresetn(resetn),
    .s_axis_mm2s_cmd_tvalid(read_command_valid),.s_axis_mm2s_cmd_tready(read_command_ready),.s_axis_mm2s_cmd_tdata(read_command),
    .m_axis_mm2s_sts_tvalid(read_status_valid),.m_axis_mm2s_sts_tready(1'b1),.m_axis_mm2s_sts_tdata(read_status),
    .m_axis_mm2s_sts_tkeep(),.m_axis_mm2s_sts_tlast(),.m_axi_mm2s_arid(),
    .m_axi_mm2s_araddr(araddr),.m_axi_mm2s_arlen(arlen),.m_axi_mm2s_arsize(arsize),.m_axi_mm2s_arburst(arburst),
    .m_axi_mm2s_arprot(),.m_axi_mm2s_arcache(),.m_axi_mm2s_aruser(),.m_axi_mm2s_arvalid(arvalid),.m_axi_mm2s_arready(arready),
    .m_axi_mm2s_rdata(rdata),.m_axi_mm2s_rresp(inject_error?2'b10:2'b00),.m_axi_mm2s_rlast(rlast),.m_axi_mm2s_rvalid(rvalid),.m_axi_mm2s_rready(rready),
    .m_axis_mm2s_tdata(read_data),.m_axis_mm2s_tkeep(read_keep),.m_axis_mm2s_tlast(read_last),.m_axis_mm2s_tvalid(read_valid),.m_axis_mm2s_tready(read_ready));
  axi_datamover_s2mm writer(
    .m_axi_s2mm_aclk(clk),.m_axi_s2mm_aresetn(resetn),.s2mm_err(write_error),
    .m_axis_s2mm_cmdsts_awclk(clk),.m_axis_s2mm_cmdsts_aresetn(resetn),
    .s_axis_s2mm_cmd_tvalid(write_command_valid),.s_axis_s2mm_cmd_tready(write_command_ready),.s_axis_s2mm_cmd_tdata(write_command),
    .m_axis_s2mm_sts_tvalid(write_status_valid),.m_axis_s2mm_sts_tready(1'b1),.m_axis_s2mm_sts_tdata(write_status),
    .m_axis_s2mm_sts_tkeep(),.m_axis_s2mm_sts_tlast(),.m_axi_s2mm_awid(),
    .m_axi_s2mm_awaddr(awaddr),.m_axi_s2mm_awlen(awlen),.m_axi_s2mm_awsize(awsize),.m_axi_s2mm_awburst(awburst),
    .m_axi_s2mm_awprot(),.m_axi_s2mm_awcache(),.m_axi_s2mm_awuser(),.m_axi_s2mm_awvalid(awvalid),.m_axi_s2mm_awready(awready),
    .m_axi_s2mm_wdata(wdata),.m_axi_s2mm_wstrb(wstrb),.m_axi_s2mm_wlast(wlast),.m_axi_s2mm_wvalid(wvalid),.m_axi_s2mm_wready(wready),
    .m_axi_s2mm_bresp(inject_error?2'b10:2'b00),.m_axi_s2mm_bvalid(bvalid),.m_axi_s2mm_bready(bready),
    .s_axis_s2mm_tdata(write_data),.s_axis_s2mm_tkeep(write_keep),.s_axis_s2mm_tlast(write_last),.s_axis_s2mm_tvalid(write_valid),.s_axis_s2mm_tready(write_ready));
  integer byte_index;
  always @(posedge clk)begin
    cycle<=cycle+1;
    random_state<={random_state[30:0],random_state[31]^random_state[21]^random_state[1]^random_state[0]};
    if(!resetn)begin
      read_left<=0;write_left<=0;response_delay<=0;
      read_words<=0;write_beats<=0;bytes_written<=0;read_statuses<=0;write_statuses<=0;
    end else begin
      if(arvalid && arready)begin
        if(arsize!=6 || arburst!=1 || araddr[5:0]!=0 || int'(araddr[11:0])+64*(int'(arlen)+1)>4096)
          $fatal(1,"invalid AXI read burst geometry");
        read_address<=araddr;read_left<=int'(arlen)+1;bursts_read<=bursts_read+1;
      end
      if(rvalid && rready)begin read_address<=read_address+64;read_left<=read_left-1;end
      if(read_valid && read_ready)begin
        if(read_data!==pattern_beat(read_base+64'(read_words*4)) || read_keep!==64'hffffffffffffffff || read_last!==(read_words==7552))
          $fatal(1,"MM2S payload/keep/last mismatch at word %d",read_words);
        read_words<=read_words+16;
      end
      if(awvalid && awready)begin
        if(awsize!=6 || awburst!=1 || awaddr[5:0]!=0 || int'(awaddr[11:0])+64*(int'(awlen)+1)>4096)
          $fatal(1,"invalid AXI write burst geometry");
        write_address<=awaddr;write_left<=int'(awlen)+1;bursts_written<=bursts_written+1;
      end
      if(write_valid && write_ready)write_beats<=write_beats+1;
      if(wvalid && wready)begin
        if(wlast!==(write_left==1))$fatal(1,"AXI WLAST mismatch");
        for(byte_index=0;byte_index<64;byte_index=byte_index+1)begin
          if(wstrb[byte_index] !== (write_address+64'(byte_index)<write_base+49200))$fatal(1,"write byte enable escaped output payload");
          if(wstrb[byte_index] && wdata[byte_index*8+:8] !== (pattern(write_address+64'(byte_index)) >> ((byte_index%4)*8) & 8'hff))
            $fatal(1,"AXI write payload mismatch");
        end
        bytes_written<=bytes_written+(write_address+64<write_base+49200?64:48);
        write_address<=write_address+64;write_left<=write_left-1;
        if(write_left==1)response_delay<=18;
      end
      if(response_delay>1)response_delay<=response_delay-1;
      if(bvalid && bready)response_delay<=0;
      if(read_status_valid)begin
        if(inject_error ? read_status[6:4]==0 : read_status!==8'h80)$fatal(1,"MM2S failed to report AXI response status");
        read_statuses<=read_statuses+1;
      end
      if(write_status_valid)begin
        if(inject_error ? write_status[6:4]==0 : write_status!==8'h80)$fatal(1,"S2MM failed to report AXI response status");
        if(bytes_written!=49200 || response_delay!=0)$fatal(1,"write completion preceded final BRESP");
        write_statuses<=write_statuses+1;
      end
    end
    if(cycle>200000)$fatal(1,"DataMover simulation timeout");
  end
  task automatic frame(input integer index,input bit fail_response);
    begin
      @(negedge clk);
      read_base=64'h100000+64'(index*30272);write_base=64'h400000+64'(index*49216);
      read_words=0;write_beats=0;bytes_written=0;inject_error=fail_response;
      read_command={8'b0,read_base,32'h40807640}; // 30272 bytes
      write_command={8'b0,write_base,32'h4080c030}; // 49200 bytes
      read_command_valid=1;
      do @(posedge clk);while(!read_command_ready);
      @(negedge clk);read_command_valid=0;write_command_valid=1;
      do @(posedge clk);while(!write_command_ready);
      @(negedge clk);write_command_valid=0;source_active=1;
      wait(read_statuses==index+1 && write_statuses==index+1);
      @(negedge clk);source_active=0;
      if(read_words!=7568 || write_beats!=769 || bytes_written!=49200)$fatal(1,"DataMover transfer size mismatch");
      $display("frame=%0d read_words=%0d output_bytes=%0d injected_error=%0d",index,read_words,bytes_written,fail_response);
    end
  endtask
  initial begin
    repeat(24)@(negedge clk);resetn=1;repeat(24)@(negedge clk);
    check_control();
    frame(0,0);frame(1,0);frame(2,1);
    resetn=0;repeat(24)@(negedge clk);resetn=1;repeat(24)@(negedge clk);
    frame(0,0);
    $display("PASS_PAPER_VENDOR_DMA frames=4 read_bursts=%0d write_bursts=%0d stalls partial_write AXI_errors reset",bursts_read,bursts_written);
    $finish;
  end
endmodule

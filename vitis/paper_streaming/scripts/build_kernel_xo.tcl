# Build the opt-in streaming kernel from a frozen source tree. This script
# generates the same vendor DataMover family used by the original U280 shell.
# Usage: vivado -mode batch -source THIS -tclargs NEW_BUILD_DIRECTORY ?ip-only|dma-sim|kernel-synth?
if {[llength $argv]<1 || [llength $argv]>2} {error "usage: NEW_BUILD_DIRECTORY ?ip-only|dma-sim|kernel-synth?"}
set output [file normalize [lindex $argv 0]]
set ip_only [expr {[llength $argv]==2 && [lindex $argv 1] eq "ip-only"}]
set kernel_synth [expr {[llength $argv]==2 && [lindex $argv 1] eq "kernel-synth"}]
set dma_sim [expr {[llength $argv]==2 && [lindex $argv 1] eq "dma-sim"}]
if {[llength $argv]==2 && !$ip_only && !$kernel_synth && !$dma_sim} {error "invalid build mode"}
set script_dir [file dirname [file normalize [info script]]]
set backend_dir [file dirname $script_dir]
set repo_dir [file normalize [file join $backend_dir ../..]]
set resource_dir [file join $repo_dir chisel src main resources]
set project_dir [file join $output project]
if {[file exists $project_dir]} {error "refusing to overwrite $project_dir"}
file mkdir $output
create_project FptPaperStreamingKernel_project $project_dir -part xcu280-fsvh2892-2L-e
set_property target_language Verilog [current_project]
set sources [glob [file join $resource_dir PaperStreaming*.sv]]
lappend sources [file join $resource_dir PaperStreamingTwiddles.svh]
lappend sources [file join $backend_dir rtl FptPaperStreamingKernel.sv]
lappend sources [file join $backend_dir rtl FptPaperStreamingKernel_control_s_axi.sv]
import_files -norecurse $sources
set_property top FptPaperStreamingKernel [current_fileset]
set_property include_dirs [list $resource_dir] [current_fileset]

create_ip -name axi_datamover -vendor xilinx.com -library ip -version 5.1 -module_name axi_datamover_mm2s
set_property -dict [list CONFIG.c_enable_s2mm {0} CONFIG.c_addr_width {64} \
  CONFIG.c_m_axi_mm2s_addr_width {64} CONFIG.c_m_axi_mm2s_data_width {512} \
  CONFIG.c_m_axis_mm2s_tdata_width {512} CONFIG.c_mm2s_btt_used {23} \
  CONFIG.c_include_mm2s_stsfifo {true} CONFIG.c_mm2s_stscmd_is_async {false} \
  CONFIG.c_mm2s_burst_size {64} CONFIG.c_m_axi_mm2s_id_width {1} \
  CONFIG.c_include_mm2s_dre {false} CONFIG.c_mm2s_include_sf {false}] [get_ips axi_datamover_mm2s]
create_ip -name axi_datamover -vendor xilinx.com -library ip -version 5.1 -module_name axi_datamover_s2mm
set_property -dict [list CONFIG.c_enable_mm2s {0} CONFIG.c_addr_width {64} \
  CONFIG.c_m_axi_s2mm_addr_width {64} CONFIG.c_m_axi_s2mm_data_width {512} \
  CONFIG.c_s_axis_s2mm_tdata_width {512} CONFIG.c_s2mm_btt_used {23} \
  CONFIG.c_include_s2mm_stsfifo {true} CONFIG.c_s2mm_stscmd_is_async {false} \
  CONFIG.c_s2mm_burst_size {64} CONFIG.c_s2mm_support_indet_btt {false} \
  CONFIG.c_m_axi_s2mm_id_width {4} CONFIG.c_include_s2mm_dre {false}] [get_ips axi_datamover_s2mm]
generate_target all [get_ips]
update_compile_order -fileset sources_1
if {$dma_sim} {
  add_files -fileset sim_1 [file join $backend_dir tests datamover_tb.sv]
  set_property top datamover_tb [get_filesets sim_1]
  set_property xsim.simulate.runtime all [get_filesets sim_1]
  launch_simulation
  close_sim
  exit
}
if {$kernel_synth} {
  synth_ip [get_ips]
  synth_design -top FptPaperStreamingKernel -part xcu280-fsvh2892-2L-e -mode out_of_context
  source [file join $script_dir resolve_datamovers.tcl]
  paper_resolve_datamovers \
    [file join [get_property IP_OUTPUT_DIR [get_ips axi_datamover_mm2s]] axi_datamover_mm2s.dcp] \
    [file join [get_property IP_OUTPUT_DIR [get_ips axi_datamover_s2mm]] axi_datamover_s2mm.dcp]
  create_clock -name ap_clk -period 5 [get_ports ap_clk]
  write_checkpoint [file join $output post_synth.dcp]
  report_timing_summary -file [file join $output post_synth_timing.rpt]
  set ::env(FPT_KERNEL_METRICS_PREFIX) [file join $output kernel_post_synth]
  source [file join $script_dir check_kernel_resources.tcl]
  puts "PAPER_KERNEL_SYNTH_COMPLETE: platform routing and board qualification remain required"
  exit
}
if {$ip_only} {
  synth_ip [get_ips]
  foreach name {axi_datamover_mm2s axi_datamover_s2mm} {
    # synth_ip emits an out-of-context DCP without creating a project run.
    open_checkpoint [file join [get_property IP_OUTPUT_DIR [get_ips $name]] ${name}.dcp]
    report_utilization -hierarchical -file [file join $output ${name}_utilization.rpt]
    close_design
  }
  puts "PAPER_DATAMOVER_SYNTH_ONLY_COMPLETE: no kernel timing or resource admission"
  exit
}
set ip_repo [file join $output ip_repo]
ipx::package_project -root_dir $ip_repo -vendor fpt.example -library kernel -taxonomy /KernelIP -import_files -set_current true
set_property sdx_kernel true [ipx::current_core]
set_property sdx_kernel_type rtl [ipx::current_core]
ipx::associate_bus_interfaces -busif s_axi_control -clock ap_clk [ipx::current_core]
foreach bus {m_axi_input m_axi_key_low m_axi_key_high m_axi_key_low1 m_axi_key_high1 m_axi_output} {
  ipx::associate_bus_interfaces -busif $bus -clock ap_clk [ipx::current_core]
}
ipx::create_xgui_files [ipx::current_core]
ipx::update_checksums [ipx::current_core]
ipx::save_core [ipx::current_core]
close_project
package_xo -xo_path [file join $output FptPaperStreamingKernel.xo] \
  -kernel_name FptPaperStreamingKernel -ip_directory $ip_repo \
  -kernel_xml [file join $backend_dir xml FptPaperStreamingKernel.xml]
puts "PAPER_XO_COMPLETE: hardware qualification remains false"

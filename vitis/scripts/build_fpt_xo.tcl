#!/usr/bin/env vivado -mode batch -source

set script_dir [file dirname [file normalize [info script]]]
set vitis_dir [file dirname $script_dir]
set repo_dir [file dirname $vitis_dir]
set build_dir [file join $vitis_dir build]
if {[info exists ::env(FPT_VITIS_BUILD_DIR)]} {
  set build_dir [file normalize $::env(FPT_VITIS_BUILD_DIR)]
}
set generated_dir [file join $build_dir generated]
set project_dir [file join $build_dir FptBlindRotateKernel_project]
set ip_repo_dir [file join $project_dir ip_repo]
set xo_dir [file join $build_dir xo]
set output_lanes 1
if {[info exists ::env(FPT_U280_OUTPUT_LANES)]} {
  set output_lanes $::env(FPT_U280_OUTPUT_LANES)
}
if {$output_lanes ni {1 4}} { error "FPT_U280_OUTPUT_LANES must be 1 or 4" }
set output_bits [expr {32 * $output_lanes}]
set chained 0
if {[info exists ::env(FPT_U280_CHAINED_INTERFACE)]} {set chained $::env(FPT_U280_CHAINED_INTERFACE)}
if {$chained ni {0 1}} {error "FPT_U280_CHAINED_INTERFACE must be 0 or 1"}
set status_fifo [expr {$chained ? "true" : "false"}]
set floorplan A
if {[info exists ::env(FPT_FLOORPLAN)]} {
  set floorplan [string toupper $::env(FPT_FLOORPLAN)]
}
if {$floorplan ni {A B}} {
  error "FPT_FLOORPLAN must be A or B"
}
set floorplan_xdc [file join $vitis_dir xdc floorplan_[string tolower $floorplan].xdc]

file delete -force $project_dir
file mkdir $xo_dir
create_project FptBlindRotateKernel_project $project_dir \
  -part xcu280-fsvh2892-2L-e -force
set_property target_language Verilog [current_project]
set output_config [file join $generated_dir FptOutputConfig.vh]
set config_file [open $output_config w]
puts $config_file "`define FPT_OUTPUT_LANES $output_lanes"
if {$chained} {puts $config_file "`define FPT_CHAIN_INTERFACE"}
close $config_file
set controller_file [open [file join $generated_dir FptBlindRotateKernelController.sv]]
set controller_rtl [read $controller_file]; close $controller_file
if {[regexp {\mio_continue\M} $controller_rtl] != $chained} {
  error "controller RTL and FPT_U280_CHAINED_INTERFACE disagree"
}

import_files -norecurse [list \
  [file join $vitis_dir rtl FptBlindRotateKernel.sv] \
  [file join $vitis_dir rtl FptBlindRotateKernel_control_s_axi.sv] \
  [file join $generated_dir FptBlindRotateKernelController.sv] \
  $output_config \
  [file join $generated_dir forward.v] \
  [file join $generated_dir inverse.v] \
  $floorplan_xdc]
set imported_xdc [get_files [file tail $floorplan_xdc]]
set_property USED_IN_SYNTHESIS false $imported_xdc
set_property USED_IN_IMPLEMENTATION true $imported_xdc
set_property SCOPED_TO_REF FptBlindRotateKernel $imported_xdc
set_property PROCESSING_ORDER LATE $imported_xdc
set_property top FptBlindRotateKernel [current_fileset]

create_ip -name axi_datamover -vendor xilinx.com -library ip -version 5.1 \
  -module_name axi_datamover_mm2s
set_property -dict [list \
  CONFIG.c_enable_s2mm {0} \
  CONFIG.c_addr_width {64} \
  CONFIG.c_m_axi_mm2s_addr_width {64} \
  CONFIG.c_m_axi_mm2s_data_width {512} \
  CONFIG.c_m_axis_mm2s_tdata_width {512} \
  CONFIG.c_mm2s_btt_used {23} \
  CONFIG.c_include_mm2s_stsfifo $status_fifo \
  CONFIG.c_mm2s_stscmd_is_async {false} \
  CONFIG.c_mm2s_burst_size {64} \
  CONFIG.c_m_axi_mm2s_id_width {1} \
  CONFIG.c_include_mm2s_dre {false}] [get_ips axi_datamover_mm2s]

create_ip -name axi_datamover -vendor xilinx.com -library ip -version 5.1 \
  -module_name axi_datamover_s2mm
set_property -dict [list \
  CONFIG.c_enable_mm2s {0} \
  CONFIG.c_addr_width {64} \
  CONFIG.c_m_axi_s2mm_addr_width {64} \
  CONFIG.c_m_axi_s2mm_data_width {512} \
  CONFIG.c_s_axis_s2mm_tdata_width $output_bits \
  CONFIG.c_s2mm_btt_used {23} \
  CONFIG.c_include_s2mm_stsfifo $status_fifo \
  CONFIG.c_s2mm_stscmd_is_async {false} \
  CONFIG.c_s2mm_burst_size {64} \
  CONFIG.c_s2mm_support_indet_btt {false} \
  CONFIG.c_m_axi_s2mm_id_width {4} \
  CONFIG.c_include_s2mm_dre {true}] [get_ips axi_datamover_s2mm]
generate_target all [get_ips axi_datamover_mm2s]
generate_target all [get_ips axi_datamover_s2mm]
if {[get_property CONFIG.c_s_axis_s2mm_tdata_width [get_ips axi_datamover_s2mm]] != $output_bits} {
  error "output DataMover width does not match FPT_U280_OUTPUT_LANES"
}
if {[get_property CONFIG.c_s2mm_btt_used [get_ips axi_datamover_s2mm]] != 23} {
  error "output DataMover BTT width changed"
}
puts "FPT_OUTPUT_STREAM_WIDTH_PASS lanes=$output_lanes bits=$output_bits"
foreach {ip property} {axi_datamover_mm2s CONFIG.c_include_mm2s_stsfifo axi_datamover_s2mm CONFIG.c_include_s2mm_stsfifo} {
  if {[expr {!![get_property $property [get_ips $ip]]}] != $chained} {
    error "DataMover status FIFO disagrees with chained protocol: $ip"
  }
}
set xml_file [open [file join $vitis_dir xml FptBlindRotateKernel.xml]]
set kernel_xml [read $xml_file]; close $xml_file
if {$chained} {set kernel_xml [string map {ap_ctrl_hs ap_ctrl_chain} $kernel_xml]}
set packaged_xml [file join $generated_dir FptBlindRotateKernel.xml]
set xml_file [open $packaged_xml w]; puts $xml_file $kernel_xml; close $xml_file
puts "FPT_KERNEL_PROTOCOL_PASS chained=$chained status_fifo=$status_fifo"
update_compile_order -fileset sources_1

ipx::package_project -root_dir $ip_repo_dir \
  -vendor fpt.example -library kernel -taxonomy /KernelIP \
  -import_files -set_current true
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

set xo_file [file join $xo_dir FptBlindRotateKernel.xo]
file delete -force $xo_file
package_xo -xo_path $xo_file \
  -kernel_name FptBlindRotateKernel \
  -ip_directory $ip_repo_dir \
  -kernel_xml $packaged_xml
puts "FPT_XO floorplan=$floorplan path=$xo_file"

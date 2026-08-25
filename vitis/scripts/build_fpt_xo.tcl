#!/usr/bin/env vivado -mode batch -source

set script_dir [file dirname [file normalize [info script]]]
set vitis_dir [file dirname $script_dir]
set repo_dir [file dirname $vitis_dir]
set build_dir [file join $vitis_dir build]
set generated_dir [file join $build_dir generated]
set project_dir [file join $build_dir FptBlindRotateKernel_project]
set ip_repo_dir [file join $project_dir ip_repo]
set xo_dir [file join $build_dir xo]
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

import_files -norecurse [list \
  [file join $vitis_dir rtl FptBlindRotateKernel.sv] \
  [file join $vitis_dir rtl FptBlindRotateKernel_control_s_axi.sv] \
  [file join $generated_dir FptBlindRotateKernelController.sv] \
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
  CONFIG.c_include_mm2s_stsfifo {false} \
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
  CONFIG.c_s_axis_s2mm_tdata_width {32} \
  CONFIG.c_s2mm_btt_used {23} \
  CONFIG.c_include_s2mm_stsfifo {false} \
  CONFIG.c_s2mm_stscmd_is_async {false} \
  CONFIG.c_s2mm_burst_size {64} \
  CONFIG.c_s2mm_support_indet_btt {false} \
  CONFIG.c_m_axi_s2mm_id_width {4} \
  CONFIG.c_include_s2mm_dre {true}] [get_ips axi_datamover_s2mm]
generate_target all [get_ips axi_datamover_mm2s]
generate_target all [get_ips axi_datamover_s2mm]
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
  -kernel_xml [file join $vitis_dir xml FptBlindRotateKernel.xml]
puts "FPT_XO floorplan=$floorplan path=$xo_file"

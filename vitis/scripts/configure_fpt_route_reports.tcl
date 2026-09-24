# Avoid Vivado 2023.2's binary timing-table writer before the checkpoint hook.
# Timing acceptance still comes from get_timing_paths and text-only reports.
proc fpt_disable_binary_route_report {} {
  set name impl_report_timing_summary_route_design_summary
  set config [get_report_configs -quiet $name]
  if {[llength $config] != 1} {error "missing or ambiguous Vitis route timing report: $name"}
  if {[get_property RUN_STEP $config] ne "route_design" ||
      ![string match {report_timing_summary:*} [get_property REPORT_TYPE $config]]} {
    error "unexpected Vitis route timing report configuration: $name"
  }
  set_property IS_ENABLED false $config
  if {[get_property IS_ENABLED $config]} {error "could not disable binary route report"}
  puts "FPT_ROUTE_REPORT_CONFIG_PASS binary_route_summary=disabled strict_timing=unchanged"
}

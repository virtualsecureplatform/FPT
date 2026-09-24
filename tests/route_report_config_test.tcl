source [file join [file dirname [info script]] .. vitis scripts configure_fpt_route_reports.tcl]
set config impl_report_timing_summary_route_design_summary
set properties [dict create RUN_STEP route_design REPORT_TYPE report_timing_summary:1.0 IS_ENABLED true]
proc get_report_configs {args} {return $::config}
proc get_property {name object} {return [dict get $::properties $name]}
proc set_property {name value object} {
  if {$name ne "IS_ENABLED"} {error "unexpected report modification $name"}
  dict set ::properties $name $value
}
fpt_disable_binary_route_report
if {[dict get $properties IS_ENABLED]} {error "binary report remains enabled"}
set config ""
if {![catch {fpt_disable_binary_route_report}]} {error "missing report was accepted"}
set config impl_report_timing_summary_route_design_summary
dict set properties RUN_STEP place_design
if {![catch {fpt_disable_binary_route_report}]} {error "wrong report step was accepted"}
dict set properties RUN_STEP route_design
dict set properties REPORT_TYPE report_utilization:1.0
if {![catch {fpt_disable_binary_route_report}]} {error "wrong report type was accepted"}
puts "FPT_ROUTE_REPORT_CONFIG_TEST_PASS"

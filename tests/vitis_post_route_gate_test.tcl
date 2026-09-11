set ::fpt_post_route_library_only 1
set test_dir [file dirname [file normalize [info script]]]
source [file join $test_dir .. vitis scripts check_fpt_post_route.tcl]

set mock_route_fully_routed 1
set mock_route_errors 0
array set mock_route_counts {
  UNROUTED 0 PARTIAL 0 UNPLACED 0 GAPS 0 CONFLICTS 0 ANTENNAS 0
}
array set mock_drc_counts {
  Fatal 0 Error 0 {Critical Warning} 0 Warning 2 Advisory 1
}
set mock_wns 0.024
set mock_whs 0.005
set ::env(FPT_POST_ROUTE_DIAGNOSTICS) 0
set mock_diagnostics {}

proc get_clocks {args} { return {clk_kernel_00_unbuffered_net} }
proc get_property {property object} {
  global mock_wns mock_whs
  if {$property eq "PERIOD"} { return 5.0 }
  if {$property eq "SLACK" && $object eq "setup_path"} { return $mock_wns }
  if {$property eq "SLACK" && $object eq "hold_path"} { return $mock_whs }
  error "unexpected property request: $property $object"
}
proc report_route_status {args} {
  global mock_route_fully_routed mock_route_errors mock_route_counts
  if {[lsearch -exact $args -file] >= 0} {
    lappend ::mock_diagnostics route_status
    return
  }
  set boolean_index [lsearch -exact $args -boolean_check]
  if {$boolean_index >= 0} {
    set check [lindex $args [expr {$boolean_index + 1}]]
    if {$check eq "ROUTED_FULLY"} { return $mock_route_fully_routed }
    if {$check eq "ERRORS_IN_ROUTES"} { return $mock_route_errors }
    error "unexpected route boolean: $check"
  }
  set type_index [lsearch -exact $args -route_type]
  set route_type [lindex $args [expr {$type_index + 1}]]
  return [lrepeat $mock_route_counts($route_type) mock_net]
}
proc report_drc {args} { return }
proc report_timing_summary {args} { lappend ::mock_diagnostics timing_summary }
proc report_timing {args} { lappend ::mock_diagnostics kernel_paths }
proc report_utilization {args} { lappend ::mock_diagnostics utilization }
proc get_drc_violations {args} {
  global mock_drc_counts
  set filter_index [lsearch -exact $args -filter]
  if {$filter_index >= 0} {
    set filter [lindex $args [expr {$filter_index + 1}]]
    regexp {^SEVERITY == \{(.+)\}$} $filter unused severity
    return [lrepeat $mock_drc_counts($severity) mock_violation]
  }
  set total 0
  foreach severity [array names mock_drc_counts] {
    incr total $mock_drc_counts($severity)
  }
  return [lrepeat $total mock_violation]
}
proc get_timing_paths {args} {
  if {[lsearch -exact $args min] >= 0} { return {hold_path} }
  return {setup_path}
}

proc assert_rejected {script label} {
  if {![catch {uplevel 1 $script} message]} {
    error "$label was accepted"
  }
}

set metrics_path [lindex $argv 0]
fpt_check_vitis_post_route $metrics_path

set mock_route_fully_routed 0
assert_rejected {fpt_check_vitis_post_route $metrics_path} "partial route"
set mock_route_fully_routed 1
set mock_drc_counts(Critical\ Warning) 1
assert_rejected {fpt_check_vitis_post_route $metrics_path} "critical DRC"
set mock_drc_counts(Critical\ Warning) 0
set mock_wns -0.001
assert_rejected {fpt_check_vitis_post_route $metrics_path} "negative setup slack"
set mock_wns 0.024
set mock_whs -0.001
assert_rejected {fpt_check_vitis_post_route $metrics_path} "negative hold slack"
set mock_whs 0.005
fpt_check_vitis_post_route $metrics_path
puts "Vitis post-route gate Tcl tests passed"

set ::env(FPT_POST_ROUTE_DIAGNOSTICS) 1
set mock_wns -0.001
if {![catch {fpt_check_vitis_post_route $metrics_path} message] ||
    [string first "WNS=-0.001" $message] < 0} {
  error "diagnostic mode did not preserve strict negative-slack rejection: $message"
}
if {$mock_diagnostics ne {timing_summary kernel_paths route_status utilization utilization}} {
  error "diagnostics were not captured before rejection: $mock_diagnostics"
}
puts "Vitis post-route diagnostics-before-failure test passed"
set ::env(FPT_POST_ROUTE_MAX_PATHS) 200
set mock_wns 0.024
fpt_check_vitis_post_route $metrics_path
set ::env(FPT_POST_ROUTE_MAX_PATHS) 0
assert_rejected {fpt_check_vitis_post_route $metrics_path} "zero diagnostic path count"
unset ::env(FPT_POST_ROUTE_MAX_PATHS)

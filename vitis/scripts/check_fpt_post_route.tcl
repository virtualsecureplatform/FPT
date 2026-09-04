# Strict acceptance gate for the complete Vitis-linked U280 design.  This is
# installed as the route_design post-hook, where the platform clock network is
# present and every routed net can be checked without the top-level-clock
# artifact of an out-of-context checkpoint.

proc fpt_vitis_route_count {route_type} {
  return [llength [report_route_status -return_nets -route_type $route_type]]
}

proc fpt_vitis_drc_count {report_name severity} {
  return [llength [get_drc_violations -name $report_name -quiet \
    -filter "SEVERITY == {$severity}"]]
}

proc fpt_vitis_write_metrics {path metrics} {
  set output [open $path w]
  puts $output "metric\tvalue"
  dict for {name value} $metrics {
    puts $output "$name\t$value"
  }
  close $output
}

proc fpt_check_vitis_post_route {metrics_path} {
  set kernel_clocks [get_clocks -quiet -filter {NAME =~ *clk_kernel_00*}]
  if {[llength $kernel_clocks] < 1} {
    error "FPT post-route gate could not find clk_kernel_00"
  }
  set kernel_periods {}
  foreach kernel_clock $kernel_clocks {
    lappend kernel_periods [get_property PERIOD $kernel_clock]
  }
  set has_200mhz_clock 0
  foreach period $kernel_periods {
    if {[string is double -strict $period] && abs($period - 5.0) <= 0.0005} {
      set has_200mhz_clock 1
    }
  }
  if {!$has_200mhz_clock} {
    error "FPT post-route gate expected a 5.000 ns kernel clock, found $kernel_periods"
  }

  set metrics [dict create kernel_clock_period_ns 5.000]
  dict set metrics route_fully_routed \
    [report_route_status -boolean_check ROUTED_FULLY]
  dict set metrics route_errors \
    [report_route_status -boolean_check ERRORS_IN_ROUTES]
  foreach {name route_type} {
    route_unrouted_nets UNROUTED
    route_partial_nets PARTIAL
    route_unplaced_nets UNPLACED
    route_gap_nets GAPS
    route_conflict_nets CONFLICTS
    route_antenna_nets ANTENNAS
  } {
    dict set metrics $name [fpt_vitis_route_count $route_type]
  }

  set drc_name fpt_vitis_post_route_drc
  report_drc -name $drc_name
  set drc_total [llength [get_drc_violations -name $drc_name -quiet]]
  dict set metrics drc_violations $drc_total
  set drc_classified 0
  foreach {name severity} {
    drc_fatal Fatal
    drc_error Error
    drc_critical_warning {Critical Warning}
    drc_warning Warning
    drc_advisory Advisory
  } {
    set count [fpt_vitis_drc_count $drc_name $severity]
    dict set metrics $name $count
    incr drc_classified $count
  }
  dict set metrics drc_unclassified [expr {$drc_total - $drc_classified}]

  set setup_paths [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
  set hold_paths [get_timing_paths -delay_type min -max_paths 1 -nworst 1]
  if {[llength $setup_paths] < 1 || [llength $hold_paths] < 1} {
    error "FPT post-route gate could not obtain setup and hold timing paths"
  }
  set wns [get_property SLACK [lindex $setup_paths 0]]
  set whs [get_property SLACK [lindex $hold_paths 0]]
  dict set metrics wns_ns [format %.3f $wns]
  dict set metrics whs_ns [format %.3f $whs]

  fpt_vitis_write_metrics $metrics_path $metrics

  set failures {}
  if {[dict get $metrics route_fully_routed] != 1} {
    lappend failures "design is not fully routed"
  }
  if {[dict get $metrics route_errors] != 0} {
    lappend failures "route status reports errors"
  }
  foreach name {
    route_unrouted_nets route_partial_nets route_unplaced_nets
    route_gap_nets route_conflict_nets route_antenna_nets
    drc_fatal drc_error drc_critical_warning drc_unclassified
  } {
    if {[dict get $metrics $name] != 0} {
      lappend failures "$name=[dict get $metrics $name]"
    }
  }
  if {$wns < 0.0} {
    lappend failures "WNS=[format %.3f $wns] ns"
  }
  if {$whs < 0.0} {
    lappend failures "WHS=[format %.3f $whs] ns"
  }
  if {[llength $failures] > 0} {
    error "FPT full U280 implementation is not acceptable: [join $failures {, }]"
  }
  puts "FPT_POST_ROUTE_PASS metrics=$metrics_path WNS=[format %.3f $wns]ns WHS=[format %.3f $whs]ns"
}

if {![info exists ::fpt_post_route_library_only]} {
  if {![info exists ::env(FPT_POST_ROUTE_METRICS)] || \
      $::env(FPT_POST_ROUTE_METRICS) eq ""} {
    error "FPT_POST_ROUTE_METRICS is not set"
  }
  fpt_check_vitis_post_route $::env(FPT_POST_ROUTE_METRICS)
}

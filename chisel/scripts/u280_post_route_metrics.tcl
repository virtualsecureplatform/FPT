# Shared post-route acceptance and metric collection for the U280 flows.

proc fpt_count_refs {pattern} {
    return [llength [get_cells -hierarchical -quiet \
        -filter "REF_NAME =~ $pattern"]]
}

proc fpt_route_net_count {route_type} {
    return [llength [report_route_status -return_nets \
        -route_type $route_type]]
}

proc fpt_drc_violation_count {report_name severity} {
    return [llength [get_drc_violations -name $report_name -quiet \
        -filter "SEVERITY == {$severity}"]]
}

proc fpt_require_u280_clock {clock_period clock_port} {
    set clock_ports [get_ports -quiet $clock_port]
    if {[llength $clock_ports] != 1} {
        error "Expected one $clock_port port after synthesis, found [llength $clock_ports]"
    }
    set clocks [get_clocks -quiet ap_clk]
    if {[llength $clocks] != 1} {
        error "Expected one ap_clk timing clock, found [llength $clocks]"
    }
    set implemented_period [get_property PERIOD [lindex $clocks 0]]
    if {![string is double -strict $implemented_period] || \
        abs($implemented_period - $clock_period) > 0.0005} {
        error "Clock constraint mismatch: requested $clock_period ns, implemented $implemented_period ns"
    }
    return $implemented_period
}

proc fpt_collect_u280_metrics {clock_period clock_port drc_name} {
    set implemented_period \
        [fpt_require_u280_clock $clock_period $clock_port]

    set metrics [dict create]
    dict set metrics implemented_clock_period_ns \
        [format %.3f $implemented_period]
    dict set metrics logic_luts [fpt_count_refs LUT*]
    dict set metrics flip_flops [fpt_count_refs FD*]
    dict set metrics dsp48e2 [fpt_count_refs DSP48E2]
    dict set metrics ramb18e2 [fpt_count_refs RAMB18E2]
    dict set metrics ramb36e2 [fpt_count_refs RAMB36E2]
    dict set metrics uram288 [fpt_count_refs URAM288]
    set all_ram [fpt_count_refs RAM*]
    dict set metrics distributed_ram [expr {
        $all_ram - [dict get $metrics ramb18e2] - \
        [dict get $metrics ramb36e2]
    }]
    dict set metrics srl [fpt_count_refs SRL*]
    dict set metrics carry8 [fpt_count_refs CARRY8]

    dict set metrics route_fully_routed \
        [report_route_status -boolean_check ROUTED_FULLY]
    dict set metrics route_errors \
        [report_route_status -boolean_check ERRORS_IN_ROUTES]
    foreach {metric route_type} {
        route_unrouted_nets UNROUTED
        route_partial_nets PARTIAL
        route_unplaced_nets UNPLACED
        route_gap_nets GAPS
        route_conflict_nets CONFLICTS
        route_antenna_nets ANTENNAS
        route_nodriver_nets NODRIVER
    } {
        dict set metrics $metric [fpt_route_net_count $route_type]
    }

    set drc_total [llength [get_drc_violations -name $drc_name -quiet]]
    dict set metrics drc_violations $drc_total
    set drc_classified 0
    foreach {metric severity} {
        drc_fatal Fatal
        drc_error Error
        drc_critical_warning {Critical Warning}
        drc_warning Warning
        drc_advisory Advisory
    } {
        set count [fpt_drc_violation_count $drc_name $severity]
        dict set metrics $metric $count
        incr drc_classified $count
    }
    dict set metrics drc_unclassified \
        [expr {$drc_total - $drc_classified}]

    set timing_paths [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
    dict set metrics wns_ns NA
    dict set metrics achieved_mhz NA
    if {[llength $timing_paths] > 0} {
        set wns [format %.3f \
            [get_property SLACK [lindex $timing_paths 0]]]
        dict set metrics wns_ns $wns
        set critical_delay [expr {$clock_period - $wns}]
        if {$critical_delay > 0} {
            dict set metrics achieved_mhz \
                [format %.3f [expr {1000.0 / $critical_delay}]]
        }
    }
    return $metrics
}

proc fpt_write_u280_metrics {path fixed_metrics metrics} {
    set metrics_file [open $path w]
    puts $metrics_file "metric\tvalue"
    foreach {metric value} $fixed_metrics {
        puts $metrics_file "$metric\t$value"
    }
    dict for {metric value} $metrics {
        puts $metrics_file "$metric\t$value"
    }
    close $metrics_file
}

proc fpt_require_clean_u280_route {metrics} {
    set failures {}
    if {[dict get $metrics route_fully_routed] != 1} {
        lappend failures "design is not fully routed"
    }
    if {[dict get $metrics route_errors] != 0} {
        lappend failures "route status reports errors"
    }
    foreach metric {
        route_unrouted_nets
        route_partial_nets
        route_unplaced_nets
        route_gap_nets
        route_conflict_nets
        route_antenna_nets
        route_nodriver_nets
        drc_fatal
        drc_error
        drc_critical_warning
        drc_unclassified
    } {
        set value [dict get $metrics $metric]
        if {$value != 0} {
            lappend failures "$metric=$value"
        }
    }
    if {[llength $failures] > 0} {
        error "U280 implementation is not acceptable: [join $failures {, }]"
    }
}

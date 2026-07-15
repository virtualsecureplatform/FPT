if {$argc != 1} {
    error "usage: tclsh u280_post_route_metrics_test.tcl OUTPUT_METRICS"
}

set test_dir [file dirname [file normalize [info script]]]
source [file join $test_dir .. chisel scripts u280_post_route_metrics.tcl]

set mock_route_fully_routed 1
set mock_route_errors 0
array set mock_route_net_counts {
    UNROUTED 0
    PARTIAL 0
    UNPLACED 0
    GAPS 0
    CONFLICTS 0
    ANTENNAS 0
    NODRIVER 0
}
array set mock_drc_counts {
    Fatal 0
    Error 0
    {Critical Warning} 0
    Warning 2
    Advisory 1
}

proc get_cells {args} {
    return {}
}

proc get_ports {args} {
    if {[lindex $args end] eq "clock"} {
        return {clock}
    }
    return {}
}

proc get_clocks {args} {
    if {[lindex $args end] eq "ap_clk"} {
        return {ap_clk}
    }
    return {}
}

proc get_property {property object} {
    switch -- $property {
        PERIOD { return 5.0 }
        SLACK { return 0.2 }
        default { error "Unexpected property $property" }
    }
}

proc report_route_status {args} {
    global mock_route_errors mock_route_fully_routed mock_route_net_counts
    set boolean_index [lsearch -exact $args -boolean_check]
    if {$boolean_index >= 0} {
        set check [lindex $args [expr {$boolean_index + 1}]]
        switch -- $check {
            ROUTED_FULLY { return $mock_route_fully_routed }
            ERRORS_IN_ROUTES { return $mock_route_errors }
            default { error "Unexpected route Boolean check $check" }
        }
    }
    set type_index [lsearch -exact $args -route_type]
    if {$type_index < 0} {
        error "Mock expected -route_type: $args"
    }
    set route_type [lindex $args [expr {$type_index + 1}]]
    return [lrepeat $mock_route_net_counts($route_type) mock_net]
}

proc get_drc_violations {args} {
    global mock_drc_counts
    set filter_index [lsearch -exact $args -filter]
    if {$filter_index >= 0} {
        set filter [lindex $args [expr {$filter_index + 1}]]
        if {![regexp {^SEVERITY == \{(.+)\}$} $filter unused severity]} {
            error "Unexpected DRC filter: $filter"
        }
        return [lrepeat $mock_drc_counts($severity) mock_violation]
    }
    set total 0
    foreach severity [array names mock_drc_counts] {
        incr total $mock_drc_counts($severity)
    }
    return [lrepeat $total mock_violation]
}

proc get_timing_paths {args} {
    return {critical_path}
}

proc assert_equal {actual expected label} {
    if {$actual ne $expected} {
        error "$label: expected $expected, got $actual"
    }
}

set metrics [fpt_collect_u280_metrics 5.0 clock mock_post_route_drc]
assert_equal [dict get $metrics implemented_clock_period_ns] 5.000 \
    "implemented clock"
assert_equal [dict get $metrics route_fully_routed] 1 "fully routed"
assert_equal [dict get $metrics drc_violations] 3 "DRC total"
assert_equal [dict get $metrics drc_warning] 2 "DRC warnings"
assert_equal [dict get $metrics drc_advisory] 1 "DRC advisories"
assert_equal [dict get $metrics drc_unclassified] 0 "classified DRCs"
assert_equal [dict get $metrics achieved_mhz] 208.333 "achieved frequency"
fpt_require_clean_u280_route $metrics

set incomplete [dict replace $metrics route_fully_routed 0]
if {![catch {fpt_require_clean_u280_route $incomplete}]} {
    error "Incomplete route was accepted"
}
set critical_drc [dict replace $metrics drc_critical_warning 1]
if {![catch {fpt_require_clean_u280_route $critical_drc}]} {
    error "Critical-warning DRC was accepted"
}
if {![catch {fpt_require_u280_clock 4.0 clock}]} {
    error "Mismatched clock period was accepted"
}

fpt_write_u280_metrics [lindex $argv 0] [list \
    top MockTop \
    clock_port clock \
    part xcu280-fsvh2892-2L-e \
    clock_period_ns 5.0] $metrics
puts "U280 post-route metric Tcl tests passed"

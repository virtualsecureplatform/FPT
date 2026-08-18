# Retarget and route an already placed single-clock U280 checkpoint.  This is
# intended for controlled historical comparisons where the placed checkpoint
# carries a different ap_clk period from the requested implementation.
#
# vivado -mode batch \
#   -source chisel/scripts/retarget_route_u280_checkpoint.tcl \
#   -tclargs RAW_PLACE_DCP OUTPUT_DIR 5.000 8

set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir u280_post_route_metrics.tcl]

set input_checkpoint [lindex $argv 0]
set output_dir [lindex $argv 1]
set clock_period [lindex $argv 2]
set jobs [lindex $argv 3]
if {$input_checkpoint eq "" || $output_dir eq ""} {
    error "usage: RAW_PLACE_DCP OUTPUT_DIR ?CLOCK_PERIOD_NS? ?JOBS?"
}
if {$clock_period eq ""} { set clock_period 5.000 }
if {$jobs eq ""} { set jobs 8 }
if {![string is double -strict $clock_period] || $clock_period <= 0} {
    error "CLOCK_PERIOD_NS must be positive: $clock_period"
}
if {![string is integer -strict $jobs] || $jobs < 1} {
    error "JOBS must be a positive integer: $jobs"
}

set top BufferedBlindRotateAccelerator
set clock_port clock
set input_checkpoint [file normalize $input_checkpoint]
set output_dir [file normalize $output_dir]
if {![file isfile $input_checkpoint]} {
    error "Placed checkpoint not found: $input_checkpoint"
}
file mkdir $output_dir
set_param general.maxThreads $jobs

open_checkpoint $input_checkpoint
set clock_ports [get_ports -quiet $clock_port]
if {[llength $clock_ports] != 1} {
    error "Expected one $clock_port port, found [llength $clock_ports]"
}
set old_clocks [get_clocks -quiet ap_clk]
if {[llength $old_clocks] != 1} {
    error "Expected one existing ap_clk, found [llength $old_clocks]"
}
set old_period [get_property PERIOD [lindex $old_clocks 0]]
create_clock -name ap_clk -period $clock_period $clock_ports
fpt_require_u280_clock $clock_period $clock_port
puts "FPT_RETARGET_CLOCK old_period_ns=$old_period new_period_ns=$clock_period"

route_design -directive Explore
write_checkpoint -force [file join $output_dir initial_route.dcp]
report_route_status -file [file join $output_dir initial_route_status.rpt]
report_timing_summary \
    -file [file join $output_dir initial_route_timing_summary.rpt]

set initially_routed [report_route_status -boolean_check ROUTED_FULLY]
set initial_paths [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
set initial_wns NA
if {[llength $initial_paths] > 0} {
    set initial_wns [format %.3f \
        [get_property SLACK [lindex $initial_paths 0]]]
}
puts "FPT_RETARGET_INITIAL_ROUTE routed=$initially_routed wns_ns=$initial_wns"

if {$initially_routed && $initial_wns ne "NA" && $initial_wns < 0} {
    phys_opt_design -directive Explore
    route_design -directive Explore
} elseif {!$initially_routed} {
    route_design -directive AggressiveExplore
}

report_utilization -hierarchical \
    -file [file join $output_dir utilization.rpt]
report_timing_summary -file [file join $output_dir timing_summary.rpt]
report_timing -delay_type max -max_paths 200 -nworst 1 \
    -file [file join $output_dir timing_paths.rpt]
report_route_status -file [file join $output_dir route_status.rpt]
set drc_name fpt_retarget_post_route_drc
report_drc -name $drc_name -file [file join $output_dir drc.rpt]
write_checkpoint -force [file join $output_dir $top.dcp]

set metrics [fpt_collect_u280_metrics \
    $clock_period $clock_port $drc_name]

# An out-of-context checkpoint has no package-pin route feeding its top-level
# clock port.  Older placed checkpoints therefore retain one explicit gap on
# the clock input even though the complete BUFG-to-leaf clock tree is routed,
# timed, and verified.  Accept only that exact boundary artifact; preserve the
# raw route status in metrics and continue to reject every internal gap.
set ooc_clock_gap_accepted 0
if {[dict get $metrics route_fully_routed] == 0 && \
    [dict get $metrics route_errors] == 0 && \
    [dict get $metrics route_unrouted_nets] == 0 && \
    [dict get $metrics route_partial_nets] == 0 && \
    [dict get $metrics route_unplaced_nets] == 0 && \
    [dict get $metrics route_gap_nets] == 1 && \
    [dict get $metrics route_conflict_nets] == 0 && \
    [dict get $metrics route_antenna_nets] == 0} {
    set gap_nets [report_route_status -return_nets -route_type GAPS]
    set clock_net [get_nets -quiet -of_objects [get_ports $clock_port]]
    if {[llength $gap_nets] == 1 && [llength $clock_net] == 1 && \
        [get_property NAME [lindex $gap_nets 0]] eq \
            [get_property NAME [lindex $clock_net 0]] && \
        [get_property ROUTE_STATUS [lindex $gap_nets 0]] eq "PARTIAL" && \
        [get_property DIRECTION [get_ports $clock_port]] eq "IN"} {
        set ooc_clock_gap_accepted 1
    }
}
dict set metrics ooc_clock_gap_accepted $ooc_clock_gap_accepted

fpt_write_u280_metrics [file join $output_dir metrics.tsv] [list \
    top $top \
    source_checkpoint $input_checkpoint \
    original_clock_period_ns $old_period \
    clock_period_ns $clock_period \
    route_directive Explore/AggressiveExplore \
    post_route_phys_opt_directive Explore] $metrics

set wns [dict get $metrics wns_ns]
puts "FPT_RETARGET_METRICS period_ns=$clock_period wns_ns=$wns achieved_mhz=[dict get $metrics achieved_mhz] routed=[dict get $metrics route_fully_routed] drc_errors=[dict get $metrics drc_error]"
set acceptance_metrics $metrics
if {$ooc_clock_gap_accepted} {
    puts "FPT_RETARGET_OOC_CLOCK_GAP_ACCEPTED net=$clock_port"
    dict set acceptance_metrics route_fully_routed 1
    dict set acceptance_metrics route_gap_nets 0
}
fpt_require_clean_u280_route $acceptance_metrics
if {$wns eq "NA"} {
    error "No setup timing path was available for retargeted acceptance"
}
if {$wns < 0} {
    error "Routed design misses $clock_period ns by [expr {-$wns}] ns"
}

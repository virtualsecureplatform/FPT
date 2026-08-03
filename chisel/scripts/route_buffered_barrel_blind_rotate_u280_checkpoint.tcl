# Route an already placed U280 Buffered Blind Rotate checkpoint.  This is a
# lightweight fallback to the full synthesis flow: it preserves the placed
# design, routes it immediately, and only runs post-route physical
# optimization when the first route still misses timing.
#
# vivado -mode batch \
#   -source chisel/scripts/route_buffered_barrel_blind_rotate_u280_checkpoint.tcl \
#   -tclargs RAW_PLACE_DCP OUTPUT_DIR 3.333 8

set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir u280_post_route_metrics.tcl]

set input_checkpoint [lindex $argv 0]
set output_dir [lindex $argv 1]
set clock_period [lindex $argv 2]
set jobs [lindex $argv 3]
if {$input_checkpoint eq "" || $output_dir eq ""} {
    error "usage: RAW_PLACE_DCP OUTPUT_DIR ?CLOCK_PERIOD_NS? ?JOBS?"
}
if {$clock_period eq ""} { set clock_period 3.333 }
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
fpt_require_u280_clock $clock_period $clock_port

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
puts "FPT_FALLBACK_INITIAL_ROUTE routed=$initially_routed wns_ns=$initial_wns"

if {$initially_routed && $initial_wns ne "NA" && $initial_wns < 0} {
    phys_opt_design -directive Explore
    route_design -directive Explore
} elseif {!$initially_routed} {
    route_design -directive AggressiveExplore
}

report_utilization -hierarchical \
    -file [file join $output_dir utilization.rpt]
report_timing_summary -file [file join $output_dir timing_summary.rpt]
report_timing -delay_type max -max_paths 20 -nworst 1 \
    -file [file join $output_dir timing_paths.rpt]
report_route_status -file [file join $output_dir route_status.rpt]
set drc_name fpt_fallback_post_route_drc
report_drc -name $drc_name -file [file join $output_dir drc.rpt]
write_checkpoint -force [file join $output_dir $top.dcp]

set metrics [fpt_collect_u280_metrics \
    $clock_period $clock_port $drc_name]
fpt_write_u280_metrics [file join $output_dir metrics.tsv] [list \
    top $top \
    source_checkpoint $input_checkpoint \
    clock_period_ns $clock_period \
    route_directive Explore/AggressiveExplore \
    post_route_phys_opt_directive Explore] $metrics

set wns [dict get $metrics wns_ns]
puts "FPT_FALLBACK_300MHZ_METRICS period_ns=$clock_period wns_ns=$wns achieved_mhz=[dict get $metrics achieved_mhz] routed=[dict get $metrics route_fully_routed] drc_errors=[dict get $metrics drc_error]"
fpt_require_clean_u280_route $metrics
if {$wns eq "NA"} {
    error "No setup timing path was available for 300 MHz acceptance"
}
if {$wns < 0} {
    error "Routed design misses $clock_period ns by [expr {-$wns}] ns"
}

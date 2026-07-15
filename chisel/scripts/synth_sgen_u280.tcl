# Post-route a generated SGen transform out of context on the U280 part.
#
# vivado -mode batch -source chisel/scripts/synth_sgen_u280.tcl \
#   -tclargs build/sgen-fpt/forward.v FptSGenForward \
#            build/vivado-sgen-forward 3.425 \
#            xcu280-fsvh2892-2L-e 8 clk

set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir u280_post_route_metrics.tcl]

set source_file [lindex $argv 0]
set top [lindex $argv 1]
set output_dir [lindex $argv 2]
set clock_period [lindex $argv 3]
set part [lindex $argv 4]
set jobs [lindex $argv 5]
set clock_port [lindex $argv 6]
if {$source_file eq "" || $top eq ""} {
    error "usage: SOURCE_V TOP ?OUTPUT_DIR? ?CLOCK_PERIOD_NS? ?PART? ?JOBS? ?CLOCK_PORT?"
}
if {$output_dir eq ""} { set output_dir build/vivado-$top }
if {$clock_period eq ""} { set clock_period 3.425 }
if {$part eq ""} { set part xcu280-fsvh2892-2L-e }
if {$jobs eq ""} { set jobs 8 }
if {$clock_port eq ""} { set clock_port clk }
if {![string is double -strict $clock_period] || $clock_period <= 0} {
    error "CLOCK_PERIOD_NS must be positive: $clock_period"
}
if {![string is integer -strict $jobs] || $jobs < 1} {
    error "JOBS must be a positive integer: $jobs"
}

set source_file [file normalize $source_file]
set output_dir [file normalize $output_dir]
if {![file isfile $source_file]} { error "RTL source not found: $source_file" }
file mkdir $output_dir
set_param general.maxThreads $jobs

set clock_xdc [file join $output_dir clock.xdc]
set clock_file [open $clock_xdc w]
puts $clock_file "create_clock -name ap_clk -period $clock_period \[get_ports $clock_port\]"
close $clock_file

read_verilog $source_file
read_xdc $clock_xdc
synth_design -top $top -part $part \
    -mode out_of_context -flatten_hierarchy rebuilt
fpt_require_u280_clock $clock_period $clock_port
report_utilization -hierarchical \
    -file [file join $output_dir post_synth_utilization.rpt]
report_timing_summary -file [file join $output_dir post_synth_timing.rpt]
write_checkpoint -force [file join $output_dir post_synth.dcp]

opt_design
place_design
phys_opt_design
route_design

report_utilization -hierarchical \
    -file [file join $output_dir utilization.rpt]
report_timing_summary -file [file join $output_dir timing_summary.rpt]
report_route_status -file [file join $output_dir route_status.rpt]
set drc_name fpt_post_route_drc
report_drc -name $drc_name -file [file join $output_dir drc.rpt]
report_power -file [file join $output_dir power.rpt]
write_checkpoint -force [file join $output_dir $top.dcp]

set metrics [fpt_collect_u280_metrics $clock_period $clock_port $drc_name]
fpt_write_u280_metrics [file join $output_dir metrics.tsv] [list \
    top $top \
    clock_port $clock_port \
    part $part \
    clock_period_ns $clock_period] $metrics

set wns [dict get $metrics wns_ns]
puts "FPT_METRICS top=$top period_ns=$clock_period wns_ns=$wns achieved_mhz=[dict get $metrics achieved_mhz] LUT=[dict get $metrics logic_luts] FF=[dict get $metrics flip_flops] DSP=[dict get $metrics dsp48e2] RAMB18=[dict get $metrics ramb18e2] RAMB36=[dict get $metrics ramb36e2] URAM=[dict get $metrics uram288] routed=[dict get $metrics route_fully_routed] drc_errors=[dict get $metrics drc_error]"
if {$wns ne "NA" && $wns < 0} {
    puts "WARNING: routed design misses the requested clock by [expr {-$wns}] ns"
}
fpt_require_clean_u280_route $metrics

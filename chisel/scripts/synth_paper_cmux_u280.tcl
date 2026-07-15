# Post-route a paper-shaped Chisel CMUX with separately generated SGen
# BlackBoxes out of context on the U280 part. The optional TOP argument also
# selects any emitted single or batched CmuxEngine variant.
#
# vivado -mode batch -source chisel/scripts/synth_paper_cmux_u280.tcl \
#   -tclargs build/chisel-paper/CmuxEngine.sv \
#            build/sgen-fpt/forward.v build/sgen-fpt/inverse.v \
#            build/vivado-paper-cmux 3.425 CmuxEngine \
#            xcu280-fsvh2892-2L-e 8

set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir u280_post_route_metrics.tcl]

set cmux_source [lindex $argv 0]
set forward_source [lindex $argv 1]
set inverse_source [lindex $argv 2]
set output_dir [lindex $argv 3]
set clock_period [lindex $argv 4]
set top [lindex $argv 5]
set part [lindex $argv 6]
set jobs [lindex $argv 7]
if {$cmux_source eq "" || $forward_source eq "" || $inverse_source eq ""} {
    error "usage: CMUX_SV FORWARD_V INVERSE_V ?OUTPUT_DIR? ?CLOCK_PERIOD_NS? ?TOP? ?PART? ?JOBS?"
}
if {$output_dir eq ""} { set output_dir build/vivado-paper-cmux }
if {$clock_period eq ""} { set clock_period 3.425 }
if {$top eq ""} { set top CmuxEngine }
if {$part eq ""} { set part xcu280-fsvh2892-2L-e }
if {$jobs eq ""} { set jobs 8 }
set clock_port clock
if {![string is double -strict $clock_period] || $clock_period <= 0} {
    error "CLOCK_PERIOD_NS must be positive: $clock_period"
}
if {![string is integer -strict $jobs] || $jobs < 1} {
    error "JOBS must be a positive integer: $jobs"
}

set cmux_source [file normalize $cmux_source]
set forward_source [file normalize $forward_source]
set inverse_source [file normalize $inverse_source]
set output_dir [file normalize $output_dir]
foreach source [list $cmux_source $forward_source $inverse_source] {
    if {![file isfile $source]} { error "RTL source not found: $source" }
}
file mkdir $output_dir
set_param general.maxThreads $jobs

# Read the clock constraint before synthesis so logic optimization and every
# implementation stage see the same target period.
set clock_xdc [file join $output_dir clock.xdc]
set clock_file [open $clock_xdc w]
puts $clock_file "create_clock -name ap_clk -period $clock_period \[get_ports clock\]"
close $clock_file

read_verilog -sv $cmux_source
read_verilog $forward_source
read_verilog $inverse_source
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

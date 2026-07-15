# Post-route a generated SGen transform out of context on the U280 part.
#
# vivado -mode batch -source chisel/scripts/synth_sgen_u280.tcl \
#   -tclargs build/sgen-fpt/forward.v FptSGenForward \
#            build/vivado-sgen-forward 3.425 \
#            xcu280-fsvh2892-2L-e 8 clk

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
report_drc -file [file join $output_dir drc.rpt]
report_power -file [file join $output_dir power.rpt]
write_checkpoint -force [file join $output_dir $top.dcp]

proc count_refs {pattern} {
    return [llength [get_cells -hierarchical -quiet \
        -filter "REF_NAME =~ $pattern"]]
}

set logic_luts [count_refs LUT*]
set flip_flops [count_refs FD*]
set dsp48e2 [count_refs DSP48E2]
set ramb18e2 [count_refs RAMB18E2]
set ramb36e2 [count_refs RAMB36E2]
set uram288 [count_refs URAM288]
set all_ram [count_refs RAM*]
set distributed_ram [expr {$all_ram - $ramb18e2 - $ramb36e2}]
set srl [count_refs SRL*]
set carry8 [count_refs CARRY8]

set timing_paths [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
set wns NA
set achieved_mhz NA
if {[llength $timing_paths] > 0} {
    set wns [format %.3f [get_property SLACK [lindex $timing_paths 0]]]
    set critical_delay [expr {$clock_period - $wns}]
    if {$critical_delay > 0} {
        set achieved_mhz [format %.3f [expr {1000.0 / $critical_delay}]]
    }
}

set metrics_file [open [file join $output_dir metrics.tsv] w]
puts $metrics_file "metric\tvalue"
foreach {metric value} [list \
    top $top \
    clock_port $clock_port \
    part $part \
    clock_period_ns $clock_period \
    wns_ns $wns \
    achieved_mhz $achieved_mhz \
    logic_luts $logic_luts \
    flip_flops $flip_flops \
    dsp48e2 $dsp48e2 \
    ramb18e2 $ramb18e2 \
    ramb36e2 $ramb36e2 \
    uram288 $uram288 \
    distributed_ram $distributed_ram \
    srl $srl \
    carry8 $carry8] {
    puts $metrics_file "$metric\t$value"
}
close $metrics_file

puts "FPT_METRICS top=$top period_ns=$clock_period wns_ns=$wns achieved_mhz=$achieved_mhz LUT=$logic_luts FF=$flip_flops DSP=$dsp48e2 RAMB18=$ramb18e2 RAMB36=$ramb36e2 URAM=$uram288"
if {$wns ne "NA" && $wns < 0} {
    puts "WARNING: routed design misses the requested clock by [expr {-$wns}] ns"
}

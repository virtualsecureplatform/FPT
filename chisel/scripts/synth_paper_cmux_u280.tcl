# Post-route a paper-shaped Chisel CMUX with separately generated SGen
# BlackBoxes out of context on the U280 part. The optional TOP argument also
# selects any emitted single or batched CmuxEngine variant.
#
# vivado -mode batch -source chisel/scripts/synth_paper_cmux_u280.tcl \
#   -tclargs build/chisel-paper/CmuxEngine.sv \
#            build/sgen-fpt/forward.v build/sgen-fpt/inverse.v \
#            build/vivado-paper-cmux 3.425 CmuxEngine \
#            xcu280-fsvh2892-2L-e 8

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

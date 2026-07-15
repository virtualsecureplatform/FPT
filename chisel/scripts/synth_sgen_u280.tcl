# Post-route a generated SGen transform out of context on the U280 part.
#
# vivado -mode batch -source chisel/scripts/synth_sgen_u280.tcl \
#   -tclargs build/sgen-fpt/forward.v FptSGenForward \
#            build/vivado-sgen-forward 3.425

set source_file [lindex $argv 0]
set top [lindex $argv 1]
set output_dir [lindex $argv 2]
set clock_period [lindex $argv 3]
if {$source_file eq "" || $top eq ""} {
    error "usage: SOURCE_V TOP ?OUTPUT_DIR? ?CLOCK_PERIOD_NS?"
}
if {$output_dir eq ""} { set output_dir build/vivado-$top }
if {$clock_period eq ""} { set clock_period 3.425 }

set source_file [file normalize $source_file]
set output_dir [file normalize $output_dir]
file mkdir $output_dir

read_verilog $source_file
synth_design -top $top -part xcu280-fsvh2892-2L-e \
    -mode out_of_context -flatten_hierarchy rebuilt
create_clock -name ap_clk -period $clock_period [get_ports clk]
opt_design
place_design
phys_opt_design
route_design

report_utilization -hierarchical \
    -file [file join $output_dir utilization.rpt]
report_timing_summary -file [file join $output_dir timing_summary.rpt]
report_power -file [file join $output_dir power.rpt]
write_checkpoint -force [file join $output_dir $top.dcp]

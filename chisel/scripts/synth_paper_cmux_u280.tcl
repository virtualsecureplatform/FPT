# Post-route a paper-shaped Chisel CMUX with separately generated SGen
# BlackBoxes out of context on the U280 part. The optional TOP argument also
# selects any emitted single or batched CmuxEngine variant.
#
# vivado -mode batch -source chisel/scripts/synth_paper_cmux_u280.tcl \
#   -tclargs build/chisel-paper/CmuxEngine.sv \
#            build/sgen-fpt/forward.v build/sgen-fpt/inverse.v \
#            build/vivado-paper-cmux 3.425 CmuxEngine

set cmux_source [lindex $argv 0]
set forward_source [lindex $argv 1]
set inverse_source [lindex $argv 2]
set output_dir [lindex $argv 3]
set clock_period [lindex $argv 4]
set top [lindex $argv 5]
if {$cmux_source eq "" || $forward_source eq "" || $inverse_source eq ""} {
    error "usage: CMUX_SV FORWARD_V INVERSE_V ?OUTPUT_DIR? ?CLOCK_PERIOD_NS? ?TOP?"
}
if {$output_dir eq ""} { set output_dir build/vivado-paper-cmux }
if {$clock_period eq ""} { set clock_period 3.425 }
if {$top eq ""} { set top CmuxEngine }

set cmux_source [file normalize $cmux_source]
set forward_source [file normalize $forward_source]
set inverse_source [file normalize $inverse_source]
set output_dir [file normalize $output_dir]
file mkdir $output_dir

read_verilog -sv $cmux_source
read_verilog $forward_source
read_verilog $inverse_source
synth_design -top $top -part xcu280-fsvh2892-2L-e \
    -mode out_of_context -flatten_hierarchy rebuilt
create_clock -name ap_clk -period $clock_period [get_ports clock]
opt_design
place_design
phys_opt_design
route_design

report_utilization -hierarchical \
    -file [file join $output_dir utilization.rpt]
report_timing_summary -file [file join $output_dir timing_summary.rpt]
report_power -file [file join $output_dir power.rpt]
write_checkpoint -force [file join $output_dir $top.dcp]

# Run with:
#   vivado -mode batch -source rtl/scripts/synth_u280.tcl \
#          -tclargs fpt_butterfly build/vivado-butterfly
# A parameterized wide-transform example is:
#   vivado -mode batch -source rtl/scripts/synth_u280.tcl \
#     -tclargs fpt_tangent_fft_wide_core build/vivado-fft-l8 \
#     POINTS=512 LANES=8 DATA_WIDTH=38 TWIDDLE_WIDTH=34 TWIDDLE_FRAC=32

set top [lindex $argv 0]
set output_dir [lindex $argv 1]
set generics [lrange $argv 2 end]
if {$top eq ""} { set top fpt_butterfly }
if {$output_dir eq ""} { set output_dir build/vivado-$top }
file mkdir $output_dir

set rtl_root [file normalize [file join [file dirname [info script]] ..]]
read_verilog -sv [file join $rtl_root fpt_gauss_mul.sv]
read_verilog -sv [file join $rtl_root fpt_butterfly.sv]
read_verilog -sv [file join $rtl_root fpt_complex_mac.sv]
read_verilog -sv [file join $rtl_root fpt_complex_mac_wide.sv]
read_verilog -sv [file join $rtl_root fpt_fft_core.sv]
read_verilog -sv [file join $rtl_root fpt_fft_wide_core.sv]
read_verilog -sv [file join $rtl_root fpt_tangent_fft_core.sv]
read_verilog -sv [file join $rtl_root fpt_tangent_ifft_core.sv]
read_verilog -sv [file join $rtl_root fpt_tangent_fft_wide_core.sv]
read_verilog -sv [file join $rtl_root fpt_tangent_ifft_wide_core.sv]

# Match HOGE's Alveo U280 device and reported 292 MHz operating point.
set synth_args [list -top $top -part xcu280-fsvh2892-2L-e \
                     -flatten_hierarchy rebuilt]
foreach generic $generics {
    lappend synth_args -generic $generic
}
synth_design {*}$synth_args
if {[llength [get_ports -quiet clk]] != 0} {
    create_clock -name ap_clk -period 3.425 [get_ports clk]
}
opt_design
place_design
phys_opt_design
route_design

report_utilization -hierarchical -file [file join $output_dir utilization.rpt]
report_timing_summary -file [file join $output_dir timing_summary.rpt]
report_power -file [file join $output_dir power.rpt]
write_checkpoint -force [file join $output_dir $top.dcp]

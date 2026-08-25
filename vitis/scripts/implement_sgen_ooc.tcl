set rtl [lindex $argv 0]
set top [lindex $argv 1]
set output_dir [lindex $argv 2]
set period [lindex $argv 3]
if {$rtl eq "" || $top eq "" || $output_dir eq "" || $period eq ""} {
  error "usage: implement_sgen_ooc.tcl RTL TOP OUTPUT_DIR PERIOD_NS"
}

file mkdir $output_dir
read_verilog $rtl
synth_design -top $top -part xcu280-fsvh2892-2L-e \
  -mode out_of_context -flatten_hierarchy full
create_clock -name fft_clock -period $period [get_ports clk]
report_utilization -file [file join $output_dir utilization_synth.rpt]
write_checkpoint -force [file join $output_dir synthesized.dcp]

opt_design
place_design
report_utilization -file [file join $output_dir utilization_placed.rpt]
report_timing_summary -delay_type max -max_paths 100 \
  -file [file join $output_dir timing_placed.rpt]
write_checkpoint -force [file join $output_dir placed.dcp]

phys_opt_design
report_timing_summary -delay_type max -max_paths 100 \
  -file [file join $output_dir timing_physopt.rpt]
write_checkpoint -force [file join $output_dir physopt.dcp]

route_design
report_route_status -file [file join $output_dir route_status.rpt]
report_timing_summary -delay_type max -max_paths 100 \
  -file [file join $output_dir timing_routed.rpt]
report_timing -delay_type max -max_paths 100 -nworst 10 \
  -file [file join $output_dir worst_setup_paths.rpt]
report_utilization -file [file join $output_dir utilization_routed.rpt]
write_checkpoint -force [file join $output_dir routed.dcp]

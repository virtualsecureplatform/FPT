set rtl [lindex $argv 0]
set top [lindex $argv 1]
set report [lindex $argv 2]
if {$rtl eq "" || $top eq "" || $report eq ""} {
  error "usage: synth_sgen_resource.tcl RTL TOP REPORT"
}

read_verilog $rtl
synth_design -top $top -part xcu280-fsvh2892-2L-e -flatten_hierarchy full
report_utilization -file $report

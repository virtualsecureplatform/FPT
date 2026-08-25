set checkpoint [lindex $argv 0]
set output_dir [lindex $argv 1]
if {$checkpoint eq "" || $output_dir eq ""} {
  error "usage: vivado -mode batch -source analyze_failed_route.tcl -tclargs CHECKPOINT OUTPUT_DIR"
}

file mkdir $output_dir
open_checkpoint $checkpoint

report_route_status -file [file join $output_dir route_status.rpt]
report_timing_summary -delay_type max -max_paths 100 \
  -file [file join $output_dir timing_summary.rpt]
report_timing -delay_type max -max_paths 200 -nworst 20 \
  -path_type full_clock_expanded \
  -file [file join $output_dir worst_setup_paths.rpt]
report_design_analysis -congestion \
  -file [file join $output_dir congestion.rpt]
report_utilization -hierarchical -hierarchical_depth 8 \
  -file [file join $output_dir hierarchical_utilization.rpt]
report_utilization -slr -file [file join $output_dir slr_utilization.rpt]

set root [get_cells -quiet -hierarchical -filter \
  {NAME =~ */controller/core/engine/blindRotate/blindRotate/cmux}]
if {[llength $root] == 1} {
  report_utilization -cells $root -hierarchical -hierarchical_depth 8 \
    -file [file join $output_dir cmux_hierarchical_utilization.rpt]
  foreach name {
    forward forwardInputBoundary coefficients external inverseBoundary inverse
    keyBuffer keyReadRequests pendingRequests forwardTags inverseTags
  } {
    set cell [get_cells -quiet ${root}/$name]
    if {[llength $cell] == 1} {
      report_utilization -cells $cell -slr \
        -file [file join $output_dir ${name}_slr_utilization.rpt]
    }
  }
}

close_design

set checkpoint [lindex $argv 0]
set output_dir [lindex $argv 1]
if {$checkpoint eq "" || $output_dir eq ""} {
  error "usage: vivado -mode batch -source report_fpt_placed_hierarchy.tcl -tclargs CHECKPOINT OUTPUT_DIR"
}
file mkdir $output_dir
open_checkpoint $checkpoint
set root [get_cells -hierarchical -filter \
  {NAME =~ */controller/core/engine/blindRotate/blindRotate/cmux}]
if {[llength $root] != 1} {
  error "expected one placed CMUX hierarchy, found [llength $root]: $root"
}
report_utilization -cells $root -hierarchical -hierarchical_depth 5 \
  -file [file join $output_dir fpt_hierarchical_utilization.rpt]
foreach name {forward forwardInputBoundary coefficients external inverseBoundary inverse} {
  set cell [get_cells -quiet ${root}/$name]
  if {[llength $cell] == 1} {
    report_utilization -cells $cell -slr \
      -file [file join $output_dir ${name}_slr_utilization.rpt]
  }
}
foreach slr {SLR0 SLR1 SLR2} {
  set cells [get_cells -hierarchical -filter \
    "NAME =~ ${root}/* && USER_SLR_ASSIGNMENT == $slr"]
  set stream [open [file join $output_dir ${slr}_assigned_cells.txt] w]
  foreach cell $cells { puts $stream $cell }
  close $stream
}
close_design

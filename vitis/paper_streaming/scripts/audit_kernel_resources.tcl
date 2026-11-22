# Read-only audit of the complete kernel in a synthesized or routed DCP.
if {$argc!=2} {error "usage: CHECKPOINT NEW_METRICS_PREFIX"}
set checkpoint [file normalize [lindex $argv 0]]
set prefix [file normalize [lindex $argv 1]]
if {[file exists ${prefix}_resources.tsv] || [file exists ${prefix}_utilization.rpt]} {
  error "refusing to overwrite kernel resource audit"
}
file mkdir [file dirname $prefix]
if {[catch {
  open_checkpoint $checkpoint
  set ::env(FPT_KERNEL_METRICS_PREFIX) $prefix
  source [file join [file dirname [info script]] check_kernel_resources.tcl]
  close_design
} message]} {
  puts stderr "PAPER_KERNEL_RESOURCE_AUDIT_FAIL $message"
  # Preserve unadmitted diagnostics if Vivado names the checkpoint scope
  # differently than its RTL top. These reports never count as admission.
  if {[llength [current_design -quiet]]} {
    puts "PAPER_RESOURCE_DIAGNOSTIC design=[current_design] top=[get_property TOP [current_design]]"
    if {[string first "could not uniquely identify" $message]>=0} {
      report_utilization -hierarchical -file ${prefix}_unadmitted_utilization.rpt
    }
  }
  exit 1
}
exit 0

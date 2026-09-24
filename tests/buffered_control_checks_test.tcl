# Match Vivado's Tcl 8.5 even when the host test runner provides Tcl 8.6.
set had_try [expr {[llength [info commands try]] != 0}]
if {$had_try} {rename try test_disabled_try}
source [file join [file dirname [info script]] ../vitis/scripts/check_fpt_buffered_controls.tcl]
set ::env(FPT_U280_BUFFERED_OUTPUT) 0
fpt_check_buffered_controls unused
set ::env(FPT_U280_BUFFERED_OUTPUT) 1
set missing 0
set wrong 0
proc get_cells {args} {
  set f [lindex $args end]
  if {[string first {*/sequencer/outputRetirement} $f] >= 0} {return top/controller/sequencer/outputRetirement}
  if {[string first {*/coefficients/loadCommandToken} $f] >= 0} {return top/controller/core/coefficients/loadCommandToken}
  if {$::missing} {return {}}
  regexp {NAME =~ ([^ ]+)/value_reg} $f -> root
  return [list "$root/value_reg\[0\]" "$root/value_reg\[0\]_rep__1"]
}
proc get_property {p v} {return $v}
proc get_sites {args} {return [lindex $args end]}
proc get_slrs {args} {
  if {$::wrong} {return SLR2}
  return [expr {[string first destinationRx [lindex $args end]] >= 0 ? "SLR1" : "SLR0"}]
}
set report [file tempfile tmp]
close $report
fpt_check_buffered_controls $tmp 0
fpt_check_buffered_controls $tmp 1
set missing 1
if {![catch {fpt_check_buffered_controls $tmp 0}]} {error "missing cuts accepted"}
set missing 0; set wrong 1
if {![catch {fpt_check_buffered_controls $tmp 1}]} {error "wrong SLR accepted"}
file delete $tmp
if {$had_try} {rename test_disabled_try try}
puts BUFFERED_CONTROL_CHECKS_TEST_PASS

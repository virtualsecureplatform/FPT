# Offline contracts for the optional checker: disabled mode, correct banks,
# missing banks, replication, and placement failures must be distinguished.
set repo [file normalize [file join [file dirname [info script]] ..]]
source [file join $repo vitis/scripts/check_fpt_buffered_output.tcl]
set ::env(FPT_U280_BUFFERED_OUTPUT) 0
fpt_check_buffered_output unused
set ::env(FPT_U280_BUFFERED_OUTPUT) 1
set bank_count 16
set wrong_slr 0
set root_refs [dict create root BufferedSampleExtract noise Queue2_BufferedSampleExtract_Anon]
proc get_cells {args} {
  global bank_count
  set filter [lindex $args end]
  if {$filter eq "REF_NAME =~ *BufferedSampleExtract* && IS_PRIMITIVE == 0"} {return [dict keys $::root_refs]}
  if {[string first snapshotMaskMemory $filter] >= 0} {
    set result {}
    for {set i 0} {$i < $bank_count} {incr i} {lappend result root/snapshotMaskMemory$i}
    return $result
  }
  return {}
}
proc get_property {property cell} {
  if {$property eq "NAME"} {return $cell}
  if {$property eq "REF_NAME"} {
    if {[dict exists $::root_refs $cell]} {return [dict get $::root_refs $cell]}
    return RAMB36E2
  }
  error "unexpected property $property"
}
proc fpt_require_placed_slr {cell expected} {
  if {$::wrong_slr} {error "wrong SLR"}
  if {$expected ne "SLR0"} {error "wrong placement contract"}
}
set report [file tempfile report_path]
close $report
fpt_check_buffered_output $report_path 1
dict set root_refs root ulp_FptBlindRotateKernel_1_0_BufferedSampleExtract
fpt_check_buffered_output $report_path 1
dict set root_refs duplicate other_BufferedSampleExtract
if {![catch {fpt_check_buffered_output $report_path} message] || ![string match {*found 2} $message]} {
  error "accepted ambiguous extractor roots: $message"
}
dict unset root_refs duplicate
dict unset root_refs root
if {![catch {fpt_check_buffered_output $report_path} message] || ![string match {*found 0} $message]} {
  error "accepted a helper module as the extractor: $message"
}
dict set root_refs root ulp_FptBlindRotateKernel_1_0_BufferedSampleExtract
foreach bad {15 17 32} {
  set bank_count $bad
  if {![catch {fpt_check_buffered_output $report_path}]} {error "accepted $bad banks"}
}
set bank_count 16
set wrong_slr 1
if {![catch {fpt_check_buffered_output $report_path 1}]} {error "accepted wrong SLR"}
file delete $report_path
puts "BUFFERED_OUTPUT_CHECKER_TEST_PASS"

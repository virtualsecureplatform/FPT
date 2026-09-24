# Shared discovery for the optional 32-bit preprocessing physical cut.
proc fpt_prepared_word_root {} {
  set roots [get_cells -hier -quiet -filter {NAME =~ *preparedWordLink}]
  set required [expr {[info exists ::env(FPT_U280_PREPROCESSED_INPUT)] &&
    $::env(FPT_U280_PREPROCESSED_INPUT) eq "1"}]
  if {[llength $roots] == 0 && !$required} {return ""}
  if {[llength $roots] != 1} {error "prepared word link count [llength $roots], expected 1"}
  return [lindex $roots 0]
}
proc fpt_prepared_word_registers {root role field} {
  if {$role ni {sourceTx destinationRx} || $field ni {payload token}} {
    error "unknown prepared word register role: $role/$field"
  }
  set cells [get_cells -hier -quiet -filter "NAME =~ ${root}/${role}/${field}/value_reg* && REF_NAME =~ FD*"]
  set expected [expr {$field eq "payload" ? 32 : 1}]
  if {[llength $cells] != $expected} {
    error "prepared word $role/$field count [llength $cells], expected $expected"
  }
  return $cells
}

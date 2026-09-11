source [file join [file dirname [info script]] .. vitis scripts check_fpt_registered_links.tcl]
proc get_cells {args} {
  set expression [lindex $args [expr {[lsearch -exact $args -filter] + 1}]]
  if {![regexp {^NAME =~ (\S+) && REF_NAME =~ FD\*$} $expression -> pattern]} {
    error "missing register-type filter: $expression"
  }
  set result {}
  foreach cell $::fixtures {if {[string match $pattern $cell]} {lappend result $cell}}
  return $result
}
foreach {role names} {
  write {memory/localWriteControl_0 memory/localControls_localWriteControl_15}
  selector {scratchSelector_prime_0 localSelectors_scratchSelector_current1_7}
  field {scratchFieldStage_prime selectionStageSpan_tail_scratchFieldStage_span}
} {
  set fixtures {}
  foreach name $names {lappend fixtures "top/coefficients/$name/value_reg\[0\]"}
  set expected $fixtures
  lappend fixtures {top/coefficients/memory/unrelated/value_reg[0]}
  lappend fixtures {top/coefficients/memory/localControls_localWriteControl_0/not_value[0]}
  if {[fpt_locality_registers $role] ne $expected} {error "missed/overmatched $role: [fpt_locality_registers $role]"}
}
if {![catch {fpt_locality_registers invalid}]} {error "invalid role accepted"}
foreach role {sourceTx middleRx middleTx destinationRx} {
  set fixtures [list "top/cmux/coefficientDigitLink/$role/outputPayload_payloadBoundary/value_reg\[0\]"]
  foreach bit {2524 2559} {
    lappend fixtures "top/cmux/coefficientDigitLink/$role/outputPayload_payloadBoundary/value_reg\[$bit\]"
  }
  set expected $fixtures
  foreach leaf {value_reg[2524]_psdsp value_reg[2524]_psdsp_24 value_reg[0]_replica value_reg value_reg[bad] value_reg[0]/child} {
    lappend fixtures "top/cmux/coefficientDigitLink/$role/outputPayload_payloadBoundary/$leaf"
  }
  lappend fixtures "top/cmux/unrelated/$role/outputPayload_payloadBoundary/value_reg\[0\]"
  lappend fixtures "top/cmux/coefficientDigitLink/$role/outputControl_controlBoundary/value_reg\[0\]"
  if {[fpt_digit_registers $role] ne $expected} {error "missed/overmatched digit $role"}
}
if {![catch {fpt_digit_registers invalid}]} {error "invalid digit role accepted"}
puts "REGISTERED_LINK_NAMES_PASS"

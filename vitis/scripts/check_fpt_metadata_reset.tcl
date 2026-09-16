# Requires check_fpt_inverse_twiddles.tcl helpers.
proc fpt_metadata_reset_valid {required driverKinds ports} {
  if {!$required} {return [expr {$driverKinds eq {GND} && [llength $ports] == 0}]}
  if {[llength $driverKinds] == 1 && [lindex $driverKinds 0] ni {GND VCC} && [llength $ports] == 0} {return 1}
  return [expr {[llength $driverKinds] == 0 && $ports eq {reset}}]
}
proc fpt_metadata_bit {name} {
  if {[regexp {value_reg\[([0-9]+)\]$} $name -> bit]} {return $bit}
  if {[string match */value_reg $name]} {return 0}
  error "unrecognized metadata register name: $name"
}
proc fpt_check_metadata_reset {report {unit both}} {
  if {$unit ni {both coefficient key}} {error "unknown reset check unit"}
  set out [open $report w]
  set cleared 0; set retained 0
  set groups {}
  if {$unit in {both coefficient}} {
    lappend groups [list 224 0 {(NAME =~ *scratchSelector*/*value_reg* || NAME =~ *scratchHeadChoice*/*value_reg* || NAME =~ *scratchTailWriteHalf*/*value_reg*) && REF_NAME =~ FD*}]
    lappend groups [list 192 4 {(NAME =~ memory/*localWriteControl_*/value_reg* || NAME =~ */memory/*localWriteControl_*/value_reg*) && REF_NAME =~ FD*}]
  }
  if {$unit in {both key}} {
    lappend groups [list 104 8 {NAME =~ *keyWriteTile*/*writeControl/value_reg* && REF_NAME =~ FD*}]
  }
  set enabled [expr {[info exists ::env(FPT_U280_MINIMAL_METADATA_RESET)] && $::env(FPT_U280_MINIMAL_METADATA_RESET) eq "1"}]
  foreach group $groups {
    lassign $group count maskBits pattern
    set cells [get_cells -hier -filter $pattern]
    if {[llength $cells] != $count} {error "metadata register count [llength $cells] != $count"}
    foreach cell $cells {
      if {![get_property DONT_TOUCH $cell]} {error "metadata preservation lost: $cell"}
      set bit [fpt_metadata_bit [get_property NAME $cell]]
      set resetRequired [expr {!$enabled || $bit < $maskBits}]
      set pins [get_pins -of_objects $cell -filter {REF_PIN_NAME == R || REF_PIN_NAME == CLR || REF_PIN_NAME == PRE || REF_PIN_NAME == S}]
      if {[llength $pins] != 1} {error "unexpected metadata reset pin shape: $cell"}
      set nets [get_nets -segments -of_objects $pins]
      set drivers [get_cells -of_objects [get_pins -leaf -of_objects $nets -filter {DIRECTION == OUT}]]
      set kinds {}
      if {[llength $drivers]} {set kinds [get_property REF_NAME $drivers]}
      set portObjects [get_ports -quiet -of_objects $nets -filter {DIRECTION == IN}]
      set ports {}
      if {[llength $portObjects]} {set ports [get_property NAME $portObjects]}
      if {![fpt_metadata_reset_valid $resetRequired $kinds $ports]} {error "metadata reset policy mismatch: $cell required=$resetRequired drivers=$kinds ports=$ports"}
      if {$resetRequired} {incr retained} else {
        incr cleared
        set ce [get_nets -segments -of_objects [get_pins -of_objects $cell -filter {REF_PIN_NAME == CE}]]
        set d [get_cells -of_objects [get_pins -leaf -of_objects $ce -filter {DIRECTION == OUT}]]
        if {[llength $d] != 1 || [get_property REF_NAME $d] ne "VCC"} {error "metadata gained enable: $cell"}
      }
      puts $out "METADATA\t$cell\treset=$resetRequired"
    }
  }
  close $out
  set expected [expr {!$enabled ? 0 : ($unit eq "coefficient" ? 352 : ($unit eq "key" ? 40 : 392))}]
  if {$cleared != $expected} {error "unreset count $cleared != $expected"}
  puts "METADATA_RESET_STRUCTURE_PASS unit=$unit unreset=$cleared reset=$retained"
}

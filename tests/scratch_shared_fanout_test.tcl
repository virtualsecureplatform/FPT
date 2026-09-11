source [file join [file dirname [info script]] .. vitis scripts check_fpt_scratch_groups.tcl]
proc fixture {bits groups extra} {
  set names {}
  foreach bit $bits {
    for {set g 0} {$g < $groups} {incr g} {
      lappend names [format {scratch/lane_groups[%d].island/readAddressesGroup_reg[%d]/D} $g $bit]
    }
  }
  for {set i 0} {$i < $extra} {incr i} {lappend names "metadata/lut$i/I0"}
  return $names
}
foreach {bits groups extra expected} {
  {10 14} 8 7 23
  {3 7 11 15} 8 0 32
  {3 7 11 15} 8 32 64
} {
  if {[lindex [fpt_scratch_input_loads [fixture $bits $groups $extra]] 0] != $expected} {error "incorrect input count"}
}
foreach names [list [fixture {10} 9 0] [fixture {3 7 11 15} 8 33] [list {scratch/bank/readAddressCut_reg[0]/D}]] {
  if {![catch {fpt_scratch_input_loads $names}]} {error "unsafe broadcast was accepted"}
}
puts SCRATCH_SHARED_FANOUT_PASS

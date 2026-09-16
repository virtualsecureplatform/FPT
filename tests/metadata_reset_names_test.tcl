source [file join [file dirname [info script]] .. vitis scripts check_fpt_metadata_reset.tcl]
foreach {name expected} {top/value_reg 0 {top/value_reg[11]} 11 {top/value_reg[0]} 0} {
  if {[fpt_metadata_bit $name] != $expected} {error "bad metadata bit"}
}
foreach name {{top/value_reg[1]_replica} top/not_value_reg} {
  if {![catch {fpt_metadata_bit $name}]} {error "bad register name accepted"}
}
foreach {required kinds ports expected} {
  1 {} reset 1
  1 FDRE {} 1
  1 LUT1 {} 1
  1 GND {} 0
  1 VCC {} 0
  1 {} {} 0
  1 {} clock 0
  1 {FDRE FDRE} {} 0
  0 GND {} 1
  0 {} reset 0
  0 VCC {} 0
  0 FDRE {} 0
} {
  if {[fpt_metadata_reset_valid $required $kinds $ports] != $expected} {error "reset driver validation mismatch"}
}
puts "METADATA_RESET_NAMES_PASS"

source [file join [file dirname [info script]] .. vitis scripts check_fpt_local_controls.tcl]
proc get_cells {args} {
  set filter [lindex $args end]
  if {![regexp {^NAME =~ (\S+) && REF_NAME =~ (\S+) && IS_PRIMITIVE == 0$} $filter -> pattern ref]} {
    error "missing local-bank type/primitive filter: $filter"
  }
  set result {}
  foreach {name type primitive} $::fixtures {
    if {!$primitive && [string match $pattern $name] && [string match $ref $type]} {lappend result $name}
  }
  return $result
}
set fixtures {
  top/rotating/finalBankTiles_0_0_0 FptFinalAccumulatorLocalUltraBank 0
  top/rotating/finalBankOutputs_finalBankTiles_1_1_10 ulp_FptFinalAccumulatorLocalDistributedBank_1 0
  top/unrelated FptFinalAccumulatorLocalUltraBank 0
  top/rotating/finalBankTiles_0_0_0/memory RAM32M16 1
}
set expected {top/rotating/finalBankTiles_0_0_0 top/rotating/finalBankOutputs_finalBankTiles_1_1_10}
if {[fpt_final_local_banks] ne $expected} {error "missed or overmatched local banks"}
puts "LOCAL_CONTROL_NAMES_PASS"

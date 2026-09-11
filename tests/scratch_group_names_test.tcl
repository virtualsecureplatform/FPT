source [file join [file dirname [info script]] .. vitis scripts check_fpt_scratch_groups.tcl]
set parent {scratch/lane_groups[0].island}
foreach {child expected} {
  {scratch/lane_groups[0].island/lane_groups[0].local_scratch/prime_banks[0].prime_bank_register/captureEnableCut_reg/D} 1
  {scratch/lane_groups[0].island/writeAddressGroup_reg[1]/D} 1
  {scratch/lane_groups[1].island/writeAddressGroup_reg[1]/D} 0
  {scratch/lane_groups0.island/writeAddressGroup_reg[1]/D} 0
  {scratch/lane_groups[0].island_extra/register/D} 0
  {scratch/lane_groups[0].island} 0
} {
  if {[fpt_scratch_group_contains $parent $child] != $expected} {
    error "incorrect hierarchy membership: $child"
  }
}
puts SCRATCH_GROUP_NAMES_PASS

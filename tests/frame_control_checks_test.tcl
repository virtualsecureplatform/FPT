source [file join [file dirname [info script]] .. vitis scripts check_fpt_frame_controls.tcl]
proc get_property {property point} {
  if {$property eq "NAME"} {return [lindex $point 1]}
  if {$property eq "CLASS"} {return [lindex $point 0]}
  error "unexpected property $property"
}
foreach {point expected} {
  {port ap_rst_n} 1
  {port reset} 1
  {port start_early} 0
  {pin top/proc_sys_reset_kernel_slr0/U0/reset_reg/Q} 1
  {pin top/controller/local_start_reg/Q} 0
  {pin top/frameRecoveryRemaining_reg[0]/Q} 0
} {
  if {[fpt_frame_reset_origin $point] != $expected} {error "reset origin classification: $point"}
}
puts FRAME_CONTROL_CHECKS_PASS

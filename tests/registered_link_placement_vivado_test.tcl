# Vivado -mode batch -source this_file -tclargs existing_distributed_bank.sv
source [file join [file dirname [info script]] .. vitis scripts check_fpt_registered_links.tcl]
if {[llength $argv] != 1} {error "expected existing FptFinalAccumulatorDistributedBank.sv"}
create_project -in_memory -part xcu280-fsvh2892-2L-e
read_verilog -sv [lindex $argv 0]
synth_design -top FptFinalAccumulatorDistributedBank -generic WIDTH=14 -mode out_of_context
create_clock -period 5 [get_ports clock]
opt_design
place_design
set macros [get_cells -hier -filter {REF_NAME == RAM32M16}]
if {[llength $macros] == 0} {error "RAM32M16 fixture did not infer a macro"}
set internal [get_cells -hier -filter {PRIMITIVE_LEVEL == INTERNAL && PRIMITIVE_SUBGROUP == LUTRAM}]
if {[llength $internal] != 16 * [llength $macros]} {error "missing internal RAM cells"}
foreach cell [concat $macros $internal [get_cells -hier -filter {REF_NAME =~ FD*}]] {
  set site [lindex [get_sites -of_objects $cell] 0]
  set expected [get_property NAME [get_slrs -of_objects $site]]
  fpt_require_placed_slr $cell $expected
  set wrong [expr {$expected eq "SLR0" ? "SLR1" : "SLR0"}]
  if {![catch {fpt_require_placed_slr $cell $wrong} message] || ![string match {*ownership mismatch*} $message]} {
    error "wrong-SLR placement was not rejected for $cell: $message"
  }
}
foreach macro $macros {
  puts "MACRO_PLACEMENT cell=$macro direct=[get_slrs -quiet -of_objects $macro] physical=[fpt_placed_slr $macro]"
}
puts "REGISTERED_LINK_PLACEMENT_VIVADO_PASS macros=[llength $macros] internal=[llength $internal]"
close_design
exit

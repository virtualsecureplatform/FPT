# Source after check_fpt_inverse_twiddles.tcl (fanout/clocking helpers).
proc fpt_check_bank_local_init {report {placed 0}} {
  if {![info exists ::env(FPT_U280_BANK_LOCAL_ACCUMULATOR_INIT)] || $::env(FPT_U280_BANK_LOCAL_ACCUMULATOR_INIT) ne "1"} {return}
  global fpt_it_constant_cache
  if {![info exists fpt_it_constant_cache]} {set fpt_it_constant_cache [dict create]}
  set groups [get_cells -hier -filter {REF_NAME =~ *FptBankLocalAccumulatorInit_* && IS_PRIMITIVE == 0}]
  if {[llength $groups] != 16} {error "bank-local initialization needs 16 four-lane groups"}
  set obsolete [get_cells -hier -filter {NAME =~ *loadCommitData* && IS_PRIMITIVE}]
  if {[llength $obsolete]} {error "separate load payload remains: $obsolete"}
  set out [open $report w]
  foreach group $groups {
    set name [get_property NAME $group]
    set modes [get_cells -hier -filter "NAME =~ $name/loadActive_reg* && REF_NAME =~ FD*"]
    set descriptor [get_cells -hier -filter "(NAME =~ $name/positive_reg* || NAME =~ $name/negative_reg* || NAME =~ $name/exponent_reg*) && REF_NAME =~ FD*"]
    set counters [get_cells -hier -filter "NAME =~ $name/localBeat_reg* && REF_NAME =~ FD*"]
    set data [get_cells -hier -filter "NAME =~ $name/*writeData_reg* && REF_NAME =~ FD*"]
    if {[llength $modes] != 4 || [llength $descriptor] != 75 || [llength $counters] != 4 || [llength $data] != 512} {
      error "bank-local shape $name: mode=[llength $modes] descriptor=[llength $descriptor] beat=[llength $counters] data=[llength $data]"
    }
    foreach cell [concat $modes $descriptor $counters] {
      if {![get_property DONT_TOUCH $cell]} {error "merged bank-local control: $cell"}
    }
    foreach cell $data {fpt_it_always_clocked $cell}
    foreach cell $modes {fpt_it_limit $cell Q 256}
    foreach cell $descriptor {fpt_it_limit $cell D 256; fpt_it_limit $cell Q 256}
    if {$placed} {
      foreach cell [concat $modes $descriptor $counters $data] {fpt_require_placed_slr $cell SLR0}
    }
    puts $out "INIT_GROUP\t$name\t[llength $descriptor]\t[llength $data]"
  }
  # Data must reach URAM directly from the unified commit FFs. A residual
  # load/update mux between the FFs and DIN would introduce a LUT driver here.
  set memory [get_cells -hier -filter {NAME =~ *forwardMemories* && REF_NAME =~ URAM*}]
  if {[llength $memory] != 128} {error "accumulator URAM allocation changed"}
  set pins [get_pins -of_objects $memory -filter {REF_PIN_NAME =~ DIN_* && DIRECTION == IN}]
  if {![llength $pins]} {error "missing accumulator data pins"}
  set nets [get_nets -segments -of_objects $pins]
  set drivers [get_cells -of_objects [get_pins -leaf -of_objects $nets -filter {DIRECTION == OUT}]]
  foreach driver $drivers {
    set kind [get_property REF_NAME $driver]
    set name [get_property NAME $driver]
    if {$kind ni {GND VCC} && !([string match FD* $kind] && [string match *bankLocalInit*/*writeData_reg* $name])} {
      error "post-commit data logic remains: $name $kind"
    }
  }
  close $out
  puts "BANK_LOCAL_INIT_STRUCTURE_PASS groups=16 uram=128"
}

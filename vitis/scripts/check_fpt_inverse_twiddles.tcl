# Opt-in gates for the fixed-rate inverse link and local forward twiddle ROMs.
# Source after check_fpt_registered_links.tcl for its physical ownership helpers.
proc fpt_it_loads {cell pin} {
  set nets [get_nets -quiet -segments -of_objects [get_pins -quiet -of_objects $cell -filter "REF_PIN_NAME == $pin"]]
  if {![llength $nets]} {return {}}
  return [get_pins -quiet -leaf -of_objects $nets -filter {DIRECTION == IN}]
}
proc fpt_it_limit {cell pin limit} {
  set count [llength [fpt_it_loads $cell $pin]]
  if {$count > $limit} {error "inverse/twiddle $pin fanout $count > $limit: $cell"}
  return $count
}
proc fpt_it_always_clocked {cell} {
  global fpt_it_constant_cache
  foreach pin [get_pins -quiet -of_objects $cell -filter {REF_PIN_NAME == CE || REF_PIN_NAME == R || REF_PIN_NAME == CLR}] {
    set key [get_property NAME [get_nets -of_objects $pin]]
    if {![dict exists $fpt_it_constant_cache $key]} {
      set nets [get_nets -segments -of_objects $pin]
      set drivers [get_cells -of_objects [get_pins -leaf -of_objects $nets -filter {DIRECTION == OUT}]]
      set kind [expr {[llength $drivers] == 1 ? [get_property REF_NAME $drivers] : "MULTIPLE_OR_MISSING"}]
      dict set fpt_it_constant_cache $key $kind
    }
    set expected [expr {[get_property REF_PIN_NAME $pin] eq "CE" ? "VCC" : "GND"}]
    if {[dict get $fpt_it_constant_cache $key] ne $expected} {
      error "payload is reset or enabled dynamically: $pin net=$key expected=$expected"
    }
  }
}
proc fpt_check_inverse_twiddles {report {placed 0} {kernel 1}} {
  global fpt_it_constant_cache
  set fpt_it_constant_cache [dict create]
  set out [open $report w]
  if {$kernel && [info exists ::env(FPT_U280_FIXED_RATE_INVERSE_INPUT_LINK)] && $::env(FPT_U280_FIXED_RATE_INVERSE_INPUT_LINK) eq "1"} {
    set tx [get_cells -hier -filter {NAME =~ *externalOutputPipeline/*payloadBoundary/value_reg* && REF_NAME =~ FD*}]
    set rx [get_cells -hier -filter {NAME =~ *inverseBoundary/*payloadBoundary/value_reg* && REF_NAME =~ FD*}]
    fpt_check_link_pair $tx $rx 3845 SLR1 SLR0 $placed $out
    foreach cell [concat $tx $rx] {fpt_it_always_clocked $cell}
    set txc [get_cells -hier -filter {NAME =~ *externalOutputPipeline/*controlBoundary/value_reg* && REF_NAME =~ FD*}]
    set rxc [get_cells -hier -filter {NAME =~ *inverseBoundary/*controlBoundary/value_reg* && REF_NAME =~ FD*}]
    fpt_check_link_pair $txc $rxc 2 SLR1 SLR0 $placed $out
    set obsolete [get_cells -hier -filter {NAME =~ *inverseBoundary/head* || NAME =~ *inverseBoundary/tail* || NAME =~ *inverseBoundary/count*}]
    if {[llength $obsolete]} {error "elastic inverse state remains: $obsolete"}
    puts $out "FIXED_INVERSE\t3845\tSLR1\tSLR0"
  }
  if {[info exists ::env(FPT_SGEN_FORWARD_TWIDDLE_ISLAND_CONSUMERS)] && $::env(FPT_SGEN_FORWARD_TWIDDLE_ISLAND_CONSUMERS) eq "4"} {
    set f [open $::env(FPT_SGEN_FORWARD) r]; set rtl [read $f]; close $f
    set expected [regexp -all -line {^// twiddle_island name=} $rtl]
    set regions_expected [regexp -all -line {^// twiddle_region name=} $rtl]
    if {$expected == 0} {error "no forward twiddle islands in manifest"}
    set islands [get_cells -hier -filter {REF_NAME =~ *SGenForwardTwiddleIsland_* && IS_PRIMITIVE == 0}]
    set regions [get_cells -hier -filter {REF_NAME =~ *SGenTwiddleAddressRegion* && IS_PRIMITIVE == 0}]
    if {[llength $islands] != $expected || [llength $regions] != $regions_expected} {
      error "twiddle manifest mismatch islands=[llength $islands]/$expected regions=[llength $regions]/$regions_expected"
    }
    foreach island $islands {
      set name [get_property NAME $island]
      set leaf [file tail $name]
      if {![regexp -line "^// twiddle_island name=$leaf address=\\S+ cycles=(\\d+) width=(\\d+) stored=(\\d+) consumers=" $rtl -> cycles width stored]} {error "missing manifest $name"}
      set coefficients [get_cells -hier -filter "NAME =~ ${name}/coefficient_reg* && REF_NAME =~ FD*"]
      if {[llength $coefficients] != $stored} {error "twiddle coefficient FF count $name [llength $coefficients] != $stored"}
      set maxq 0
      foreach cell $coefficients {
        set maxq [expr {max($maxq,[fpt_it_limit $cell Q 64])}]
        fpt_it_always_clocked $cell
        if {$placed} {fpt_require_placed_slr $cell SLR2}
      }
      set phases [get_cells -hier -filter "NAME =~ ${name}/phase_*_reg* && REF_NAME =~ FD*"]
      set address_width [llength [get_pins -of_objects $island -filter {REF_PIN_NAME =~ phase* && DIRECTION == IN}]]
      if {$address_width < 1 || [llength $phases] != ($cycles - 1) * $address_width} {
        error "twiddle address pipeline count $name phases=[llength $phases] cycles=$cycles address_width=$address_width"
      }
      foreach cell $phases {
        fpt_it_limit $cell D 256
        fpt_it_limit $cell Q 64
        fpt_it_always_clocked $cell
        if {$placed} {fpt_require_placed_slr $cell SLR2}
      }
      puts $out "TWIDDLE\t$name\t[llength $coefficients]\t[llength $phases]\t$maxq"
    }
    foreach region $regions {
      set name [get_property NAME $region]
      set regs [get_cells -hier -filter "NAME =~ ${name}/value_reg* && REF_NAME =~ FD*"]
      if {![llength $regs]} {error "missing address region registers $name"}
      foreach cell $regs {
        fpt_it_limit $cell D 256
        fpt_it_limit $cell Q 32
        fpt_it_always_clocked $cell
        if {$placed} {fpt_require_placed_slr $cell SLR2}
      }
      puts $out "TWIDDLE_REGION\t$name\t[llength $regs]"
    }
  }
  close $out
  puts "INVERSE_TWIDDLE_STRUCTURE_PASS kernel=$kernel placed=$placed"
}

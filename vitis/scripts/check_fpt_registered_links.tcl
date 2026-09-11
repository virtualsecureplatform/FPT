# Explicit payload topology is a contract; Laguna mapping is a diagnostic.
proc fpt_placed_slr {cell} {
  # Vivado 2023.2 can return no SLR for a placed RAM macro (e.g. RAM32M16)
  # when queried directly. Its physical sites, including the internal RAM
  # cells' sites, do resolve. Do not discard MACRO or INTERNAL primitives.
  set sites [lsort -unique [get_sites -quiet -of_objects $cell]]
  if {[llength $sites] == 0} {error "no physical placement site for $cell"}
  set owners {}
  foreach site $sites {
    set slrs [get_slrs -quiet -of_objects $site]
    if {[llength $slrs] != 1} {error "unresolved physical SLR for $cell at $site: $slrs"}
    lappend owners [get_property NAME $slrs]
  }
  set owners [lsort -unique $owners]
  if {[llength $owners] != 1} {error "physical sites span multiple SLRs for $cell: $owners"}
  return [lindex $owners 0]
}
proc fpt_require_placed_slr {cell expected} {
  set actual [fpt_placed_slr $cell]
  if {$actual ne $expected} {error "SLR ownership mismatch: $cell expected=$expected actual=$actual"}
}
proc fpt_locality_registers {role} {
  # Chisel prefixes suggested instance names with their enclosing Scala vals.
  # Match that optional prefix, while retaining the bank/leaf/type constraints.
  switch -- $role {
    write {set pattern {*/memory/*localWriteControl_*/value_reg*}}
    selector {set pattern {*/*scratchSelector_*/value_reg*}}
    field {set pattern {*/*scratchFieldStage_*/value_reg*}}
    default {error "unknown locality register role: $role"}
  }
  return [get_cells -hier -filter "NAME =~ $pattern && REF_NAME =~ FD*"]
}
proc fpt_check_link_pair {sources destinations expected source_slr destination_slr placed out} {
  if {[llength $sources] != $expected || [llength $destinations] != $expected} {
    error "SLR payload bank count: TX=[llength $sources] RX=[llength $destinations] expected=$expected"
  }
  set receiver_set [dict create]
  foreach cell $destinations { dict set receiver_set $cell 1 }
  set reached [dict create]
  set laguna_pairs 0
  foreach cell $sources {
    set nets [get_nets -segments -of_objects [get_pins -of_objects $cell -filter {REF_PIN_NAME == Q}]]
    set sinks [get_pins -leaf -of_objects $nets -filter {DIRECTION == IN}]
    if {[llength $sinks] != 1 || [get_property REF_PIN_NAME $sinks] ne "D"} {
      error "SLR TX must directly drive exactly one FF D: $cell -> $sinks"
    }
    set receiver [get_cells -of_objects $sinks]
    if {![dict exists $receiver_set $receiver]} {error "SLR link bypasses RX bank: $cell -> $receiver"}
    dict set reached $receiver 1
    if {$placed} {
      fpt_require_placed_slr $cell $source_slr
      fpt_require_placed_slr $receiver $destination_slr
      set txbel [get_property BEL $cell]
      set rxbel [get_property BEL $receiver]
      if {[string match *TX_REG* $txbel] && [string match *RX_REG* $rxbel]} {incr laguna_pairs}
    }
  }
  if {[dict size $reached] != $expected} {error "SLR receive bank is not one-to-one"}
  puts $out "PAIR\t$source_slr\t$destination_slr\t$expected\t$laguna_pairs"
}

proc fpt_digit_registers {role} {
  if {$role ni {sourceTx middleRx middleTx destinationRx}} {error "unknown digit bank: $role"}
  set payload {}
  foreach cell [get_cells -hier -filter "NAME =~ */coefficientDigitLink/$role/outputPayload_payloadBoundary/value_reg* && REF_NAME =~ FD*"] {
    # DSP register push-out can inherit this prefix (value_reg[2524]_psdsp).
    # Only the original indexed payload FFs belong to the link. Keep exact
    # counts and connectivity checks below; do not waive extra TX fanout.
    if {[regexp {/value_reg\[[0-9]+\]$} $cell]} {lappend payload $cell}
  }
  return $payload
}
proc fpt_check_registered_links {report {kernel 1} {placed 0} {architecture paired_inverse}} {
  if {$architecture ni {paired_inverse coefficient_slr0_narrow}} {error "unknown link architecture: $architecture"}
  set out [open $report w]
  puts $out "kind\tsource_slr\tdestination_slr\tpayload_bits\tlaguna_tx_rx_pairs"
  set tx [get_cells -hier -filter {NAME =~ *boundary/tx_* && REF_NAME =~ FD* && NAME !~ */tx_next*}]
  set rx [get_cells -hier -filter {NAME =~ *boundary/rx_* && REF_NAME =~ FD* && NAME !~ */rx_next*}]
  fpt_check_link_pair $tx $rx 7680 SLR2 SLR1 $placed $out
  set banks [list $tx $rx]
  if {$kernel} {
    if {$architecture eq "coefficient_slr0_narrow"} {
      set digit_banks {}
      foreach role {sourceTx middleRx middleTx destinationRx} {
        set cells [fpt_digit_registers $role]
        lappend digit_banks $cells
        lappend banks $cells
        set controls [get_cells -hier -filter "NAME =~ */coefficientDigitLink/$role/outputControl_controlBoundary/value_reg* && REF_NAME =~ FD*"]
        if {[llength $controls] != 2} {error "digit start/valid FF count $role: [llength $controls] != 2"}
      }
      fpt_check_link_pair [lindex $digit_banks 0] [lindex $digit_banks 1] 2560 SLR0 SLR1 $placed $out
      fpt_check_link_pair [lindex $digit_banks 1] [lindex $digit_banks 2] 2560 SLR1 SLR1 $placed $out
      fpt_check_link_pair [lindex $digit_banks 2] [lindex $digit_banks 3] 2560 SLR1 SLR2 $placed $out
      foreach name {forwardInputBoundary inverseOutputSourceTx inverseOutputMiddleRx inverseOutputMiddleTx inverseOutputDestinationRx inverseOutputMiddleRelay inverseOutputDestinationRelay} {
        if {[llength [get_cells -hier -filter "NAME =~ */$name/* && REF_NAME =~ FD*"]] != 0} {
          error "obsolete wide transport bank remains: $name"
        }
      }
      set local_inverse [get_cells -hier -filter {NAME =~ */inverseOutputLocalBoundary/outputPayload_payloadBoundary/value_reg* && REF_NAME =~ FD*}]
      if {[llength $local_inverse] != 3840} {error "local inverse payload count [llength $local_inverse] != 3840"}
      if {$placed} {
        foreach cell $local_inverse {
          fpt_require_placed_slr $cell SLR0
          if {[string match *LAGUNA* [get_property LOC $cell]]} {error "local inverse payload unexpectedly uses Laguna: $cell"}
        }
        set cmux [get_cells -hier -filter {NAME =~ */controller/core/engine/blindRotate/blindRotate/cmux}]
        if {[llength $cmux] != 1} {error "missing placed narrow-link CMUX"}
        set wrapper [file dirname [file dirname $cmux]]
        set locals [get_cells -hier -filter "NAME =~ ${wrapper}/* && NAME !~ ${cmux}/* && IS_PRIMITIVE && REF_NAME != VCC && REF_NAME != GND"]
        foreach name {coefficients componentJoin inverseTags inverse} {
          set locals [concat $locals [get_cells -hier -filter "NAME =~ ${cmux}/$name/* && IS_PRIMITIVE && REF_NAME != VCC && REF_NAME != GND"]]
        }
        set locals [lsort -unique $locals]
        foreach cell $locals {fpt_require_placed_slr $cell SLR0}
        puts $out "LOCAL\tSLR0\tcoefficient_inverse_load_drain\t[llength $locals]"
      }
    } else {
      set inverse_banks {}
      foreach name {inverseOutputSourceTx inverseOutputMiddleRx inverseOutputMiddleTx inverseOutputDestinationRx} {
        set cells [get_cells -hier -filter "NAME =~ */$name/outputPayload_payloadBoundary/value_reg* && REF_NAME =~ FD*"]
        lappend inverse_banks $cells
        lappend banks $cells
      }
      fpt_check_link_pair [lindex $inverse_banks 0] [lindex $inverse_banks 1] 3840 SLR0 SLR1 $placed $out
      fpt_check_link_pair [lindex $inverse_banks 1] [lindex $inverse_banks 2] 3840 SLR1 SLR1 $placed $out
      fpt_check_link_pair [lindex $inverse_banks 2] [lindex $inverse_banks 3] 3840 SLR1 SLR2 $placed $out
    }
    set controls [fpt_locality_registers write]
    # 16 groups x (7 address + 1 load + 4 mask) bits at the U280 shape.
    if {[llength $controls] != 192} {error "local write-control FF count [llength $controls] != 192"}
    set selectors [fpt_locality_registers selector]
    if {[llength $selectors] != 96} {error "scratch selector FF count [llength $selectors] != 96"}
    set fields [fpt_locality_registers field]
    if {[llength $fields] != 9216} {error "scratch field FF count [llength $fields] != 9216"}
    foreach group [list $controls $selectors] {
      set max_loads 0
      foreach cell $group {
        set nets [get_nets -segments -of_objects [get_pins -of_objects $cell -filter {REF_PIN_NAME == Q}]]
        set loads [get_pins -leaf -of_objects $nets -filter {DIRECTION == IN}]
        set max_loads [expr {max($max_loads,[llength $loads])}]
      }
      puts $out "CONTROL\t[lindex $group 0]\t[llength $group]\t$max_loads"
    }
  }
  if {$placed} {
    foreach bank $banks {
      foreach cell $bank {
        set own [fpt_placed_slr $cell]
        set dn [get_nets -segments -of_objects [get_pins -of_objects $cell -filter {REF_PIN_NAME == D}]]
        set qn [get_nets -segments -of_objects [get_pins -of_objects $cell -filter {REF_PIN_NAME == Q}]]
        set sources [get_cells -of_objects [get_pins -leaf -of_objects $dn -filter {DIRECTION == OUT}]]
        set sinks [get_cells -of_objects [get_pins -leaf -of_objects $qn -filter {DIRECTION == IN}]]
        set d_cross 0; set q_cross 0
        foreach source $sources {
          # Constants have no physical origin and do not cross an SLR.
          if {[get_property REF_NAME $source] in {VCC GND}} {continue}
          if {[fpt_placed_slr $source] ne $own} {set d_cross 1}
        }
        foreach sink $sinks {if {[fpt_placed_slr $sink] ne $own} {set q_cross 1}}
        if {$d_cross && $q_cross} {error "both D and Q cross SLRs: $cell"}
      }
    }
  }
  close $out
  puts "REGISTERED_LINK_STRUCTURE_PASS kernel=$kernel placed=$placed"
  puts "REGISTERED_LINK_ARCHITECTURE $architecture"
}

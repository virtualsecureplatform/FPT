# Token-mode transform and three-stage EP row-control contracts.
# Source check_fpt_local_controls.tcl and check_fpt_registered_links.tcl first.
proc fpt_frame_reset_origin {point} {
  set name [get_property NAME $point]
  if {[get_property CLASS $point] eq "port"} {
    return [regexp {(^|/)(ap_rst_n|reset)$} $name]
  }
  return [string match */proc_sys_reset_* $name]
}
proc fpt_check_frame_tiles {out {expected 96}} {
  set tiles [get_cells -hier -filter {REF_NAME =~ *SGenCommutatorPermutation* && IS_PRIMITIVE == 0}]
  # An OOC single-tile test has no hierarchical cell for its top module.
  if {$expected == 1 && [llength $tiles] == 0} {set tiles [list ""]}
  if {[llength $tiles] != $expected} {error "expected $expected frame-token commutator tiles"}
  set earlyNets {}
  foreach tile $tiles {
    set prefix [expr {$tile eq "" ? "" : "$tile/"}]
    set leaves [get_cells -hier -filter "NAME =~ $prefix* && IS_PRIMITIVE"]
    if {[llength [filter $leaves {REF_NAME =~ RAM* || REF_NAME =~ URAM* || REF_NAME =~ DSP*}]]} {
      error "RAM/DSP in commutator $tile"
    }
    set controls [filter $leaves {REF_NAME =~ FD* && (NAME =~ *phase_reg* || NAME =~ *phase_pair1_reg || NAME =~ *child_select_delay_reg* || NAME =~ *local_start_reg)}]
    if {[llength $controls] != 6} {error "missing frame controls in $tile"}
    foreach cell $controls {
      if {![get_property DONT_TOUCH $cell]} {error "unpreserved frame control $cell"}
      set loads [fpt_control_loads $cell Q]
      if {[llength $loads] > 256} {error "excessive local frame fanout: $cell loads=[llength $loads]"}
      foreach pin $loads {
        if {![string match "$prefix*" $pin]} {error "frame control escapes $tile: $pin"}
      }
      foreach point [all_fanin -flat -startpoints_only -to [get_pins -of_objects $cell -filter {DIRECTION == IN && REF_PIN_NAME != C}]] {
        if {[fpt_frame_reset_origin $point]} {error "global reset still reaches $cell through $point"}
      }
      puts $out "FRAME\t$cell\t[llength $loads]"
    }
    lappend earlyNets {*}[get_nets -segments -of_objects [get_pins ${prefix}local_start_reg/D]]
    # Early initialization may reach only the admission and phase FFs, never
    # the datapath. Inspect endpoints through LUTs as well as direct FF loads.
    if {$tile eq ""} {
      set earlySource [get_ports start_early]
    } else {
      set earlySource [get_pins ${prefix}start_early]
    }
    if {[llength $earlySource] != 1} {error "missing early token input: $tile"}
    set earlyEndpoints [all_fanout -flat -endpoints_only -from $earlySource]
    if {[llength $earlyEndpoints] == 0} {error "missing early token endpoints: $tile"}
    foreach pin $earlyEndpoints {
      if {[string match "$prefix*" $pin] &&
          ![regexp {(^|/)(local_start_reg|phase_pair1_reg|phase_reg\[[0-9]+\])/(D|R|S|CE)$} $pin]} {
        error "early initialization reaches payload: $pin"
      }
    }
  }
  foreach net [lsort -unique $earlyNets] {
    # Tcl list operations discard Vivado's collection object types. Resolve
    # the resulting names back to net objects before using -of_objects.
    set loads [get_pins -leaf -of_objects [get_nets $net] -filter {DIRECTION == IN}]
    puts $out "EARLY_NET\t$net\t[llength $loads]"
    if {[llength $loads] > 256} {error "early token fanout exceeds 256: $net"}
  }
  # Keep one admission FF per tile; phase initialization is additionally legal.
  set tileLoads 0
  foreach pin [get_pins -leaf -of_objects [get_nets [lsort -unique $earlyNets]] -filter {DIRECTION == IN}] {
    foreach tile $tiles {
      set prefix [expr {$tile eq "" ? "" : "$tile/"}]
      if {[string match "$prefix*" $pin]} {
        if {$pin eq "${prefix}local_start_reg/D"} {incr tileLoads}
        break
      }
    }
  }
  if {$tileLoads != $expected} {error "frame admission count $tileLoads != $expected"}
}
proc fpt_check_inverse_frame_reset {out {kernel 1}} {
  if {$kernel} {
    # IP packaging prefixes module references with the packaged IP name.
    # Accept only the original name or that suffix, and still require uniqueness.
    set inverse {}
    foreach cell [get_cells -hier -filter {REF_NAME =~ *FptSGenInverse && IS_PRIMITIVE == 0}] {
      if {[regexp {(^|_)FptSGenInverse$} [get_property REF_NAME $cell]]} {
        lappend inverse [get_property NAME $cell]
      }
    }
    if {[llength $inverse] != 1} {error "missing unique inverse transform"}
    set inverseName [lindex $inverse 0]
    set inverseCell [get_cells -quiet $inverseName]
    if {[llength $inverseCell] != 1 ||
        ![regexp {(^|_)FptSGenInverse$} [get_property REF_NAME $inverseCell]]} {
      error "inverse cell resolution mismatch: $inverseName"
    }
    set prefix "$inverseName/"
    # Rebuilt hierarchy rewrites ports (the original reset port disappears).
    # Find the global reset source through the four ordinal FF reset pins,
    # then audit its entire fanout inside the inverse hierarchy.
    set ordinalFFs [get_cells -hier -filter "NAME =~ ${prefix}frame_ordinal_reg* && REF_NAME =~ FD*"]
    if {[llength $ordinalFFs] != 4} {error "expected four inverse ordinal FFs"}
    set resetNames {}
    foreach point [all_fanin -flat -startpoints_only -to [get_pins -of_objects $ordinalFFs -filter {REF_PIN_NAME == R || REF_PIN_NAME == CLR}]] {
      if {[fpt_frame_reset_origin $point]} {
        if {[get_property CLASS $point] eq "port"} {
          lappend resetNames [get_property NAME $point]
        } else {
          # Timing startpoints may be reported as the launching FF's C pin.
          foreach q [get_pins -of_objects [get_cells -of_objects $point] -filter {DIRECTION == OUT}] {
            lappend resetNames [get_property NAME $q]
          }
        }
      }
    }
    set resetPins {}
    foreach name [lsort -unique $resetNames] {
      set object [get_ports -quiet $name]
      if {[llength $object] == 0} {set object [get_pins -quiet $name]}
      lappend resetPins {*}$object
    }
  } else {
    set prefix ""
    set resetPins [get_ports reset]
  }
  if {[llength $resetPins] == 0} {error "missing inverse global reset source"}
  set ordinalCells {}
  foreach pin [all_fanout -flat -endpoints_only -from $resetPins] {
    if {![string match "$prefix*" $pin]} {continue}
    set cell [get_cells -of_objects $pin]
    if {![regexp {(^|/)frame_ordinal_reg\[[0-9]+\]$} $cell]} {error "inverse global reset reaches distributed state: $pin"}
    lappend ordinalCells [get_property NAME $cell]
  }
  if {[llength [lsort -unique $ordinalCells]] != 4} {error "expected four ingress ordinal reset FFs"}
  puts $out "INVERSE_RESET\t[lsort -unique $ordinalCells]\t4"
}
proc fpt_check_row_controls {out {placed 0}} {
  # Each region feeds four tiles: two first-row copies plus one last-row bit
  # per tile. First-row regional fanout is therefore eight, not four.
  foreach {role expected nextRole bound} {Root 8 Region 4 Region 24 Leaf 8 Leaf 132 {} 512} {
    set cells {}
    foreach cell [get_cells -hier -filter "NAME =~ *row${role}_*/value_reg* && REF_NAME =~ FD*"] {
      if {[regexp {/value_reg\[[0-9]+\]$} $cell]} {lappend cells $cell}
    }
    if {[llength $cells] != $expected} {error "row $role FF count [llength $cells] != $expected"}
    foreach cell $cells {
      if {![get_property DONT_TOUCH $cell]} {error "unpreserved row control $cell"}
      set loads [fpt_control_loads $cell Q]
      if {[llength $loads] == 0 || [llength $loads] > $bound} {error "row $role fanout: $cell -> [llength $loads]"}
      if {$nextRole ne ""} {
        foreach pin $loads {
          if {![string match "*row${nextRole}_*/value_reg*/D" $pin]} {error "row tree stage bypass: $cell -> $pin"}
        }
      }
      if {$placed} {fpt_require_placed_slr $cell SLR1}
      puts $out "ROW_$role\t$cell\t[llength $loads]"
    }
  }
}
proc fpt_check_frame_controls {report {placed 0}} {
  set out [open $report w]
  puts $out "kind\tcell\tloads"
  fpt_check_frame_tiles $out
  fpt_check_inverse_frame_reset $out
  fpt_check_row_controls $out $placed
  close $out
  puts "FRAME_STRUCTURE_PASS tiles=96 ordinal_bits=4 row_roots=4 row_regions=12 row_leaves=44 placed=$placed"
}

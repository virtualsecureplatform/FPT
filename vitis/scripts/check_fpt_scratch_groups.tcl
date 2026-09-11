# Requires check_fpt_local_controls.tcl. Checks the opt-in wide-read groups.
proc fpt_scratch_group_contains {group object} {
  return [expr {[string first "${group}/" $object] == 0}]
}
proc fpt_scratch_input_loads {names} {
  set fields {}
  set other 0
  foreach name [lsort -unique $names] {
    if {[regexp {/(readAddressesGroup|writeAddressGroup|writeEnableGroup|primeCaptureGroup|readComponentGroup|readHalvesGroup)_reg(\[[0-9]+\])?/D$} $name -> role bit]} {
      dict incr fields "$role$bit"
    } else {
      if {[regexp {/(readAddressCut|writeAddressCut|writeEnableCut)_reg} $name]} {
        error "scratch input bypasses group stage: $name"
      }
      incr other
    }
  }
  foreach {field count} $fields {
    if {$count > 8} {error "scratch input field $field reaches $count groups (limit 8)"}
  }
  # Four aliased read-port fields may each reach eight groups. Reserve at
  # most 32 further loads for shared address computation/metadata, not banks.
  set total [llength [lsort -unique $names]]
  if {$total > 64 || $other > 32} {error "scratch shared input fanout total=$total other=$other exceeds 64/32"}
  return [list $total $other $fields]
}
proc fpt_check_scratch_groups {out {placed 0}} {
  set groups [get_cells -hier -filter {REF_NAME =~ *FptCoefficientScratchLaneGroup* && IS_PRIMITIVE == 0}]
  if {[llength $groups] != 8} {error "expected eight scratch lane groups"}
  foreach group $groups {
    set name [get_property NAME $group]
    set controls {}
    foreach c [get_cells -hier -filter {NAME =~ *Group_reg* && REF_NAME =~ FD*}] {
      if {[fpt_scratch_group_contains $name [get_property NAME $c]]} {
        lappend controls [get_property NAME $c]
      }
    }
    if {[llength $controls] == 0} {error "no group controls: $name"}
    foreach controlName $controls {
      if {[string match *writeDataGroup_reg* $controlName]} {continue}
      set c [get_cells -quiet $controlName]
      if {[llength $c] != 1} {error "missing scratch group register: $controlName"}
      if {![get_property DONT_TOUCH $c]} {error "unpreserved scratch group control: $c"}
      set inputLoads [fpt_control_loads $c D]
      set d [llength $inputLoads]
      set q [fpt_control_loads $c Q]
      # Wide readout does not use these selected-readout controls. The test
      # harness also samples their input for output normalization; that load
      # is not a scratch control broadcast. Do not exempt active selectors.
      if {[llength $q] == 0 && [regexp {(readComponentGroup|readHalvesGroup)_reg} $controlName]} {
        puts $out "SCRATCH_UNUSED\t$c\t$d\t0"
        continue
      }
      set inputSummary [fpt_scratch_input_loads [get_property NAME $inputLoads]]
      puts $out "SCRATCH_INPUT\t$controlName\t$inputSummary"
      if {[llength $q] > 16} {error "scratch control fanout: $c D=$d Q=[llength $q]"}
      foreach pin $q {
        if {![fpt_scratch_group_contains $name [get_property NAME $pin]]} {error "scratch group control escapes: $pin"}
      }
      if {$placed} {fpt_require_placed_slr $c SLR0}
      puts $out "SCRATCH_GROUP\t$c\t$d\t[llength $q]"
    }
  }
  puts "SCRATCH_GROUP_STRUCTURE_PASS groups=8 placed=$placed"
}

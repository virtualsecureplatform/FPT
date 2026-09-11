proc fpt_control_loads {cell pin} {
  set nets [get_nets -segments -of_objects [get_pins -of_objects $cell -filter "REF_PIN_NAME == $pin"]]
  return [get_pins -leaf -of_objects $nets -filter {DIRECTION == IN}]
}
proc fpt_check_ifft_controls {out expected} {
  set islands [get_cells -quiet -hier -filter {REF_NAME =~ *SGenRegisteredMuxIsland* && IS_PRIMITIVE == 0}]
  if {[llength $islands]} {
    if {![info exists ::env(FPT_SGEN_INVERSE)]} {error "missing inverse manifest path"}
    set file [open $::env(FPT_SGEN_INVERSE) r]
    set rtl [read $file]
    close $file
    set regionalExpected [regexp -all -line {^// mux_predecessor } $rtl]
    set regional [get_cells -quiet -hier -filter {REF_NAME =~ *SGenMuxIslandPredecessor* && IS_PRIMITIVE == 0}]
    if {[llength $regional] != $regionalExpected} {error "IFFT predecessor count differs from manifest"}
    foreach region $regional {
      set regionName [get_property NAME $region]
      set ff [get_cells "$regionName/value_reg"]
      if {[llength $ff] != 1 || ![get_property DONT_TOUCH $ff]} {error "unpreserved IFFT predecessor: $regionName"}
      set q [fpt_control_loads $ff Q]
      set d [fpt_control_loads $ff D]
      if {[llength $q] == 0 || [llength $q] > 32 || [llength $d] > 256} {
        error "IFFT regional fanout: $regionName Q=[llength $q] D=[llength $d]"
      }
      foreach pin $q {
        if {![string match */mux_island_*/select_local_reg/D $pin] && ![string match mux_island_*/select_local_reg/D $pin]} {
          error "IFFT predecessor bypass: $pin"
        }
      }
      puts $out "IFFT_PREDECESSOR\t$regionName\t[llength $d]\t[llength $q]"
    }
    set manifests [regexp -all -inline -line {^// mux_island name=\S+ source=\S+ cycles=\d+ bit=-?\d+ bits=\d+ muxes=\S+ captures=\S+} $rtl]
    if {[llength $manifests] != [llength $islands]} {error "IFFT island count differs from manifest"}
    set expected [regexp -all -line {^// mux_control_copy } $rtl]
    foreach island $islands {
      set name [get_property NAME $island]
      set short [file tail $name]
      set width 0
      foreach entry $manifests {
        if {[regexp "name=$short source=.* bits=(\\d+) " $entry -> width]} {break}
      }
      if {$width < 1 || $width > 120} {error "invalid IFFT island width: $name"}
      set selector [get_cells -quiet "$name/select_local_reg"]
      if {[llength $selector] != 1 || ![get_property DONT_TOUCH $selector]} {error "missing preserved island selector: $name"}
      set q [fpt_control_loads $selector Q]
      set d [fpt_control_loads $selector D]
      if {[llength $q] > 128 || [llength $q] == 0 || [llength $d] > 256} {error "island fanout: $name Q=[llength $q] D=[llength $d]"}
      foreach pin $q {
        if {![string match "$name/*" $pin]} {error "island selector escapes: $pin"}
      }
      set captures [get_cells -hier -filter "NAME =~ $name/captured_reg* && REF_NAME =~ FD*"]
      if {[llength $captures] != $width} {error "island capture count: $name actual=[llength $captures] expected=$width"}
      foreach pin [all_fanout -flat -endpoints_only -from [get_pins -of_objects $selector -filter {REF_PIN_NAME == Q}]] {
        if {![string match "$name/captured_reg*/D" $pin]} {error "island control bypass: $pin"}
      }
      puts $out "IFFT_ISLAND\t$name\t[llength $d]\t[llength $q]\t$width"
    }
    puts "IFFT_ISLAND_STRUCTURE_PASS islands=[llength $islands] legacy_copies=$expected"
  }
  set copies [get_cells -hier -regexp {.*_mux_control_[0-9]+_reg}]
  if {[llength $copies] != $expected} {error "IFFT control copies [llength $copies] != $expected"}
  foreach cell $copies {
    if {![get_property IS_SEQUENTIAL $cell] || ![get_property DONT_TOUCH $cell]} {
      error "IFFT control is not a preserved FF: $cell"
    }
    set q [llength [fpt_control_loads $cell Q]]
    set d [llength [fpt_control_loads $cell D]]
    puts $out "IFFT\t$cell\t$d\t$q"
    if {$q == 0 || $q > 256} {error "IFFT selector fanout is not bounded: $cell Q=$q"}
  }
}
proc fpt_check_final_bank_controls {out bank distributed} {
  set prefix [expr {$bank eq "" ? "" : "$bank/"}]
  set roles {writeEnable 1 writeAddress 2 readEnable 1 readAddress 2}
  if {$distributed} {lappend roles readAddressCut 2}
  foreach {role expected} $roles {
    # Include vector bit names, but not readAddressCut when matching readAddress.
    set cells [get_cells -hier -filter "NAME =~ ${prefix}${role}_reg* && REF_NAME =~ FD*"]
    if {[llength $cells] != $expected} {error "local control $prefix$role count [llength $cells] != $expected"}
    foreach cell $cells {
      if {![get_property DONT_TOUCH $cell]} {error "local control lost preservation: $cell"}
      set loads [fpt_control_loads $cell Q]
      set q [llength $loads]
      puts $out "EP\t$cell\t[llength [fpt_control_loads $cell D]]\t$q"
      if {$q == 0 || $q > 512} {error "EP local control fanout is not bounded: $cell Q=$q"}
      foreach sink [get_cells -of_objects $loads] {
        if {$prefix ne "" && ![string match ${prefix}* $sink]} {error "bank control escaped its bank: $cell -> $sink"}
      }
    }
  }
}
proc fpt_final_local_banks {} {
  # Chisel prepends the enclosing val to explicit suggested names.
  return [get_cells -hier -filter {NAME =~ */*finalBankTiles_* && REF_NAME =~ *FptFinalAccumulatorLocal* && IS_PRIMITIVE == 0}]
}
proc fpt_check_local_controls {report expected {placed 0}} {
  set out [open $report w]
  puts $out "kind\tregister\tpredecessor_loads\tlocal_loads"
  fpt_check_ifft_controls $out $expected
  set banks [fpt_final_local_banks]
  if {[llength $banks] != 44} {error "EP local bank count [llength $banks] != 44"}
  set ultra 0
  set distributed 0
  set urams {}
  foreach bank $banks {
    set is_distributed [string match *Distributed* [get_property REF_NAME $bank]]
    if {$is_distributed} {incr distributed} else {incr ultra}
    fpt_check_final_bank_controls $out $bank $is_distributed
    lappend urams {*}[get_cells -hier -filter "NAME =~ $bank/* && REF_NAME =~ URAM*"]
    if {$placed} {
      foreach cell [get_cells -hier -filter "NAME =~ $bank/* && IS_PRIMITIVE && REF_NAME != VCC && REF_NAME != GND"] {
        fpt_require_placed_slr $cell SLR1
      }
    }
  }
  if {$ultra != 22 || $distributed != 22} {error "EP storage split changed: ultra=$ultra distributed=$distributed"}
  if {[llength $urams] != 108} {error "EP UltraRAM allocation [llength $urams] != 108"}
  close $out
  puts "LOCAL_CONTROL_STRUCTURE_PASS ifft=$expected banks=44 uram=108 placed=$placed"
}

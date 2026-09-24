# Validate the timing cuts independently of the snapshot's RAM allocation.
proc fpt_buffered_control_source {root name ref} {
  # Only registered local authorization can reach stream/packer controls.
  # Raw DMA status, global chain faults, and arbitrary data are not allowed.
  if {![string match FD* $ref]} {return 0}
  foreach base [list ${root}/outputActive_reg ${root}/commandSent_reg] {
    if {$name eq $base || [string match ${base}_rep* $name]} {return 1}
  }
  if {[string first ${root}/ $name] != 0} {return 0}
  set tail [string range $name [string length ${root}/] end]
  return [regexp {^stop(Stream|Accept|Retire|Records|Record[01])[^/]*/value_reg(\[0\])?(_rep.*)?$} $tail]
}

proc fpt_buffered_interface_control {name} {
  # Rebuilt hierarchy retains io_* interfaces, sometimes with a Scala-val
  # prefix. It also exports unrelated expressions under names of consuming
  # registers (e.g. sig_s_ready_out_reg is the final-beat handshake feeding
  # timestamps/lastSeen). Those expressions are not slice ready/valid outputs.
  return [regexp {(^|_)io_[A-Za-z0-9_]*(ready|valid)$} $name]
}

proc fpt_check_buffered_output_island {report {placed 0}} {
  if {![info exists ::env(FPT_U280_BUFFERED_OUTPUT)] || $::env(FPT_U280_BUFFERED_OUTPUT) ne "1"} {return}
  set roots [get_cells -hier -quiet -filter {NAME =~ */sequencer/outputRetirement && IS_PRIMITIVE == 0}]
  if {[llength $roots] != 1} {error "buffered output island count [llength $roots] != 1"}
  set root [get_property NAME [lindex $roots 0]]
  set out [open $report w]
  foreach {role width} {inputSlice 132 outputSlice 145} {
    set slice [get_cells -quiet ${root}/${role}]
    if {[llength $slice] != 1} {error "missing output slice $role"}
    set payload [get_cells -hier -quiet -filter "NAME =~ ${root}/${role}/slot*_reg* && REF_NAME =~ FD*"]
    # Count logical data/TLAST bits, not physical replicas. Constant count/keep
    # bits may disappear; physical optimization may legally duplicate FFs.
    set n [llength $payload]
    set bits [dict create]
    foreach cell $payload {
      if {[regexp {/(slot[01])_reg\[([0-9]+)\](_rep.*)?$} [get_property NAME $cell] -> slot bit]} {
        dict set bits $slot $bit 1
      }
    }
    foreach slot {slot0 slot1} {
      if {![dict exists $bits $slot 0]} {error "$role/$slot missing TLAST register"}
      for {set bit [expr {$width-128}]} {$bit < $width} {incr bit} {
        if {![dict exists $bits $slot $bit]} {error "$role/$slot missing payload bit $bit"}
      }
    }
    set pins [get_pins -of_objects $slice -filter {DIRECTION == OUT}]
    if {![llength $pins]} {error "missing slice output pins: $role"}
    # Rebuilt hierarchy also exports unrelated relocated logic. Inspect the
    # named payload interface separately from enables. Pins named slot*_reg
    # are synthesis exports, not necessarily outputs of those registers.
    set data {}; set controls {}
    foreach pin $pins {
      set name [get_property REF_PIN_NAME $pin]
      if {[regexp {bits|^io_outputKeep|^io_outputLast$} $name]} {
        lappend data $pin
      } elseif {[fpt_buffered_interface_control $name]} {lappend controls $pin}
    }
    if {![llength $data]} {error "missing slice payload pins: $role"}
    if {![llength $controls]} {error "missing slice ready/valid interface pins: $role"}
    set starts [all_fanin -flat -startpoints_only -only_cells -to $data]
    if {![llength $starts]} {error "missing slice output drivers: $role"}
    foreach cell $starts {
      set name [get_property NAME $cell]
      set ref [get_property REF_NAME $cell]
      if {$ref in {VCC GND}} {continue}
      if {![string match FD* $ref] || [string first ${root}/${role}/ $name] != 0} {
        error "slice bypass/nonlocal combinational driver: $role $name $ref"
      }
    }
    if {[llength $controls]} {
      foreach cell [all_fanin -flat -startpoints_only -only_cells -to $controls] {
        set name [get_property NAME $cell]; set ref [get_property REF_NAME $cell]
        if {$ref in {VCC GND}} {continue}
        if {[string match FD* $ref] && [string first ${root}/${role}/ $name] == 0} {continue}
        if {![fpt_buffered_control_source $root $name $ref]} {
          error "unexpected slice control bypass: $role $name $ref"
        }
        puts $out "$role authorized_control_source=$name"
      }
    }
    puts $out "$role payload_ff=$n registered_payload=1 controls_checked=[llength $controls]"
  }
  # Rebuilt hierarchy can absorb part of the packer's mux into a slice and
  # rename interface bits. Independently check the actual consumer FF inputs:
  # no upstream sample-extraction/data register may bypass the input storage.
  set packer [get_cells -hier -quiet -filter "NAME =~ ${root}/packer/* && REF_NAME =~ FD*"]
  if {![llength $packer]} {error "missing packer registers"}
  set sinks [get_pins -of_objects $packer -filter {REF_PIN_NAME == D}]
  foreach cell [all_fanin -flat -startpoints_only -only_cells -to $sinks] {
    set name [get_property NAME $cell]; set ref [get_property REF_NAME $cell]
    if {$ref in {GND VCC}} {continue}
    set local 0
    foreach role {inputSlice outputSlice packer} {
      if {[string match FD* $ref] && [string first ${root}/${role}/ $name] == 0} {set local 1}
    }
    if {!$local && ![fpt_buffered_control_source $root $name $ref]} {
      error "packer bypasses input storage: $name $ref"
    }
  }
  puts $out "packer_registered_input=1"
  set tiles [get_cells -hier -quiet -filter {NAME =~ *drainResponses*/storageTile_* && IS_PRIMITIVE == 0}]
  if {[llength $tiles] != 9} {error "expected nine drain storage tiles, found [llength $tiles]"}
  foreach tile $tiles {
    if {![regexp {(^|_)FptCoefficientQueueTile_[0-9]+_2_NoFlow(_[0-9]+)*$} [get_property REF_NAME $tile]]} {
      error "drain tile is not the validated non-flow implementation: $tile"
    }
  }
  if {$placed} {
    set leaves [get_cells -hier -quiet -filter "NAME =~ ${root}/* && IS_PRIMITIVE"]
    if {![llength $leaves]} {error "empty output island"}
    foreach cell $leaves {
      if {[get_property REF_NAME $cell] ni {VCC GND}} {fpt_require_placed_slr $cell SLR0}
    }
  }
  puts $out "drain_no_flow_tiles=9 placed=$placed"
  close $out
  puts "BUFFERED_OUTPUT_ISLAND_PASS drain_no_flow_tiles=9 placed=$placed"
}

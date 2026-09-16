# Source after registered-links and inverse-twiddle helpers.
proc fpt_lane_members {objects prefix} {
  set result {}
  foreach object $objects {
    if {[string first "$prefix/" [get_property NAME $object]] == 0} {lappend result $object}
  }
  return $result
}
# Constant D nets are shared tie-offs, not control broadcasts.
proc fpt_lane_input_limit {cell limit} {
  set pins [get_pins -of_objects $cell -filter {REF_PIN_NAME == D}]
  set nets [get_nets -segments -of_objects $pins]
  set drivers [get_cells -of_objects [get_pins -leaf -of_objects $nets -filter {DIRECTION == OUT}]]
  if {[llength $drivers] == 1 && [get_property REF_NAME $drivers] in {GND VCC}} {return 0}
  return [fpt_it_limit $cell D $limit]
}
# The small rolling state holds through tile-local CEs, avoiding thousands of
# feedback muxes. Unlike key payload cuts, these CEs need not be constant.
proc fpt_lane_rolling_payload {cell tile} {
  global fpt_lane_payload_cache
  foreach pin [get_pins -quiet -of_objects $cell -filter {REF_PIN_NAME == R || REF_PIN_NAME == CLR || REF_PIN_NAME == CE}] {
    set key [list $tile [get_property REF_PIN_NAME $pin] [get_property NAME [get_nets -of_objects $pin]]]
    if {[dict exists $fpt_lane_payload_cache $key]} {continue}
    set nets [get_nets -segments -of_objects $pin]
    set outputPins [get_pins -leaf -of_objects $nets -filter {DIRECTION == OUT}]
    set drivers [get_cells -of_objects $outputPins]
    if {[llength $drivers] != 1} {error "ambiguous rolling payload control: $pin"}
    set driver [lindex $drivers 0]
    set kind [get_property REF_NAME $driver]
    if {[get_property REF_PIN_NAME $pin] ne "CE"} {
      if {$kind ne "GND"} {error "reset extracted onto rolling payload: $pin"}
    } elseif {$kind ne "VCC"} {
      if {[string first "$tile/" [get_property NAME $driver]] != 0} {error "nonlocal rolling enable: $pin"}
      set loads [get_pins -leaf -of_objects $nets -filter {DIRECTION == IN}]
      if {[llength $loads] > 256} {error "wide rolling enable: $pin [llength $loads]"}
      foreach load $loads {
        if {[string first "$tile/" [get_property NAME $load]] != 0} {error "rolling enable escapes tile: $pin -> $load"}
      }
    }
    dict set fpt_lane_payload_cache $key 1
  }
}
proc fpt_check_lane_local {report {placed 0} {unit both}} {
  global fpt_it_constant_cache fpt_lane_payload_cache
  set fpt_it_constant_cache [dict create]
  set fpt_lane_payload_cache [dict create]
  set out [open $report w]
  if {$unit in {both coefficient}} {
    set tiles [get_cells -hier -filter {REF_NAME =~ *FptCoefficientRollingHeadTile_4_24_2* && IS_PRIMITIVE == 0}]
    if {[llength $tiles] != 16} {error "expected 16 coefficient rolling-head tiles, got [llength $tiles]"}
    set commands [get_cells -hier -filter {NAME =~ *rollingHeadTile*/command_reg* && REF_NAME =~ FD*}]
    set payload [get_cells -hier -filter {(NAME =~ *rollingHeadTile*/headsReg_reg* || NAME =~ *rollingHeadTile*/deferredTail_reg*) && REF_NAME =~ FD*}]
    foreach tile $tiles {
      set name [get_property NAME $tile]
      set control [fpt_lane_members $commands $name]
      set data [fpt_lane_members $payload $name]
      if {[llength $control] != 4 || [llength $data] != 480} {
        error "rolling-head tile shape: $name commands=[llength $control] payload=[llength $data]"
      }
      foreach cell $control {
        if {![get_property DONT_TOUCH $cell]} {error "merged rolling command: $cell"}
        fpt_lane_input_limit $cell 64
        set q [fpt_it_limit $cell Q 256]
        foreach pin [fpt_it_loads $cell Q] {
          if {[string first "$name/" [get_property NAME $pin]] != 0} {error "rolling command escapes tile: $cell -> $pin"}
        }
        puts $out "ROLLING_CONTROL\t$cell\t$q"
      }
      foreach cell $data {fpt_lane_rolling_payload $cell $name}
      if {$placed} {foreach cell [concat $control $data] {fpt_require_placed_slr $cell SLR0}}
      puts $out "ROLLING_TILE\t$name\t[llength $control]\t[llength $data]"
    }
    set selectors [get_cells -hier -filter {(NAME =~ *scratchSelector*/*value_reg* || NAME =~ *scratchHeadChoice*/*value_reg* || NAME =~ *scratchTailWriteHalf*/*value_reg*) && REF_NAME =~ FD*}]
    # Six source selectors x 16 groups x two bits, plus 16 head choices and 16 independent tail-write selectors.
    if {[llength $selectors] != 224} {error "four-lane selector count: [llength $selectors] != 224"}
    foreach cell $selectors {
      if {![get_property DONT_TOUCH $cell]} {error "merged scratch selector: $cell"}
      set q [fpt_it_limit $cell Q 256]
      puts $out "SCRATCH_SELECTOR\t$cell\t$q"
      if {$placed} {fpt_require_placed_slr $cell SLR0}
    }
    puts "COEFFICIENT_LANE_STRUCTURE_PASS tiles=16 selectors=224 placed=$placed"
  }
  if {$unit in {both key}} {
    set tiles [get_cells -hier -filter {REF_NAME =~ *FptKeyWriteTile_32_8_216* && IS_PRIMITIVE == 0}]
    if {[llength $tiles] != 8} {error "expected eight key-write tiles, got [llength $tiles]"}
    set payload [get_cells -hier -filter {NAME =~ *keyWriteTile*/*writePayload/value_reg* && REF_NAME =~ FD*}]
    set controls [get_cells -hier -filter {NAME =~ *keyWriteTile*/*writeControl/value_reg* && REF_NAME =~ FD*}]
    set memories [get_cells -hier -filter {NAME =~ *keyWriteTile*/* && REF_NAME =~ RAMB*}]
    if {[llength $memories] != 192} {error "key BRAM allocation: [llength $memories] != 192"}
    foreach cell $memories {
      if {[get_property REF_NAME $cell] ne "RAMB36E2"} {error "unexpected key RAM primitive: $cell"}
    }
    foreach tile $tiles {
      set name [get_property NAME $tile]
      set data [fpt_lane_members $payload $name]
      set control [fpt_lane_members $controls $name]
      set ram [fpt_lane_members $memories $name]
      if {[llength $data] != 216 || [llength $control] != 13 || [llength $ram] != 24} {
        error "key tile shape: $name data=[llength $data] control=[llength $control] ram=[llength $ram]"
      }
      foreach cell $data {
        if {![get_property DONT_TOUCH $cell]} {error "key payload cut removed: $cell"}
        fpt_it_always_clocked $cell
        fpt_lane_input_limit $cell 2
        fpt_it_limit $cell Q 16
      }
      foreach cell $control {
        if {![get_property DONT_TOUCH $cell]} {error "key control cut removed: $cell"}
        fpt_lane_input_limit $cell 64
        fpt_it_limit $cell Q 256
      }
      set pins [get_pins -of_objects $ram -filter {REF_PIN_NAME =~ DIN* && DIRECTION == IN}]
      set nets [get_nets -segments -of_objects $pins]
      set drivers [get_cells -of_objects [get_pins -leaf -of_objects $nets -filter {DIRECTION == OUT}]]
      foreach driver $drivers {
        set kind [get_property REF_NAME $driver]
        set driverName [get_property NAME $driver]
        if {$kind ni {GND VCC} && !([string match FD* $kind] && [string first "$name/writePayload/" $driverName] == 0)} {
          error "key data bypasses local commit FF: $driverName -> $name"
        }
      }
      puts $out "KEY_TILE\t$name\t[llength $data]\t[llength $control]\t[llength $ram]"
      if {$placed} {
        set slrs {}
        foreach cell [concat $data $ram] {lappend slrs [fpt_placed_slr $cell]}
        puts $out "KEY_TILE_SLRS\t$name\t[lsort -unique $slrs]"
      }
    }
    puts "KEY_WRITE_STRUCTURE_PASS tiles=8 payload=1728 bram=192 placed=$placed"
  }
  close $out
}

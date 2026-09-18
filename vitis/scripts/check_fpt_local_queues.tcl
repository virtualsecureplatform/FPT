# Structural gate: local queue state may not merge or drive another tile.
proc fpt_local_queue_tile_matches {name queue} {
  # Chisel's enclosing val can prefix the factory's suggested instance name.
  # Match an exact queue parent, never a similarly named or nested descendant.
  return [regexp [format {(^|/)(%s|%s_%s)/storageTile_[0-9]+$} $queue $queue $queue] $name]
}
proc fpt_local_queue_state_owner {name queue} {
  if {![regexp {^(.*)/((enqPointer|deqPointer)_reg(\[[0-9]+\])?|maybeFull_reg)$} $name -> tile]} {return {}}
  if {![fpt_local_queue_tile_matches $tile $queue]} {return {}}
  return $tile
}
proc fpt_local_queue_tile_width {tile queue} {
  if {![fpt_local_queue_tile_matches $tile $queue]} {error "invalid queue tile: $tile"}
  regexp {storageTile_([0-9]+)$} $tile -> index
  switch $queue {
    updateQueue {set total 7685}
    drainResponses {set total 4097}
    default {error "unknown queue: $queue"}
  }
  set width [expr {min(512,$total-512*$index)}]
  if {$width <= 0} {error "queue tile index out of range: $tile"}
  return $width
}
proc fpt_local_queue_ram_pin_limit {width} {
  if {![string is integer -strict $width] || $width < 1 || $width > 512} {error "invalid queue tile width: $width"}
  # A RAM32M16 group provides 14 independently read payload bits, but its
  # write address drives 14 RAMD32 plus 2 RAMS32 leaf pins. Thus a full
  # 512-bit tile has ceil(512/14)*16 = 592 physical address loads, not 512.
  return [expr {(($width+13)/14)*16}]
}
proc fpt_local_queue_check_fanout {width ramLoads logicLoads} {
  if {$ramLoads < 0 || $logicLoads < 0 || $ramLoads > [fpt_local_queue_ram_pin_limit $width] || $logicLoads > 64} {
    error "queue fanout exceeds tile budget: width=$width RAM=$ramLoads logic=$logicLoads"
  }
}
proc fpt_check_local_queues {report} {
  set f [open $report w]
  # Primitive names survive rebuilt/flattened hierarchy; module REF_NAME is
  # neither required nor stable after platform IP uniquification.
  set candidates [get_cells -hier -filter {(NAME =~ *Pointer_reg* || NAME =~ *maybeFull_reg*) && REF_NAME =~ FD*}]
  foreach {queue expected depth} {updateQueue 16 8 drainResponses 9 2} {
    set groups [dict create]
    foreach cell $candidates {
      set tile [fpt_local_queue_state_owner [get_property NAME $cell] $queue]
      if {$tile ne {}} {dict lappend groups $tile $cell}
    }
    set tiles [dict keys $groups]
    if {[llength $tiles] != $expected} {error "$queue expected $expected storage tiles, got [llength $tiles]"}
    foreach tile $tiles {
      set width [fpt_local_queue_tile_width $tile $queue]
      set state [dict get $groups $tile]
      set count [expr {$depth == 8 ? 7 : 3}]
      if {[llength $state] != $count} {error "queue tile state lost: $tile [llength $state] != $count"}
      set max_fanout 0
      set max_ram 0; set max_logic 0
      foreach c $state {
        if {![get_property DONT_TOUCH $c]} {error "queue control preservation lost: $c"}
        set nets [get_nets -segments -of_objects [get_pins -of_objects $c -filter {REF_PIN_NAME == Q}]]
        set loads [get_pins -leaf -of_objects $nets -filter {DIRECTION == IN}]
        set max_fanout [expr {max($max_fanout,[llength $loads])}]
        set ramLoads 0; set logicLoads 0
        foreach pin $loads {
          set cell [get_cells -of_objects $pin]
          set owner [get_property NAME $cell]
          if {[regexp {(^|/)storageTile_[0-9]+/} $owner] && [string first ${tile}/ $owner] != 0} {
            error "queue state drives another tile: $c -> $owner"
          }
          if {[get_property REF_NAME $cell] in {RAMD32 RAMS32}} {
            if {[string first ${tile}/ $owner] != 0 || ![regexp {^[RW]?ADR[0-4]$} [get_property REF_PIN_NAME $pin]]} {
              error "queue state drives nonlocal/nonaddress RAM pin: $c -> $pin"
            }
            incr ramLoads
          } else {incr logicLoads}
        }
        fpt_local_queue_check_fanout $width $ramLoads $logicLoads
        set max_ram [expr {max($max_ram,$ramLoads)}]
        set max_logic [expr {max($max_logic,$logicLoads)}]
      }
      puts $f "$tile state=[llength $state] max_pointer_fanout=$max_fanout max_ram_pins=$max_ram max_logic_pins=$max_logic RAM_limit=[fpt_local_queue_ram_pin_limit $width] logic_limit=64"
    }
  }
  close $f
  puts "LOCAL_COEFFICIENT_QUEUE_STRUCTURE_PASS tiles=25"
}

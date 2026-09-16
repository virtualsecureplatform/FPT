# Preserve only tile-local payload enables, before opt_design/place_design.
# Command FF preservation alone does not preserve the enable nets' load sets.
proc fpt_rolling_enable_locality {tile driver loads} {
  if {[string first "$tile/" $driver] != 0} {error "nonlocal rolling enable driver: $driver"}
  if {[llength $loads] == 0 || [llength $loads] > 256} {
    error "rolling enable fanout [llength $loads] outside 1..256: $driver"
  }
  foreach load $loads {
    if {[string first "$tile/" $load] != 0} {error "rolling enable escapes tile: $driver -> $load"}
  }
}
proc fpt_protect_rolling_enables {report} {
  set tiles [get_cells -hier -filter {REF_NAME =~ *FptCoefficientRollingHeadTile_4_24_2* && IS_PRIMITIVE == 0}]
  if {[llength $tiles] != 16} {error "rolling enable protection requires 16 four-lane tiles"}
  set payload [get_cells -hier -filter {(NAME =~ *rollingHeadTile*/headsReg_reg* || NAME =~ *rollingHeadTile*/deferredTail_reg*) && REF_NAME =~ FD*}]
  if {[llength $payload] != 7680} {error "rolling enable payload count [llength $payload] != 7680"}
  set actions {}
  foreach tile $tiles {
    set name [get_property NAME $tile]
    set cells {}
    foreach cell $payload {
      if {[string first "$name/" [get_property NAME $cell]] == 0} {lappend cells $cell}
    }
    if {[llength $cells] != 480} {error "rolling enable tile payload count: $name"}
    set seen [dict create]
    foreach pin [get_pins -of_objects $cells -filter {REF_PIN_NAME == CE}] {
      set nets [get_nets -segments -of_objects $pin]
      set key [lsort [get_property NAME $nets]]
      if {[dict exists $seen $key]} {continue}
      dict set seen $key 1
      set drivers [get_cells -of_objects [get_pins -leaf -of_objects $nets -filter {DIRECTION == OUT}]]
      if {[llength $drivers] != 1} {error "ambiguous rolling enable: $pin"}
      set driver [lindex $drivers 0]
      set kind [get_property REF_NAME $driver]
      if {$kind eq "VCC"} {continue}
      if {![string match FD* $kind] && ![string match LUT* $kind]} {error "unexpected rolling enable driver: $driver $kind"}
      set loads [get_pins -leaf -of_objects $nets -filter {DIRECTION == IN}]
      fpt_rolling_enable_locality $name [get_property NAME $driver] [get_property NAME $loads]
      lappend actions [list $nets $driver [llength $loads]]
    }
  }
  if {[llength $actions] != 64} {error "rolling enable net count [llength $actions] != 64"}
  # Validate every target before setting any preservation property.
  set out [open $report w]
  foreach action $actions {
    lassign $action nets driver loads
    set_property DONT_TOUCH true $nets
    set_property DONT_TOUCH true $driver
    foreach net $nets {
      if {![get_property DONT_TOUCH $net]} {error "rolling enable net protection failed: $net"}
    }
    puts $out "ROLLING_ENABLE_PROTECTED\t$driver\t$loads\t$nets"
  }
  close $out
  puts "ROLLING_ENABLE_PROTECTION_PASS tiles=16 nets=64"
}

source [file join [file dirname [info script]] .. vitis scripts check_fpt_registered_links.tcl]
set tx {}; set rx {}; set connections [dict create]; set slrs [dict create]
for {set bit 0} {$bit < 32} {incr bit} {
  set a "sourceTx/payload/value_reg\[$bit\]"
  set b "destinationRx/payload/value_reg\[$bit\]"
  lappend tx $a; lappend rx $b
  dict set connections $a [list $b/D]
  dict set slrs $a SLR1; dict set slrs $b SLR0
}
proc get_nets {args} {return [file dirname [lindex $args end]]}
proc get_pins {args} {
  set i [lsearch -exact $args -of_objects]
  set object [lindex $args [expr {$i + 1}]]
  if {[lsearch -exact $args -leaf] >= 0} {return [dict get $::connections $object]}
  return $object/Q
}
proc get_cells {args} {return [file dirname [lindex [lindex $args end] 0]]}
proc get_sites {args} {return [lindex $args end]}
proc get_slrs {args} {return [dict get $::slrs [lindex $args end]]}
proc get_property {property object} {
  switch -- $property {
    NAME {return $object}
    REF_PIN_NAME {return [file tail [lindex $object 0]]}
    BEL {return AFF}
    default {error "unexpected property $property"}
  }
}
proc reject {script pattern} {
  if {![catch {uplevel 1 $script} message] || ![string match $pattern $message]} {
    error "expected $pattern, got $message"
  }
}
fpt_check_link_pair $tx $rx 32 SLR1 SLR0 1 stdout
reject {fpt_check_link_pair [lrange $tx 1 end] $rx 32 SLR1 SLR0 0 stdout} {*bank count*}
set first [lindex $tx 0]; set sink [lindex $rx 0]
dict set connections $first [list $sink/D unwanted/D]
reject {fpt_check_link_pair $tx $rx 32 SLR1 SLR0 0 stdout} {*exactly one FF D*}
dict set connections $first {unwanted/D}
reject {fpt_check_link_pair $tx $rx 32 SLR1 SLR0 0 stdout} {*bypasses RX*}
dict set connections $first [list $sink/CE]
reject {fpt_check_link_pair $tx $rx 32 SLR1 SLR0 0 stdout} {*exactly one FF D*}
dict set connections $first [list $sink/D]
dict set slrs $sink SLR1
reject {fpt_check_link_pair $tx $rx 32 SLR1 SLR0 1 stdout} {*ownership mismatch*}
puts "PREPARED_WORD_LINK_TOPOLOGY_TEST_PASS"

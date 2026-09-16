source [file join [file dirname [info script]] .. vitis scripts protect_fpt_rolling_enables.tcl]
set guard [file normalize [lindex $argv 0]]
if {![file isfile $guard]} {error "expected cached-place-guard.tcl"}
proc get_cells {args} {
  if {[lsearch -exact $args -hier] >= 0} {return [lrepeat 7680 payload]}
  return [lindex $args end]
}
proc get_pins {args} {
  set object [lindex $args [expr {[lsearch -exact $args -of_objects]+1}]]
  if {[llength $object] == 7680} {
    set pins {}; for {set i 0} {$i < 64} {incr i} {lappend pins pin$i}; return $pins
  }
  regexp {[0-9]+$} $object i
  if {[string first {DIRECTION == OUT} [lindex $args end]] >= 0} {return driver$i}
  return load$i
}
proc get_nets {args} {
  regexp {[0-9]+$} [lindex $args end] i
  return net$i
}
proc get_property {property object} {
  if {$property eq "DONT_TOUCH"} {return [expr {!$::lost}]}
  if {$property eq "REF_NAME"} {return LUT2}
  if {$property eq "NAME"} {
    regexp {[0-9]+$} $object i
    if {[string match net* $object]} {return $object}
    set tile [expr {$i / 4}]
    if {[string match load* $object]} {
      if {$::escape} {incr tile}
      return top/rollingHeadTile_$tile/heads/CE
    }
    return top/rollingHeadTile_$tile/enable
  }
  error "unexpected property"
}
set lost 0; set escape 0
if {![catch {source $guard} message] || ![string match {*did not execute*} $message]} {
  error "missing pre-opt execution accepted"
}
set ::fpt_rolling_enable_hook_executed [file dirname $guard]
source $guard
set lost 1
if {![catch {source $guard} message] || ![string match {*protection lost*} $message]} {
  error "lost preservation accepted"
}
set lost 0; set escape 1
if {![catch {source $guard} message] || ![string match {*escapes tile*} $message]} {
  error "cross-tile enable accepted"
}
puts "CACHED_ENABLE_GUARD_TEST_PASS"

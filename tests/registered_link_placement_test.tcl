source [file join [file dirname [info script]] .. vitis scripts check_fpt_registered_links.tcl]
proc get_sites {args} {return [dict get $::cell_sites [lindex $args end]]}
proc get_slrs {args} {
  set object [lindex $args end]
  if {[dict exists $::site_slrs $object]} {return [dict get $::site_slrs $object]}
  # Reproduce Vivado's empty direct macro-to-SLR query.
  return {}
}
proc get_property {property object} {
  if {$property ne "NAME"} {error "unexpected property $property"}
  return $object
}
proc reject {script pattern} {
  if {![catch {uplevel 1 $script} message] || ![string match $pattern $message]} {
    error "expected rejection $pattern for $script; got $message"
  }
}
set cell_sites [dict create macro site0 macro/RAMA site0 ff site0 dsp dsp0 \
  same_slr {site0 site0b} wrong site1 unplaced {} unknown site_unknown \
  ambiguous site_ambiguous split {site0 site1} incomplete {site0 site_unknown}]
set site_slrs [dict create site0 SLR0 site0b SLR0 dsp0 SLR0 site1 SLR1 \
  site_unknown {} site_ambiguous {SLR0 SLR1}]
if {[get_slrs -of_objects macro] ne {}} {error "fixture must reproduce direct-query failure"}
foreach cell {macro macro/RAMA ff dsp same_slr} {fpt_require_placed_slr $cell SLR0}
reject {fpt_require_placed_slr wrong SLR0} {*expected=SLR0 actual=SLR1*}
reject {fpt_placed_slr unplaced} {*no physical placement site*}
foreach cell {unknown ambiguous incomplete} {
  reject {fpt_placed_slr $cell} {*unresolved physical SLR*}
}
reject {fpt_placed_slr split} {*span multiple SLRs*}
puts "REGISTERED_LINK_PLACEMENT_PASS"

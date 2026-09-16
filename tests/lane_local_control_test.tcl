source [file join [file dirname [info script]] ../vitis/scripts/check_fpt_lane_local.tcl]
proc get_property {property object} {
  if {$property eq "NAME"} {return $object}
  if {$property eq "REF_NAME"} {return $::driver_kind}
  error "unexpected property $property"
}
proc get_pins {args} {return pin}
proc get_nets {args} {return net}
proc get_cells {args} {return driver}
proc fpt_it_limit {cell pin limit} {
  if {$::loads > $limit} {error "fanout limit"}
  return $::loads
}
set children {tile/a tile/b tile2/c top/tile/d}
if {[fpt_lane_members $children tile] ne {tile/a tile/b}} {error "prefix ownership check failed"}
set loads 18000
foreach driver_kind {GND VCC} {
  if {[fpt_lane_input_limit cell 64] != 0} {error "constant was classified as a broadcast"}
}
set driver_kind LUT4
if {![catch {fpt_lane_input_limit cell 64}]} {error "real high-fanout input was accepted"}
set loads 16
if {[fpt_lane_input_limit cell 64] != 16} {error "valid input fanout failed"}
puts LANE_LOCAL_CONTROL_TEST_PASS

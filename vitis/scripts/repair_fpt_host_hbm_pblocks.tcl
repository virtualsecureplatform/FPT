# Narrow, opt-in host-register pblock repair. Source after fpt_host_hbm_laguna.tcl.
# No platform pblock geometry, DFX boundary, clock, or RTL changes are permitted.
proc fpt_host_hbm::assert_membership_delta {before after removed} {
  set expected [dict create]
  foreach cell $before {dict set expected $cell 1}
  foreach cell $removed {
    if {![dict exists $expected $cell]} {error "repair target not in original pblock: $cell"}
    dict unset expected $cell
  }
  if {[lsort -unique $after] ne [lsort [dict keys $expected]]} {
    error "pblock repair changed unrelated membership"
  }
}

proc fpt_host_hbm::laguna_ranges {sites} {
  set columns [dict create]
  foreach site [lsort -unique $sites] {
    if {![regexp {^LAGUNA_X([0-9]+)Y([0-9]+)$} $site -> x y]} {
      error "non-Laguna repair site: $site"
    }
    dict lappend columns $x $y
  }
  set ranges {}
  foreach x [lsort -integer [dict keys $columns]] {
    set start -1; set last -1
    foreach y [lsort -integer [dict get $columns $x]] {
      if {$start >= 0 && $y != $last+1} {
        lappend ranges LAGUNA_X${x}Y${start}:LAGUNA_X${x}Y${last}
        set start -1
      }
      if {$start < 0} {set start $y}
      set last $y
    }
    lappend ranges LAGUNA_X${x}Y${start}:LAGUNA_X${x}Y${last}
  }
  return $ranges
}

proc fpt_host_hbm::repair_pblocks {rows} {
  if {![info exists ::env(FPT_HOST_HBM_LAGUNA_PLACEMENT)] ||
      $::env(FPT_HOST_HBM_LAGUNA_PLACEMENT) ne "1"} {return}
  if {![llength $rows]} {error "pblock repair requires nonempty pairs"}
  set groups [dict create]; set slr_groups [dict create]; set seen [dict create]
  set allocated_sites [dict create]
  foreach r $rows {
    validate_record $r
    describe [dict get $r tx] [dict get $r rx] $r
    foreach role {tx rx} {
      set name [dict get $r $role]; set slr [dict get $r ${role}_slr]
      if {[dict exists $seen $name]} {error "duplicate repair target: $name"}
      dict set seen $name 1
      set c [one [get_cells $name] $name]
      if {[get_property PBLOCK $c] ne "pblock_dynamic_$slr"} {
        error "unexpected original pblock on $name"
      }
      set peer [expr {$role eq "tx" ? "rx" : "tx"}]
      set edge [edge_key $slr [dict get $r ${peer}_slr]]
      dict lappend groups $edge $name
      dict lappend slr_groups $slr $name
      if {[dict exists $r ${role}_site]} {dict set allocated_sites $name [dict get $r ${role}_site]}
    }
  }
  # Snapshot all original pblock geometry and primitive ownership. The only
  # permitted membership delta is removing the exact endpoint leaves.
  set geometry [dict create]; set ownership [dict create]
  foreach b [get_pblocks] {
    foreach prop {GRID_RANGES DERIVED_RANGES IS_SOFT SNAPPING_MODE CONTAIN_ROUTING EXCLUDE_PLACEMENT} {
      dict set geometry $b $prop [get_property $prop $b]
    }
  }
  foreach slr [dict keys $slr_groups] {
    set b pblock_dynamic_$slr
    dict set ownership $b [get_cells -hier -filter "IS_PRIMITIVE == 1 && PBLOCK == $b"]
  }
  set root [one [get_pblocks pblock_dynamic_region] {dynamic region}]
  set root_sites [dict create]
  foreach s [get_sites -of_objects $root -filter {SITE_TYPE == LAGUNA}] {dict set root_sites $s 1}
  foreach edge [dict keys $groups] {
    lassign [split $edge _] slr direction
    if {[llength [get_pblocks -quiet fpt_host_laguna_$edge]]} {error "repair pblock already exists"}
    # A Laguna-only pblock must occupy a single edge, not both edges of
    # the middle SLR (Place 30-779). Derive that edge from device clock rows.
    set clock_rows [dict create]
    foreach cr [get_clock_regions -of_objects [get_slrs $slr]] {
      if {![regexp {Y([0-9]+)$} $cr -> y]} {error "unknown clock region: $cr"}
      dict lappend clock_rows $y $cr
    }
    set ys [lsort -integer [dict keys $clock_rows]]
    set y [expr {$direction eq "upper" ? [lindex $ys end] : [lindex $ys 0]}]
    set sites {}
    foreach s [get_sites -of_objects [get_clock_regions [dict get $clock_rows $y]] -filter {SITE_TYPE == LAGUNA}] {
      if {[dict exists $root_sites $s]} {lappend sites $s}
    }
    if {![llength $sites]} {error "no dynamic Laguna sites on $edge"}
    foreach name [dict get $groups $edge] {
      if {[dict exists $allocated_sites $name] &&
          [lsearch -exact $sites [dict get $allocated_sites $name]] < 0} {
        error "frozen allocation is outside its SLR edge: $name"
      }
    }
    set b [create_pblock fpt_host_laguna_$edge]
    resize_pblock $b -add [laguna_ranges $sites]
    set_property IS_SOFT FALSE $b
    set_property PARENT pblock_dynamic_region $b
    if {[lsort [get_sites -of_objects $b]] ne [lsort $sites]} {
      error "repair pblock geometry is not the exact dynamic/SLR Laguna intersection"
    }
    set cells [get_cells [dict get $groups $edge]]
    remove_cells_from_pblock [get_pblocks pblock_dynamic_$slr] $cells
    add_cells_to_pblock $b $cells
    foreach c $cells {
      if {[get_property PBLOCK $c] ne "fpt_host_laguna_$edge"} {error "repair assignment failed: $c"}
    }
    puts "HOST_HBM_PBLOCK_EDGE_REPAIR $edge registers=[llength $cells] sites=[llength $sites] clock_row=$y"
  }
  foreach slr [dict keys $slr_groups] {
    assert_membership_delta [dict get $ownership pblock_dynamic_$slr] \
      [get_cells -hier -filter "IS_PRIMITIVE == 1 && PBLOCK == pblock_dynamic_$slr"] \
      [dict get $slr_groups $slr]
  }
  dict for {b properties} $geometry {
    dict for {prop value} $properties {
      if {[get_property $prop [get_pblocks $b]] ne $value} {
        error "repair changed original pblock geometry/property: $b $prop"
      }
    }
  }
  # Invalidate any effective-site caches populated before the repair.
  variable pblock_sites; set pblock_sites [dict create]
  variable cell_allowed_cache; array unset cell_allowed_cache
  puts "HOST_HBM_PBLOCK_REPAIR_PASS registers=[dict size $seen]"
}

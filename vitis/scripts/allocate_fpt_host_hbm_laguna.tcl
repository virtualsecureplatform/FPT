# Run in the frozen routed reference, before any implementation changes.
source [file join [file dirname [info script]] fpt_host_hbm_laguna.tcl]
namespace eval fpt_host_hbm {variable pblock_sites [dict create]}

proc fpt_host_hbm::site_allowed {cell site} {
  variable pblock_sites
  variable cell_allowed_cache
  if {[info exists cell_allowed_cache($cell)]} {
    return [dict exists $cell_allowed_cache($cell) $site]
  }
  set blocks [get_pblocks -quiet -of_objects [get_cells $cell]]
  # Always enforce the dynamic region, including when membership is inherited.
  lappend blocks [one [get_pblocks -quiet pblock_dynamic_region] {dynamic region}]
  set allowed {}; set first 1
  foreach block [lsort -unique $blocks] {
    if {[optional_boolean [get_property IS_SOFT $block]]} {continue}
    if {![dict exists $pblock_sites $block]} {
      set names [dict create]
      foreach s [get_sites -quiet -of_objects $block -filter {SITE_TYPE == LAGUNA}] {
        dict set names $s 1
      }
      dict set pblock_sites $block $names
    }
    if {$first} {
      set allowed [dict get $pblock_sites $block]; set first 0
    } else {
      foreach name [dict keys $allowed] {
        if {![dict exists $pblock_sites $block $name]} {dict unset allowed $name}
      }
    }
  }
  set cell_allowed_cache($cell) $allowed
  return [dict exists $allowed $site]
}

proc fpt_host_hbm::physical_receiver {site lane {unused_only 0}} {
  # Follow device routing connectivity. Never assume a fixed Y-coordinate
  # offset, or pair identically numbered BELs without checking their wires.
  set pin [get_site_pins -quiet $site/TXQ$lane]
  if {[llength $pin] != 1} {error "missing Laguna TXQ pin: $site/$lane"}
  set frontier [get_nodes -quiet -of_objects $pin]
  set visited [dict create]
  for {set depth 0} {$depth < 4} {incr depth} {
    set nodes {}
    foreach n $frontier {
      if {![dict exists $visited $n]} {dict set visited $n 1; lappend nodes $n}
    }
    if {![llength $nodes]} {break}
    if {[dict size $visited] > 64} {error "not a dedicated SLL connection at $pin"}
    if {$unused_only && [llength [get_nets -quiet -of_objects $nodes]]} {return {}}
    set receivers {}
    foreach p [get_site_pins -quiet -of_objects $nodes -filter {DIRECTION == IN}] {
      if {[regexp {^(LAGUNA_X[0-9]+Y[0-9]+)/RXD([0-5])$} $p -> rx lane_rx]} {
        if {$rx eq $site} {continue}; # Local RX loopback is not an SLR crossing.
        lappend receivers [list $rx RX_REG$lane_rx]
      }
    }
    if {[llength $receivers]} {return [one [lsort -unique $receivers] "physical RX for $pin"]}
    set frontier [get_nodes -quiet -downhill -of_objects $nodes]
  }
  return {}
}

proc fpt_host_hbm::require_legal_sites {cell} {
  variable cell_allowed_cache
  site_allowed $cell {}
  if {![dict size $cell_allowed_cache($cell)]} {
    error "no legal Laguna sites in effective hard pblocks for $cell"
  }
}

proc fpt_host_hbm::region_distance {a b} {
  variable region_cache
  foreach site [list $a $b] {
    if {![info exists region_cache($site)]} {
      set region_cache($site) [one [get_clock_regions -of_objects [get_sites $site]] "$site clock region"]
    }
  }
  set ra $region_cache($a)
  set rb $region_cache($b)
  if {![regexp {X([0-9]+)Y([0-9]+)$} $ra -> ax ay] ||
      ![regexp {X([0-9]+)Y([0-9]+)$} $rb -> bx by]} {error "unknown clock-region coordinates"}
  return [expr {abs($ax-$bx)+abs($ay-$by)}]
}

proc fpt_host_hbm::allocate {failure_file manifest report} {
  variable cell_allowed_cache
  array unset cell_allowed_cache
  set out [open $report w]
  puts $out "Host-HBM reference inventory and legal Laguna allocation"
  set records {}
  foreach pair [read_failures $failure_file] {
    lassign $pair tx rx
    set r [describe $tx $rx]
    foreach role {tx rx} {
      set c [get_cells [dict get $r $role]]
      if {[get_property IS_LOC_FIXED $c] || [get_property IS_BEL_FIXED $c]} {
        error "pre-existing fixed placement on $c; will not override"
      }
    }
    lappend records $r
    puts $out "REFERENCE $r"
    flush $out
  }
  # Fail before enumerating free sites when the platform sub-pblock itself
  # excludes Laguna. GRID_RANGES can name sites absent from DERIVED_RANGES.
  foreach r $records {
    foreach role {tx rx} {require_legal_sites [dict get $r $role]}
  }
  # Entirely unused sites avoid sharing clock/control resources with another
  # clock domain. One allocated pair per site is deliberate for this small trial.
  set free_sites [dict create]
  set occupied [dict create]
  foreach loc [get_property LOC [get_cells -hier -quiet -filter {IS_PRIMITIVE == 1 && LOC =~ LAGUNA*}]] {
    dict set occupied $loc 1
  }
  set region [one [get_pblocks -quiet pblock_dynamic_region] {dynamic region}]
  puts "HOST_HBM_SITE_SCAN occupied=[dict size $occupied]"
  set site_slrs [dict create]
  foreach slr {SLR0 SLR1 SLR2} {
    foreach s [get_sites -of_objects [get_slrs $slr] -filter {SITE_TYPE == LAGUNA}] {
      dict set site_slrs $s $slr
    }
  }
  set candidates [lsort -dictionary [get_sites -of_objects $region -filter {SITE_TYPE == LAGUNA}]]
  set prohibited [dict create]
  if {[llength $candidates] && "PROHIBIT" in [list_property [get_sites [lindex $candidates 0]]]} {
    foreach s $candidates value [get_property PROHIBIT [get_sites $candidates]] {
      if {[optional_boolean $value]} {dict set prohibited $s 1}
    }
  }
  foreach s $candidates {
    if {[dict exists $occupied $s] || [dict exists $prohibited $s]} {continue}
    if {![dict exists $site_slrs $s]} {error "unresolved SLR for $s"}
    dict set free_sites $s [dict get $site_slrs $s]
  }
  puts "HOST_HBM_SITE_SCAN free=[dict size $free_sites]"
  # Avoid thousands of individual physical database lookups. The original
  # SLICE endpoints are still resolved lazily (only 36 such queries).
  variable region_cache
  foreach cr [get_clock_regions] {
    foreach s [get_sites -of_objects $cr -filter {SITE_TYPE == LAGUNA} -quiet] {
      set region_cache($s) $cr
    }
  }
  puts "HOST_HBM_REGION_CACHE_READY"
  set rows {}; set reserved {}; set physical_cache [dict create]
  foreach r $records {
    set ranked {}
    foreach site [dict keys $free_sites] {
      if {$site in $reserved || [dict get $free_sites $site] ne [dict get $r tx_slr]} {continue}
      if {![site_allowed [dict get $r tx] $site]} {continue}
      lappend ranked [list [region_distance $site [dict get $r tx_old_site]] $site]
    }
    set ranked [lsort -integer -index 0 [lsort -dictionary -index 1 $ranked]]
    set best {}; set best_cost -1
    foreach entry $ranked {
      lassign $entry lower_bound txsite
      if {$best_cost >= 0 && $lower_bound > $best_cost} {break}
      if {![dict exists $physical_cache $txsite]} {
        dict set physical_cache $txsite [physical_receiver $txsite 0 1]
      }
      set dest [dict get $physical_cache $txsite]
      if {![llength $dest]} {continue}
      lassign $dest rxsite rxbel
      if {![dict exists $free_sites $rxsite] || $rxsite in $reserved} {continue}
      if {[dict get $free_sites $rxsite] ne [dict get $r rx_slr]} {continue}
      if {![site_allowed [dict get $r rx] $rxsite]} {continue}
      set cost [expr {$lower_bound + [region_distance $rxsite [dict get $r rx_old_site]]}]
      set candidate [list $txsite TX_REG0 $rxsite $rxbel]
      if {$best_cost < 0 || $cost < $best_cost ||
          ($cost == $best_cost && [lindex [lsort -dictionary [list $candidate $best]] 0] eq $candidate)} {
        set best $candidate; set best_cost $cost
      }
      # Zero is the global lower bound; the site order resolves equal-zero ties.
      if {$best_cost == 0} {break}
    }
    if {![llength $best]} {error "no unused legal paired Laguna sites for [dict get $r tx]"}
    lassign $best txsite txbel rxsite rxbel
    lappend reserved $txsite $rxsite
    dict set r tx_site $txsite; dict set r tx_bel $txbel
    dict set r rx_site $rxsite; dict set r rx_bel $rxbel
    dict set r topology_verified 1
    lappend rows $r
    puts $out "ALLOCATION $r"
    flush $out
  }
  validate_allocations $rows
  if {[file exists $manifest]} {error "refusing to overwrite allocation manifest"}
  set f [open $manifest w]
  puts $f "# Host-HBM Laguna allocation v1: exact 18 reference pairs"
  foreach r $rows {puts $f $r}
  close $f
  close $out
  puts "HOST_HBM_LAGUNA_ALLOCATION_PASS pairs=18"
}

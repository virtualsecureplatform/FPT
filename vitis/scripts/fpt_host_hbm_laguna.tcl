# Opt-in physical-only constraints for the frozen host-HBM timing experiment.
# This library never changes clocks, connectivity, or pipeline depth.
namespace eval fpt_host_hbm {
  variable prefix level0_i/ulp/hmss_0/inst/path_6/slice0_6/inst/
}

proc fpt_host_hbm::edge_key {slr peer} {
  if {![regexp {^SLR([0-2])$} $slr -> a] ||
      ![regexp {^SLR([0-2])$} $peer -> b] || abs($a-$b) != 1} {
    error "invalid adjacent SLRs for Laguna edge"
  }
  return ${slr}_[expr {$b > $a ? "upper" : "lower"}]
}

proc fpt_host_hbm::read_failures {path} {
  set f [open $path r]
  set lines [split [read $f] "\n"]
  close $f
  set pairs {}
  foreach line [lrange $lines 1 end] {
    if {[string trim $line] eq ""} {continue}
    # Historical reports contain literal backslash-t separators.
    set fields [split [string map {\\t \t} $line] "\t"]
    if {[llength $fields] != 3} {error "malformed failing-path row"}
    lassign $fields slack source destination
    if {![string is double -strict $slack] || $slack >= 0} {error "invalid failing slack"}
    if {[file tail $source] ne "C" || [file tail $destination] ne "D"} {
      error "unexpected timing endpoint pins"
    }
    lappend pairs [list [file dirname $source] [file dirname $destination]]
  }
  if {[llength $pairs] != 18 || [llength [lsort -unique $pairs]] != 18} {
    error "expected exactly 18 unique frozen host-HBM pairs"
  }
  return $pairs
}

proc fpt_host_hbm::validate_record {r} {
  variable prefix
  foreach role {tx rx} {
    set name [dict get $r $role]
    if {![string equal -length [string length $prefix] $prefix $name]} {
      error "cell outside host-HBM scope: $name"
    }
    if {[dict get $r ${role}_type] ne "FDRE"} {error "unsupported register type: $name"}
    if {[dict get $r ${role}_clock] ne "hbm_aclk"} {error "wrong clock: $name"}
    if {abs([dict get $r ${role}_period] - 2.222) > 0.001} {error "wrong HBM period"}
    if {![dict get $r ${role}_controls_ok]} {error "incompatible register controls: $name"}
  }
  if {[dict get $r sinks] ne [list "[dict get $r rx]/D"]} {
    error "not a direct single-load Q-to-D pair"
  }
  if {![regexp {^SLR([0-2])$} [dict get $r tx_slr] -> a] ||
      ![regexp {^SLR([0-2])$} [dict get $r rx_slr] -> b] || abs($a-$b) != 1} {
    error "non-adjacent or unknown SLR crossing"
  }
}

proc fpt_host_hbm::one {objects label} {
  if {[llength $objects] != 1} {error "missing or ambiguous $label: $objects"}
  return [lindex $objects 0]
}

proc fpt_host_hbm::pin {cell role} {
  return [one [get_pins -quiet -of_objects $cell -filter "REF_PIN_NAME == $role"] "$cell/$role"]
}

proc fpt_host_hbm::optional_boolean {value} {
  if {$value eq ""} {return 0}
  if {![string is boolean -strict $value]} {error "invalid boolean property: $value"}
  return [expr {$value ? 1 : 0}]
}

proc fpt_host_hbm::constant_pin {cell role expected} {
  set nets [get_nets -quiet -segments -of_objects [pin $cell $role]]
  set drivers [get_pins -quiet -leaf -of_objects $nets -filter {DIRECTION == OUT}]
  if {[llength $drivers] != 1} {return 0}
  set owner [get_cells -quiet -of_objects $drivers]
  return [expr {[get_property REF_NAME $owner] eq $expected}]
}

proc fpt_host_hbm::describe {txname rxname {expected {}}} {
  set r [dict create tx $txname rx $rxname]
  foreach role {tx rx} name [list $txname $rxname] {
    # Exact lookup, never substitute a similarly named physical-opt replica.
    set c [one [get_cells -quiet $name] $name]
    if {[get_property NAME $c] ne $name} {error "cell identity mismatch: $name"}
    dict set r ${role}_type [get_property REF_NAME $c]
    set clk [one [get_clocks -quiet -of_objects [pin $c C]] "$name clock"]
    dict set r ${role}_clock [get_property NAME $clk]
    dict set r ${role}_period [get_property PERIOD $clk]
    set controls [expr {[constant_pin $c CE VCC] && [constant_pin $c R GND]}]
    foreach p {IS_C_INVERTED IS_D_INVERTED IS_R_INVERTED} {
      if {$p in [list_property $c]} {
        set inversion [get_property $p $c]
        # Unset optional inversion properties are returned as an empty string.
        if {[optional_boolean $inversion]} {set controls 0}
      }
    }
    dict set r ${role}_controls_ok $controls
    if {$expected eq ""} {
      set site [one [get_sites -quiet -of_objects $c] "$name site"]
      dict set r ${role}_slr [get_property NAME [one [get_slrs -of_objects $site] "$name SLR"]]
      dict set r ${role}_old_site [get_property NAME $site]
    } else {
      dict set r ${role}_slr [dict get $expected ${role}_slr]
    }
  }
  set tx [get_cells -quiet $txname]
  set nets [get_nets -segments -of_objects [pin $tx Q]]
  set loads [get_pins -quiet -leaf -of_objects $nets -filter {DIRECTION == IN}]
  dict set r sinks [lsort [get_property NAME $loads]]
  validate_record $r
  return $r
}

proc fpt_host_hbm::validate_allocations {rows {expected_count 18}} {
  if {$expected_count < 1 || [llength $rows] != $expected_count} {error "expected $expected_count allocations"}
  set cells {}; set bels {}
  foreach r $rows {
    validate_record $r
    if {![dict get $r topology_verified]} {error "unverified TX/RX physical pairing"}
    foreach role {tx rx} {
      set name [dict get $r $role]
      set site [dict get $r ${role}_site]
      set bel [dict get $r ${role}_bel]
      set kind [string toupper $role]
      if {![string match LAGUNA_X*Y* $site] || ![regexp "^${kind}_REG\[0-5\]$" $bel]} {
        error "invalid Laguna site/BEL"
      }
      if {$name in $cells || "$site/$bel" in $bels} {error "duplicate cell or BEL allocation"}
      lappend cells $name
      lappend bels "$site/$bel"
    }
  }
}

proc fpt_host_hbm::read_manifest {path} {
  set f [open $path r]; set text [read $f]; close $f
  set rows {}; set expected_count 18; set version_seen 0
  foreach line [split $text "\n"] {
    if {[regexp {^# Host-HBM Laguna allocation v2 pairs=([1-9][0-9]*)$} $line -> count]} {
      if {$version_seen || [llength $rows]} {error "duplicate or misplaced manifest version"}
      set expected_count $count; set version_seen 1
    }
    if {[string trim $line] eq "" || [string index $line 0] eq "#"} {continue}
    # Parse Tcl lists as data, not executable Tcl.
    if {[llength $line] % 2} {error "malformed allocation record"}
    lappend rows $line
  }
  validate_allocations $rows $expected_count
  return $rows
}

proc fpt_host_hbm::apply {manifest} {
  if {![info exists ::env(FPT_HOST_HBM_LAGUNA_PLACEMENT)] ||
      $::env(FPT_HOST_HBM_LAGUNA_PLACEMENT) ne "1"} {return}
  set rows [read_manifest $manifest]
  # Validate every cell before making any change to the in-memory design.
  foreach r $rows {describe [dict get $r tx] [dict get $r rx] $r}
  foreach r $rows {
    foreach role {tx rx} {
      set c [get_cells [dict get $r $role]]
      set_property USER_SLL_REG TRUE $c
      set_property DONT_TOUCH TRUE $c
      set_property BEL [dict get $r ${role}_bel] $c
      set_property LOC [dict get $r ${role}_site] $c
    }
  }
  puts "HOST_HBM_LAGUNA_APPLY_PASS pairs=[llength $rows]"
}

proc fpt_host_hbm::check {manifest} {
  set rows [read_manifest $manifest]
  foreach r $rows {
    describe [dict get $r tx] [dict get $r rx] $r
    foreach role {tx rx} {
      set c [get_cells [dict get $r $role]]
      foreach {property field} {LOC site BEL bel} {
        set actual [file tail [get_property $property $c]]
        if {$property eq "BEL"} {set actual [lindex [split $actual .] end]}
        if {$actual ne [dict get $r ${role}_${field}]} {
          error "host-HBM placement mismatch: $c $property"
        }
      }
      set site [get_sites -of_objects $c]
      if {[get_property NAME [get_slrs -of_objects $site]] ne [dict get $r ${role}_slr]} {
        error "host-HBM SLR mismatch: $c"
      }
      set peer [expr {$role eq "tx" ? "rx" : "tx"}]
      set block fpt_host_laguna_[edge_key [dict get $r ${role}_slr] [dict get $r ${peer}_slr]]
      if {[get_property PBLOCK $c] ne $block ||
          [get_property IS_SOFT [get_pblocks $block]] ||
          [get_property PARENT [get_pblocks $block]] ne "pblock_dynamic_region"} {
        error "host-HBM repaired pblock mismatch: $c"
      }
      if {[lsearch -exact [get_sites -of_objects [get_pblocks pblock_dynamic_region] -filter {SITE_TYPE == LAGUNA}] $site] < 0} {
        error "host-HBM placement outside dynamic region: $c"
      }
    }
  }
  puts "HOST_HBM_LAGUNA_PLACEMENT_PASS pairs=[llength $rows]"
}

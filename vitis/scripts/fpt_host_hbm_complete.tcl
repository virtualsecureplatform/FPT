# Whole-payload coverage, not timing-report-selected bits. Run allocation in
# the original wide-output routed reference; validate names again before place.
source [file join [file dirname [info script]] allocate_fpt_host_hbm_laguna.tcl]
source [file join [file dirname [info script]] repair_fpt_host_hbm_pblocks.tcl]

proc fpt_host_hbm::payload_channel {name} {
  variable prefix
  if {[string first $prefix $name] != 0 ||
      ![regexp {^(aw|ar|w|b|r)15\.} [string range $name [string length $prefix] end] -> channel]} {
    error "not a host payload channel: $name"
  }
  return $channel
}

proc fpt_host_hbm::inventory_payloads {} {
  variable prefix
  set rows {}; set coverage [dict create]; set receivers [dict create]
  set txs [lsort -dictionary [get_cells -hier -filter "NAME =~ ${prefix}*laguna_m_payload_i_reg* && IS_PRIMITIVE == 1"]]
  if {![llength $txs]} {error "no host payload transmitters"}
  foreach tx $txs {
    set channel [payload_channel $tx]
    set nets [get_nets -segments -of_objects [pin $tx Q]]
    set loads [get_pins -leaf -of_objects $nets -filter {DIRECTION == IN}]
    set load [one $loads "$tx payload receiver"]
    set rx [one [get_cells -of_objects $load] "$tx receiver cell"]
    if {[payload_channel $rx] ne $channel ||
        ![string match *laguna_s_payload_d_reg* $rx] ||
        [get_property REF_PIN_NAME $load] ne "D"} {error "unsupported live payload crossing: $tx -> $load"}
    if {[dict exists $receivers $rx]} {error "shared payload receiver: $rx"}
    dict set receivers $rx 1
    set r [describe $tx $rx]
    dict set r channel $channel
    set seam [lsort [list [dict get $r tx_slr] [dict get $r rx_slr]]]
    dict incr coverage [list $channel $seam]
    lappend rows $r
  }
  set rxs [lsort -dictionary [get_cells -hier -filter "NAME =~ ${prefix}*laguna_s_payload_d_reg* && IS_PRIMITIVE == 1"]]
  if {[lsort $rxs] ne [lsort [dict keys $receivers]]} {error "incomplete receiver coverage"}
  # Frozen platform slice configuration: AXI3, address=33, data=256,
  # ID=1, no USER/REGION. Constant payload fields may disappear in synthesis.
  set nominal [dict create aw 56 ar 56 w 290 b 3 r 260]
  foreach channel {aw w b ar r} {
    foreach seam {{SLR0 SLR1} {SLR1 SLR2}} {
      if {![dict exists $coverage [list $channel $seam]]} {error "missing payload channel/seam: $channel $seam"}
      set live [dict get $coverage [list $channel $seam]]
      set full [dict get $nominal $channel]
      if {$live > $full} {error "unexpected payload replication or changed platform width: $channel $seam"}
      puts "HOST_PAYLOAD_COVERAGE channel=$channel seam=$seam pairs=$live nominal=$full optimized_away=[expr {$full-$live}]"
    }
  }
  return $rows
}

proc fpt_host_hbm::check_complete {manifest} {
  set frozen [read_manifest $manifest]
  set actual [inventory_payloads]
  set want {}; set got {}
  foreach r $frozen {lappend want [list [dict get $r tx] [dict get $r rx]]}
  foreach r $actual {lappend got [list [dict get $r tx] [dict get $r rx]]}
  if {[lsort $want] ne [lsort $got]} {error "host payload inventory changed"}
  check $manifest
  puts "HOST_PAYLOAD_COMPLETE_PASS pairs=[llength $frozen]"
}

proc fpt_host_hbm::verify_payload_names {manifest} {
  variable prefix
  set rows [read_manifest $manifest]
  foreach role {tx rx} stem {laguna_m_payload_i_reg laguna_s_payload_d_reg} {
    set expected {}
    foreach r $rows {lappend expected [dict get $r $role]}
    set actual [get_cells -hier -filter "NAME =~ ${prefix}*${stem}* && IS_PRIMITIVE == 1"]
    if {[lsort $expected] ne [lsort $actual]} {error "new netlist changed complete $role payload inventory"}
  }
  foreach r $rows {describe [dict get $r tx] [dict get $r rx] $r}
  puts "HOST_PAYLOAD_NETLIST_PASS pairs=[llength $rows]"
}

proc fpt_host_hbm::report_complete_timing {manifest report} {
  set rows [read_manifest $manifest]
  set txs {}; set rxs {}; set expected [dict create]
  foreach r $rows {
    lappend txs [dict get $r tx]; lappend rxs [dict get $r rx]
    dict set expected [list [dict get $r tx] [dict get $r rx]] 1
  }
  set out [open $report w]
  puts $out "delay_type\tslack_ns\ttx\trx"
  foreach delay {max min} {
    set paths [get_timing_paths -from [get_cells $txs] -to [get_cells $rxs] \
      -delay_type $delay -max_paths [llength $rows] -nworst 1]
    set covered [dict create]
    foreach path $paths {
      set tx [file dirname [get_property STARTPOINT_PIN $path]]
      set rx [file dirname [get_property ENDPOINT_PIN $path]]
      set key [list $tx $rx]
      if {![dict exists $expected $key] || [dict exists $covered $key]} {error "unexpected/duplicate payload timing path: $key"}
      dict set covered $key 1
      puts $out "$delay\t[get_property SLACK $path]\t$tx\t$rx"
    }
    if {[dict size $covered] != [llength $rows]} {error "incomplete $delay payload timing coverage"}
  }
  close $out
  puts "HOST_PAYLOAD_TIMING_COVERAGE_PASS pairs=[llength $rows]"
}

proc fpt_host_hbm::allocate_complete {seed_manifest manifest report} {
  if {[file exists $manifest]} {error "refusing to overwrite full allocation"}
  set records [inventory_payloads]
  set seeds [dict create]
  foreach r [read_manifest $seed_manifest] {dict set seeds [dict get $r tx] $r}
  set ::env(FPT_HOST_HBM_LAGUNA_PLACEMENT) 1
  repair_pblocks $records
  set occupied [dict create]
  foreach loc [get_property LOC [get_cells -hier -filter {IS_PRIMITIVE == 1 && LOC =~ LAGUNA*}]] {
    dict set occupied $loc 1
  }
  set free [dict create]
  foreach slr {SLR0 SLR1 SLR2} {
    foreach site [get_sites -of_objects [get_slrs $slr] -filter {SITE_TYPE == LAGUNA}] {
      if {![dict exists $occupied $site] && ![optional_boolean [get_property PROHIBIT $site]]} {
        dict set free $site $slr
      }
    }
  }
  # Fill physical location caches in bulk, including original slice endpoints.
  variable region_cache
  foreach cr [get_clock_regions] {
    foreach site [get_sites -of_objects $cr] {set region_cache($site) $cr}
  }
  set rows {}; set reserved [dict create]; set seeded [dict create]
  foreach r $records {
    set tx [dict get $r tx]
    if {![dict exists $seeds $tx]} {continue}
    set seed [dict get $seeds $tx]
    if {[dict get $seed rx] ne [dict get $r rx]} {error "seed connectivity changed"}
    foreach role {tx rx} {
      set site [dict get $seed ${role}_site]
      if {![dict exists $free $site] || ![site_allowed [dict get $r $role] $site]} {error "seed site no longer free/legal"}
      dict set reserved $site 1
      dict set r ${role}_site $site
      dict set r ${role}_bel [dict get $seed ${role}_bel]
    }
    set lane [string index [dict get $r tx_bel] end]
    if {[physical_receiver [dict get $r tx_site] $lane 1] ne [list [dict get $r rx_site] [dict get $r rx_bel]]} {error "seed SLL no longer available"}
    dict set r topology_verified 1
    dict set seeded $tx 1
    lappend rows $r
  }
  if {[dict size $seeded] != [dict size $seeds]} {error "missing preserved seed pair"}
  set slots [dict create]; set topology [dict create]
  foreach r $records {
    set tx [dict get $r tx]
    if {[dict exists $seeded $tx]} {continue}
    set key [list [dict get $r tx_slr] [dict get $r rx_slr] [dict get $r channel]]
    if {![dict exists $slots $key] || ![llength [dict get $slots $key]]} {
      set ranked {}
      foreach site [dict keys $free] {
        if {[dict exists $reserved $site] || [dict get $free $site] ne [dict get $r tx_slr] || ![site_allowed $tx $site]} {continue}
        lappend ranked [list [region_distance $site [dict get $r tx_old_site]] $site]
      }
      set best {}; set best_cost -1
      foreach entry [lsort -integer -index 0 [lsort -dictionary -index 1 $ranked]] {
        lassign $entry lower site
        if {$best_cost >= 0 && $lower > $best_cost} {break}
        if {![dict exists $topology $site]} {
          set found {}
          for {set lane 0} {$lane < 6} {incr lane} {
            set dst [physical_receiver $site $lane 1]
            if {[llength $dst]} {lappend found [list $site TX_REG$lane {*}$dst]}
          }
          dict set topology $site $found
        }
        set available {}
        foreach slot [dict get $topology $site] {
          lassign $slot ts tb rs rb
          if {[dict exists $free $rs] && ![dict exists $reserved $rs] &&
              [dict get $free $rs] eq [dict get $r rx_slr] && [site_allowed [dict get $r rx] $rs]} {lappend available $slot}
        }
        if {![llength $available]} {continue}
        set rs [lindex [lindex $available 0] 2]
        set cost [expr {$lower + [region_distance $rs [dict get $r rx_old_site]]}]
        if {$best_cost < 0 || $cost < $best_cost} {set best $available; set best_cost $cost}
        if {$best_cost == 0} {break}
      }
      if {![llength $best]} {error "no legal packed Laguna site pair for $tx"}
      foreach slot $best {
        dict set reserved [lindex $slot 0] 1
        dict set reserved [lindex $slot 2] 1
      }
      dict set slots $key $best
    }
    set available [dict get $slots $key]
    lassign [lindex $available 0] ts tb rs rb
    dict set slots $key [lrange $available 1 end]
    dict set r tx_site $ts; dict set r tx_bel $tb
    dict set r rx_site $rs; dict set r rx_bel $rb
    dict set r topology_verified 1
    lappend rows $r
  }
  validate_allocations $rows [llength $records]
  set f [open $manifest w]
  puts $f "# Host-HBM Laguna allocation v2 pairs=[llength $rows]"
  foreach r $rows {puts $f $r}
  close $f
  set f [open $report w]
  puts $f "pairs=[llength $rows] preserved_pairs=[dict size $seeded] reserved_sites=[dict size $reserved]"
  foreach r $rows {puts $f $r}
  close $f
  puts "HOST_PAYLOAD_ALLOCATION_PASS pairs=[llength $rows]"
}

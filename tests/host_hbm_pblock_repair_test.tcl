source [file join [file dirname [info script]] .. vitis scripts fpt_host_hbm_laguna.tcl]
source [file join [file dirname [info script]] .. vitis scripts repair_fpt_host_hbm_pblocks.tcl]
set tests 0
proc reject {script pattern} {
  incr ::tests
  if {![catch {uplevel 1 $script} message] || ![string match $pattern $message]} {
    error "expected $pattern, got: $message"
  }
}
fpt_host_hbm::assert_membership_delta {a b c} {c a} b
incr tests
fpt_host_hbm::assert_membership_delta {a b c} {c} {a b}
incr tests
reject {fpt_host_hbm::assert_membership_delta {a b c} {a} b} {*unrelated membership*}
reject {fpt_host_hbm::assert_membership_delta {a b c} {a c d} b} {*unrelated membership*}
reject {fpt_host_hbm::assert_membership_delta {a b c} {a b c} b} {*unrelated membership*}
reject {fpt_host_hbm::assert_membership_delta {a b c} {a b c} d} {*not in original pblock*}
set ranges [fpt_host_hbm::laguna_ranges {LAGUNA_X2Y3 LAGUNA_X2Y1 LAGUNA_X2Y2 LAGUNA_X2Y8 LAGUNA_X1Y9 LAGUNA_X2Y3}]
if {$ranges ne {LAGUNA_X1Y9:LAGUNA_X1Y9 LAGUNA_X2Y1:LAGUNA_X2Y3 LAGUNA_X2Y8:LAGUNA_X2Y8}} {error "range compaction bridged a hole or changed sites"}
incr tests
reject {fpt_host_hbm::laguna_ranges {SLICE_X1Y2}} {*non-Laguna*}
unset -nocomplain ::env(FPT_HOST_HBM_LAGUNA_PLACEMENT)
fpt_host_hbm::repair_pblocks {}
incr tests
set ::env(FPT_HOST_HBM_LAGUNA_PLACEMENT) 1
reject {fpt_host_hbm::repair_pblocks {}} {*requires nonempty pairs*}
foreach {slr peer expected} {SLR0 SLR1 SLR0_upper SLR1 SLR0 SLR1_lower SLR1 SLR2 SLR1_upper SLR2 SLR1 SLR2_lower} {
  if {[fpt_host_hbm::edge_key $slr $peer] ne $expected} {error "wrong edge"}
  incr tests
}
reject {fpt_host_hbm::edge_key SLR0 SLR2} {*invalid adjacent*}
reject {fpt_host_hbm::edge_key SLR1 SLR1} {*invalid adjacent*}
if {[llength $argv]} {
  set groups [dict create]
  foreach r [fpt_host_hbm::read_manifest [lindex $argv 0]] {
    foreach role {tx rx} peer {rx tx} {
      set key [fpt_host_hbm::edge_key [dict get $r ${role}_slr] [dict get $r ${peer}_slr]]
      dict incr groups $key
    }
  }
  if {[dict size $groups] != 4 || [dict get $groups SLR1_lower] != 2 || [dict get $groups SLR1_upper] != 16} {error "frozen endpoint edge split mismatch"}
  incr tests
}
puts "HOST_HBM_PBLOCK_REPAIR_TEST_PASS tests=$tests"

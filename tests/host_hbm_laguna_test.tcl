source [file join [file dirname [info script]] .. vitis scripts fpt_host_hbm_laguna.tcl]
set tests 0
proc reject {script pattern} {
  incr ::tests
  if {![catch {uplevel 1 $script} message] || ![string match $pattern $message]} {
    error "expected $pattern, got: $message"
  }
}
foreach {value expected} {{} 0 0 0 false 0 1 1 true 1} {
  if {[fpt_host_hbm::optional_boolean $value] != $expected} {error "optional boolean mismatch"}
  incr tests
}
reject {fpt_host_hbm::optional_boolean unexpected} {*invalid boolean property*}
set rows {}
for {set i 0} {$i < 18} {incr i} {
  set tx ${fpt_host_hbm::prefix}tx_$i
  set rx ${fpt_host_hbm::prefix}rx_$i
  lappend rows [dict create tx $tx rx $rx tx_type FDRE rx_type FDRE \
    tx_clock hbm_aclk rx_clock hbm_aclk tx_period 2.222 rx_period 2.222 \
    tx_controls_ok 1 rx_controls_ok 1 tx_slr SLR1 rx_slr SLR2 \
    sinks [list $rx/D] topology_verified 1 tx_site LAGUNA_X0Y$i \
    rx_site LAGUNA_X0Y[expr {$i+120}] tx_bel TX_REG0 rx_bel RX_REG0]
}
fpt_host_hbm::validate_allocations $rows
incr tests
set good [lindex $rows 0]
foreach {field value pattern} {
  tx other_kernel/register {*outside host-HBM scope*}
  rx_type FDCE {*unsupported register type*}
  rx_clock kernel_clock {*wrong clock*}
  tx_period 5.0 {*wrong HBM period*}
  tx_controls_ok 0 {*incompatible register controls*}
  rx_controls_ok 0 {*incompatible register controls*}
  rx_slr SLR0 {*non-adjacent*}
  rx_slr UNKNOWN {*non-adjacent*}
  sinks {} {*single-load*}
  sinks {other/D extra/D} {*single-load*}
} {
  set bad [dict replace $good $field $value]
  # SLR0 is adjacent to SLR1, so use SLR0 -> SLR2 for that test.
  if {$field eq "rx_slr" && $value eq "SLR0"} {dict set bad tx_slr SLR2}
  reject {fpt_host_hbm::validate_record $bad} $pattern
}
foreach {field value pattern} {
  topology_verified 0 {*unverified*}
  tx_site SLICE_X0Y0 {*invalid Laguna*}
  tx_bel RX_REG0 {*invalid Laguna*}
  rx_bel RX_REG6 {*invalid Laguna*}
} {
  set bad [lreplace $rows 0 0 [dict replace $good $field $value]]
  reject {fpt_host_hbm::validate_allocations $bad} $pattern
}
set bad [lreplace $rows 1 1 $good]
reject {fpt_host_hbm::validate_allocations $bad} {*duplicate*}
set duplicate [dict replace [lindex $rows 1] tx_site [dict get $good tx_site]]
set bad [lreplace $rows 1 1 $duplicate]
reject {fpt_host_hbm::validate_allocations $bad} {*duplicate*}
reject {fpt_host_hbm::validate_allocations [lrange $rows 0 16]} {*18 allocations*}
reject {fpt_host_hbm::one {} missing} {*missing or ambiguous*}
reject {fpt_host_hbm::one {a b} duplicate} {*missing or ambiguous*}
# Disabled mode must perform no Vivado queries and need no manifest.
unset -nocomplain ::env(FPT_HOST_HBM_LAGUNA_PLACEMENT)
fpt_host_hbm::apply /does/not/exist
incr tests
set ::env(FPT_HOST_HBM_LAGUNA_PLACEMENT) 0
fpt_host_hbm::apply /does/not/exist
incr tests
if {[llength $argv]} {
  fpt_host_hbm::read_failures [lindex $argv 0]
  incr tests
}
# Exercise routing-topology discovery independently of any coordinate offset.
source [file join [file dirname [info script]] .. vitis scripts allocate_fpt_host_hbm_laguna.tcl]
proc get_site_pins {args} {
  if {[lsearch -exact $args -of_objects] < 0} {return [lindex $args end]}
  set nodes [lindex $args [expr {[lsearch -exact $args -of_objects]+1}]]
  set pins {}
  foreach n $nodes {
    if {[dict exists $::node_pins $n]} {set pins [concat $pins [dict get $::node_pins $n]]}
  }
  return $pins
}
proc get_nodes {args} {
  set objects [lindex $args end]
  if {[lsearch -exact $args -downhill] < 0} {return launch_node}
  set next {}
  foreach n $objects {
    if {[dict exists $::edges $n]} {set next [concat $next [dict get $::edges $n]]}
  }
  return $next
}
set edges [dict create launch_node mux_node mux_node receive_node]
set node_pins [dict create receive_node LAGUNA_X7Y381/RXD3]
if {[fpt_host_hbm::physical_receiver LAGUNA_X4Y221 0] ne {LAGUNA_X7Y381 RX_REG3}} {
  error "physical pairing must follow routing, not coordinates or lane number"
}
incr tests
dict set node_pins receive_node {LAGUNA_X4Y221/RXD0 LAGUNA_X7Y381/RXD3}
if {[fpt_host_hbm::physical_receiver LAGUNA_X4Y221 0] ne {LAGUNA_X7Y381 RX_REG3}} {
  error "local RX loopback must not count as a crossing"
}
incr tests
dict set node_pins receive_node {LAGUNA_X7Y381/RXD3 LAGUNA_X8Y381/RXD3}
reject {fpt_host_hbm::physical_receiver LAGUNA_X4Y221 0} {*ambiguous*}
set node_pins {}
if {[fpt_host_hbm::physical_receiver LAGUNA_X4Y221 0] ne {}} {error "fabric-only path accepted"}
incr tests
proc get_nets {args} {return existing_sll_route}
if {[fpt_host_hbm::physical_receiver LAGUNA_X4Y221 0 1] ne {}} {error "occupied SLL accepted"}
incr tests
proc get_cells {args} {return [lindex $args end]}
proc get_pblocks {args} {
  if {[lsearch -exact $args -of_objects] >= 0} {return owner_block}
  return [lindex $args end]
}
proc get_sites {args} {
  set object [lindex $args [expr {[lsearch -exact $args -of_objects]+1}]]
  return [dict get $::block_sites $object]
}
proc get_property {property object} {
  if {$property eq "IS_SOFT"} {return 0}
  error "unexpected mocked property $property"
}
set block_sites [dict create owner_block {site_a site_b} pblock_dynamic_region {site_b site_c}]
if {![fpt_host_hbm::site_allowed cell site_b]} {error "legal intersection rejected"}
foreach site {site_a site_c outside_site} {
  if {[fpt_host_hbm::site_allowed cell $site]} {error "outside-pblock site accepted"}
  incr tests
}
incr tests
set fpt_host_hbm::cell_allowed_cache(blocked_cell) {}
reject {fpt_host_hbm::require_legal_sites blocked_cell} {*no legal Laguna sites in effective hard pblocks*}
puts "HOST_HBM_LAGUNA_UNIT_PASS checks=$tests"

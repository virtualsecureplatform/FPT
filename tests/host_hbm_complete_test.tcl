source [file join [file dirname [info script]] .. vitis scripts fpt_host_hbm_complete.tcl]
set tests 0
proc reject {script pattern} {
  incr ::tests
  if {![catch {uplevel 1 $script} message] || ![string match $pattern $message]} {error "expected $pattern, got $message"}
}
foreach channel {aw w b ar r} {
  if {[fpt_host_hbm::payload_channel ${fpt_host_hbm::prefix}${channel}15.anything] ne $channel} {error "channel mismatch"}
  incr tests
}
reject {fpt_host_hbm::payload_channel other/r15.anything} {*not a host payload*}
reject {fpt_host_hbm::payload_channel ${fpt_host_hbm::prefix}other15.anything} {*not a host payload*}
set seeds [fpt_host_hbm::read_manifest [lindex $argv 0]]
set rows [lrange $seeds 0 2]
fpt_host_hbm::validate_allocations $rows 3
incr tests
reject {fpt_host_hbm::validate_allocations $rows 4} {*expected 4*}
set f [file tempfile path]
puts $f "# Host-HBM Laguna allocation v2 pairs=3"
foreach r $rows {puts $f $r}
close $f
if {[fpt_host_hbm::read_manifest $path] ne $rows} {error "versioned manifest failed"}
file delete $path
incr tests
puts "HOST_HBM_COMPLETE_TEST_PASS checks=$tests"

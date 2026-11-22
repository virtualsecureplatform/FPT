# route_design pre-hook for the physical-slrs floorplan: keep a small setup
# margin on the platform HBM clock while routing. The post-route gate removes
# it before any acceptance check.
if {[llength [get_clocks -quiet hbm_aclk]]} {
  set_clock_uncertainty -setup 0.040 [get_clocks hbm_aclk]
  puts "PAPER_PLATFORM_MARGIN: hbm_aclk setup uncertainty 0.040 ns for routing"
}

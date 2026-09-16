source [file join [file dirname [info script]] .. vitis scripts check_fpt_registered_links.tcl]
foreach {width count} {0 96 4 192} {
  fpt_check_scratch_selector_count [lrepeat $count fixture] $width
  foreach wrong [list 0 [expr {$count - 1}] [expr {$count + 1}] [expr {288 - $count}]] {
    if {![catch {fpt_check_scratch_selector_count [lrepeat $wrong fixture] $width}]} {
      error "accepted wrong selector count $wrong for width $width"
    }
  }
}
foreach width {1 2 8 invalid} {
  if {![catch {fpt_check_scratch_selector_count [lrepeat 96 fixture] $width}]} {
    error "accepted unsupported width $width"
  }
}
puts "SCRATCH_SELECTOR_COUNT_PASS"

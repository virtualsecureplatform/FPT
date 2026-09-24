# Check preserved boundaries by ownership, not generated REF_NAME suffixes or
# raw FF totals (constant bits can fold and physical optimization can replicate).
proc fpt_buffered_cut {root role slr placed out} {
  set cells [get_cells -hier -quiet -filter "NAME =~ ${root}/${role}/value_reg* && REF_NAME =~ FD*"]
  if {![llength $cells]} {error "missing buffered control cut: $root/$role"}
  foreach cell $cells {
    if {$placed} {
      set actual [get_slrs -of_objects [get_sites -of_objects $cell]]
      if {[llength $actual] != 1 || [get_property NAME $actual] ne $slr} {
        error "buffered control cut outside $slr: $cell ($actual)"
      }
    }
  }
  puts $out "$root/$role ff=[llength $cells] expected_slr=$slr placed=$placed"
}
proc fpt_check_buffered_controls {report {placed 0}} {
  if {![info exists ::env(FPT_U280_BUFFERED_OUTPUT)] || $::env(FPT_U280_BUFFERED_OUTPUT) ne "1"} {return}
  set roots [get_cells -hier -quiet -filter {NAME =~ */sequencer/outputRetirement && IS_PRIMITIVE == 0}]
  if {[llength $roots] != 1} {error "buffered control retirement root count [llength $roots]"}
  set root [get_property NAME [lindex $roots 0]]
  set controller [file dirname [file dirname $root]]
  set out [open $report w]
  # Vivado 2023.2 embeds Tcl 8.5: use catch/close instead of Tcl 8.6 try/finally.
  set failed [catch {
    foreach role {payload token} {fpt_buffered_cut $root dmaEvent/$role SLR0 $placed $out}
    foreach role {stopAccept stopRecord0 stopRecord1 stopStream stopRetire} {
      # Accommodate optional Scala-val prefixes on the explicit suggested name.
      fpt_buffered_cut $root *$role SLR0 $placed $out
    }
    foreach {boundary slr} {sourceTx SLR0 destinationRx SLR1} {
      foreach role {payload token} {
        fpt_buffered_cut ${controller}/completionLink $boundary/$role $slr $placed $out
      }
    }
    set stores [get_cells -hier -quiet -filter {NAME =~ */coefficients/loadCommandToken && IS_PRIMITIVE == 0}]
    if {[llength $stores] != 1} {error "initialization command boundary count [llength $stores]"}
    set store [file dirname [get_property NAME [lindex $stores 0]]]
    foreach role {loadCommandPayload loadCommandToken} {fpt_buffered_cut $store $role SLR0 $placed $out}
  } message options]
  close $out
  if {$failed} {return -options $options $message}
  puts "BUFFERED_CONTROL_CUTS_PASS placed=$placed"
}

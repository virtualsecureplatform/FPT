# The new mask store must be banked BRAM, not a replicated LUTRAM snapshot.
proc fpt_check_buffered_output {report {placed 0}} {
  if {![info exists ::env(FPT_U280_BUFFERED_OUTPUT)] || $::env(FPT_U280_BUFFERED_OUTPUT) ne "1"} {return}
  # Vitis prefixes packaged module names. Match the exact logical suffix,
  # excluding generated queue/helper names that merely contain this string.
  set roots {}
  foreach cell [get_cells -quiet -hier -filter {REF_NAME =~ *BufferedSampleExtract* && IS_PRIMITIVE == 0}] {
    if {[regexp {(^|_)BufferedSampleExtract$} [get_property REF_NAME $cell]]} {
      lappend roots $cell
    }
  }
  if {[llength $roots] != 1} {error "expected one buffered extractor, found [llength $roots]"}
  set root [get_property NAME [lindex $roots 0]]
  set ram [get_cells -hier -filter "NAME =~ $root/*snapshotMaskMemory* && (REF_NAME =~ RAMB36* || REF_NAME =~ RAMB18*)"]
  set tiles 0
  foreach cell $ram {
    if {[string match RAMB36* [get_property REF_NAME $cell]]} {set tiles [expr {$tiles + 1}]} else {set tiles [expr {$tiles + 0.5}]}
    if {$placed} {fpt_require_placed_slr $cell SLR0}
  }
  if {$tiles != 16} {error "snapshot mask allocation $tiles != 16 BRAM tiles"}
  set obsolete [get_cells -quiet -hier -filter "NAME =~ $root/*maskMemory* && IS_PRIMITIVE"]
  if {[llength $obsolete]} {error "obsolete per-context extraction RAM remains"}
  set out [open $report w]
  puts $out "mask_bram_tiles\t$tiles"
  puts $out "placed\t$placed"
  foreach cell $ram {puts $out "ram\t[get_property NAME $cell]\t[get_property REF_NAME $cell]"}
  close $out
  puts "BUFFERED_OUTPUT_STRUCTURE_PASS bram_tiles=$tiles placed=$placed"
}

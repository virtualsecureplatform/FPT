# Ensure generated IP is resolved before complete-kernel reporting. Vivado
# normally reads the IP DCPs during final translation; explicitly fill any
# remaining black boxes rather than reporting only the RTL primitives.
proc paper_resolve_datamovers {mm2s_checkpoint s2mm_checkpoint} {
  foreach {reference expected checkpoint} [list axi_datamover_mm2s 5 $mm2s_checkpoint axi_datamover_s2mm 1 $s2mm_checkpoint] {
    set cells [get_cells -hierarchical -filter "REF_NAME == $reference"]
    if {[llength $cells]!=$expected} {error "expected $expected $reference instances; got [llength $cells]"}
    foreach cell $cells {
      if {[get_property IS_BLACKBOX $cell]} {read_checkpoint -cell $cell $checkpoint}
    }
  }
  set boxes [get_cells -hierarchical -quiet -filter {IS_BLACKBOX == 1}]
  if {[llength $boxes]} {error "unresolved kernel cells remain: $boxes"}
}

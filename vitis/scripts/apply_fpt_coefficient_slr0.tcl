# Called only by the opt-in coefficient_slr0_narrow place pre-hook.
source [file join [file dirname [info script]] fpt_prepared_word_link.tcl]
proc fpt_required_cell {name} {
  set cell [get_cells -quiet $name]
  if {[llength $cell] != 1} {error "missing or ambiguous narrow-link hierarchy: $name"}
  return $cell
}
proc fpt_apply_coefficient_slr0 {cmux} {
  set slr0 {}; set slr1 {}; set slr2 {}
  foreach name {coefficients inverse componentJoin inverseTags inverseOutputLocalBoundary} {
    lappend slr0 [fpt_required_cell ${cmux}/$name]
  }
  foreach name {external externalOutputPipeline pendingRequests forwardTags} {
    lappend slr1 [fpt_required_cell ${cmux}/$name]
  }
  set fixed_inverse [get_cells -hier -filter "NAME =~ ${cmux}/inverseBoundary/*payloadBoundary/value_reg* && REF_NAME =~ FD*"]
  if {[llength $fixed_inverse]} {
    lappend slr0 [fpt_required_cell ${cmux}/inverseBoundary]
    set fixed_tx [get_cells -hier -filter "NAME =~ ${cmux}/externalOutputPipeline/*payloadBoundary/value_reg* && REF_NAME =~ FD*"]
    if {[llength $fixed_inverse] != 3845 || [llength $fixed_tx] != 3845} {
      error "fixed inverse payload+metadata count TX=[llength $fixed_tx] RX=[llength $fixed_inverse] expected=3845"
    }
    set_property USER_SLL_REG TRUE [concat $fixed_tx $fixed_inverse]
    set_property DONT_TOUCH TRUE [concat $fixed_tx $fixed_inverse]
  } else {
    lappend slr1 [fpt_required_cell ${cmux}/inverseBoundary]
  }
  set forward_root ${cmux}/forward/core/backend/generated
  lappend slr2 [fpt_required_cell ${forward_root}/front]
  lappend slr1 [fpt_required_cell ${forward_root}/back]
  foreach {role slr} {sourceTx 0 middleRx 1 middleTx 1 destinationRx 2} {
    set bank [fpt_required_cell ${cmux}/coefficientDigitLink/$role]
    lappend slr$slr $bank
    set payload [get_cells -hier -filter "NAME =~ ${bank}/outputPayload_payloadBoundary/value_reg* && REF_NAME =~ FD*"]
    if {[llength $payload] != 2560} {error "digit payload count $role: [llength $payload] != 2560"}
    set_property USER_SLL_REG TRUE $payload
    set_property DONT_TOUCH TRUE $payload
  }
  set tx [get_cells -hier -filter "NAME =~ ${forward_root}/boundary/tx_* && IS_SEQUENTIAL"]
  set rx [get_cells -hier -filter "NAME =~ ${forward_root}/boundary/rx_* && IS_SEQUENTIAL"]
  set back_control [get_cells -hier -filter "NAME =~ ${forward_root}/boundary/back_token_delay* && IS_SEQUENTIAL"]
  if {[llength $tx] < 7680 || [llength $rx] < 7680} {error "missing FFT partition registers"}
  set slr2 [concat $slr2 $tx]
  set slr1 [concat $slr1 $rx $back_control]
  set_property USER_SLL_REG TRUE [concat $tx $rx]

  # All loader/scheduler/extraction leaves belong to this wrapper, whereas
  # the BK buffer is its sibling. Exclude the complete CMUX subtree before
  # collecting leaves, so no FFT or External Product cell is dragged to SLR0.
  set blind_rotate [file dirname $cmux]
  set extraction_wrapper [file dirname $blind_rotate]
  if {[info exists ::env(FPT_U280_BUFFERED_OUTPUT)] && $::env(FPT_U280_BUFFERED_OUTPUT) eq "1"} {
    # The sequencer's soft SLR preference did not prevent the packer feedback
    # from bouncing across SLR0/1 three times in one cycle. Constrain only
    # the output retirement island, not the complete sequencer/controller.
    set controller [file dirname [file dirname [file dirname $extraction_wrapper]]]
    set output_island [fpt_required_cell ${controller}/sequencer/outputRetirement]
    lappend slr0 $output_island
    set_property USER_SLR_ASSIGNMENT SLR0 $output_island
    set completion_tx [fpt_required_cell ${controller}/completionLink/sourceTx]
    set completion_rx [fpt_required_cell ${controller}/completionLink/destinationRx]
    lappend slr0 $completion_tx
    lappend slr1 $completion_rx
    set_property USER_SLR_ASSIGNMENT SLR0 $completion_tx
    set_property USER_SLR_ASSIGNMENT SLR1 $completion_rx
    foreach boundary [list $completion_tx $completion_rx] {
      set registers [get_cells -hier -quiet -filter "NAME =~ ${boundary}/*/value_reg* && REF_NAME =~ FD*"]
      if {![llength $registers]} {error "missing completion crossing registers: $boundary"}
      set_property USER_SLL_REG TRUE $registers
      set_property DONT_TOUCH TRUE $registers
    }
    puts "BUFFERED_OUTPUT_FLOORPLAN_PASS retirement=SLR0"
  }
  # Override the packaged XDC's old sample-extraction SLR1 assignment too.
  # The leaf collection below supplies its pblock membership.
  set_property USER_SLR_ASSIGNMENT SLR0 [fpt_required_cell ${extraction_wrapper}/sampleExtract]
  set local_owners [get_cells -hier -filter "NAME =~ ${extraction_wrapper}/* && NAME !~ ${cmux}/* && IS_PRIMITIVE"]
  if {[llength $local_owners] == 0} {error "missing local loader/extraction leaves"}
  set local_tags [get_cells -hier -filter "NAME =~ ${cmux}/registeredInverseTag* && IS_SEQUENTIAL"]
  if {[llength $local_tags] != 5} {error "registered inverse tag/valid count [llength $local_tags] != 5"}
  set slr0 [concat $slr0 $local_owners $local_tags]
  set local_inverse [get_cells -hier -filter "NAME =~ ${cmux}/inverseOutputLocalBoundary/outputPayload_payloadBoundary/value_reg* && REF_NAME =~ FD*"]
  if {[llength $local_inverse] != 3840} {error "local inverse payload count [llength $local_inverse] != 3840"}
  set_property USER_SLL_REG FALSE $local_inverse
  set prepared_root [fpt_prepared_word_root]
  if {$prepared_root ne ""} {
    # Keep queue addressing and 16:1 selection on the TX side. Do not constrain
    # the entire controller: other interfaces retain their established owners.
    lappend slr1 [fpt_required_cell [file dirname $prepared_root]/chain]
    lappend slr1 [fpt_required_cell ${prepared_root}/sourceTx]
    lappend slr0 [fpt_required_cell ${prepared_root}/destinationRx]
    foreach role {sourceTx destinationRx} {
      set data [fpt_prepared_word_registers $prepared_root $role payload]
      set token [fpt_prepared_word_registers $prepared_root $role token]
      set_property USER_SLL_REG TRUE $data
      set_property DONT_TOUCH TRUE [concat $data $token]
    }
    puts "PREPARED_WORD_FLOORPLAN_PASS TX=SLR1 RX=SLR0 payload=32 stages=2"
  }
  foreach index {0 1 2} {
    set cells [set slr$index]
    fpt_hard_pblock fpt_narrow_coefficients_slr$index SLR$index $cells
    set_property USER_SLR_ASSIGNMENT SLR$index $cells
  }
  puts "FPT_HARD_FLOORPLAN mode=coefficient_slr0_narrow coefficients+inverse+load+drain=SLR0 external+FFTback=SLR1 FFTfront=SLR2 digitPayload=2560 localInverse=3840"
}

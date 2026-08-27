# This is a place_design pre-hook, not an XDC file.  Vitis parses an IP-scoped
# XDC multiple times, while pblocks are design-global objects and must be
# created exactly once after link_design.
set cmux [get_cells -quiet -hierarchical -filter \
  {NAME =~ */controller/core/engine/blindRotate/blindRotate/cmux}]
if {[llength $cmux] != 1} {
  error "expected one FPT CMUX hierarchy, found [llength $cmux]: $cmux"
}

proc fpt_hard_pblock {name slr cells} {
  create_pblock $name
  resize_pblock [get_pblocks $name] -add ${slr}:${slr}
  add_cells_to_pblock [get_pblocks $name] $cells
}

set floorplan_mode partition
if {[info exists ::env(FPT_HARD_FLOORPLAN_MODE)]} {
  set floorplan_mode $::env(FPT_HARD_FLOORPLAN_MODE)
}
if {$floorplan_mode ni {whole_forward partition partition_spill}} {
  error "FPT_HARD_FLOORPLAN_MODE must be whole_forward, partition, or partition_spill"
}

set inverse [get_cells -quiet ${cmux}/inverse]
if {[llength $inverse] != 1} {
  error "missing FPT inverse hierarchy: inverse=$inverse"
}

set coefficients [get_cells -quiet ${cmux}/coefficients]
set external [get_cells -quiet ${cmux}/external]
set forward [get_cells -quiet ${cmux}/forward]
set forward_input_boundary [get_cells -quiet ${cmux}/forwardInputBoundary]
set pending_requests [get_cells -quiet ${cmux}/pendingRequests]
set inverse_boundary [get_cells -quiet ${cmux}/inverseBoundary]
set external_output_pipeline [get_cells -quiet ${cmux}/externalOutputPipeline]
foreach required [list $coefficients $external $forward \
    $forward_input_boundary $pending_requests $inverse_boundary \
    $external_output_pipeline] {
  if {[llength $required] != 1} {
    error "missing whole-module floorplan hierarchy: $required"
  }
}

if {$floorplan_mode eq "whole_forward"} {
  # Keep each high-bandwidth arithmetic unit intact.  The coefficient store
  # follows the inverse accumulator and HBM-side control into SLR0, the key
  # and External Product state stay in SLR1, and the complete forward FFT gets
  # the DSP/BRAM-rich SLR2.  forwardInputBoundary is the registered SLR1 relay
  # for the otherwise two-SLR coefficient-to-FFT hop.
  fpt_hard_pblock fpt_coefficients_inverse_slr0 SLR0 \
    [concat $coefficients $inverse]
  fpt_hard_pblock fpt_external_relays_slr1 SLR1 \
    [concat $external $forward_input_boundary $pending_requests \
      $inverse_boundary $external_output_pipeline]
  fpt_hard_pblock fpt_forward_slr2 SLR2 $forward

  set_property USER_SLR_ASSIGNMENT SLR0 [concat $coefficients $inverse]
  set_property USER_SLR_ASSIGNMENT SLR1 \
    [concat $external $forward_input_boundary $pending_requests \
      $inverse_boundary $external_output_pipeline]
  set_property USER_SLR_ASSIGNMENT SLR2 $forward

  # These are the wide receive/transmit banks at the three physical seams.
  # All are already functional pipeline/FIFO state; the properties only make
  # their Laguna placement role explicit.
  set forward_relay_registers [get_cells -quiet -hierarchical \
    -filter "NAME =~ ${forward_input_boundary}/outputPayload_payload/* && IS_SEQUENTIAL"]
  set forward_output_registers [get_cells -quiet -hierarchical \
    -filter "NAME =~ ${pending_requests}/inputBoundary* && IS_SEQUENTIAL"]
  set inverse_sll_registers [get_cells -quiet -hierarchical \
    -filter "NAME =~ ${inverse_boundary}/tail_input_* && IS_SEQUENTIAL"]
  if {[llength $forward_relay_registers] < 7680 || \
      [llength $forward_output_registers] < 7680 || \
      [llength $inverse_sll_registers] < 3840} {
    error "missing whole-forward SLL banks: coefficientRelay=[llength $forward_relay_registers] forwardOutput=[llength $forward_output_registers] inverse=[llength $inverse_sll_registers]"
  }
  set_property USER_SLL_REG TRUE \
    [concat $forward_relay_registers $forward_output_registers \
      $inverse_sll_registers]
  puts "FPT_HARD_FLOORPLAN mode=whole_forward coefficients+inverse=SLR0 external+relays=SLR1 forward=SLR2 coefficientRelay=[llength $forward_relay_registers] forwardOutput=[llength $forward_output_registers] inverseBoundary=[llength $inverse_sll_registers]"
  return
}

fpt_hard_pblock fpt_inverse_slr0 SLR0 $inverse

set forward_root ${cmux}/forward/core/backend/generated
set forward_front [get_cells -quiet ${forward_root}/front]
set forward_back [get_cells -quiet ${forward_root}/back]
set forward_boundary [get_cells -quiet ${forward_root}/boundary]
if {[llength $forward_front] == 1 && [llength $forward_back] == 1} {
  set forward_partition_tx [get_cells -quiet -hierarchical \
    -filter "NAME =~ ${forward_boundary}/tx_*"]
  set forward_partition_rx [get_cells -quiet -hierarchical \
    -filter "NAME =~ ${forward_boundary}/rx_*"]
  set forward_partition_control [get_cells -quiet -hierarchical \
    -filter "NAME =~ ${forward_boundary}/back_token_delay*"]
  if {[llength $forward_partition_tx] == 0 || \
      [llength $forward_partition_rx] == 0} {
    error "missing partition SLL registers: tx=[llength $forward_partition_tx] rx=[llength $forward_partition_rx]"
  }
  fpt_hard_pblock fpt_forward_coefficients_slr2 SLR2 \
    [concat $forward_front $coefficients $forward_partition_tx]
  fpt_hard_pblock fpt_forward_external_slr1 SLR1 \
    [concat $forward_back $external $forward_partition_rx \
      $forward_partition_control]
  set_property USER_SLR_ASSIGNMENT SLR2 \
    [concat $forward_front $coefficients $forward_partition_tx]
  set_property USER_SLR_ASSIGNMENT SLR1 \
    [concat $forward_back $external $forward_partition_rx \
      $forward_partition_control]
  set_property USER_SLL_REG TRUE \
    [concat $forward_partition_tx $forward_partition_rx]
  puts "FPT_HARD_FLOORPLAN forwardFront+coefficients=SLR2; forwardBack+external=SLR1; inverse=SLR0; boundaryTX=[llength $forward_partition_tx]; boundaryRX=[llength $forward_partition_rx]"
} else {
  fpt_hard_pblock fpt_forward_slr2 SLR2 $forward
  puts "FPT_HARD_FLOORPLAN monolithic-forward=SLR2; inverse=SLR0"
}

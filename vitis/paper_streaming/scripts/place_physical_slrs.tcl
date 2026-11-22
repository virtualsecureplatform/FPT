# Three-SLR floorplan for the PHYSICAL=1 kernel. The DSP budget fixes the
# grouping: forward stages (2250) plus 45 lanes of MAC component 0 (270; SLR1 has only 2619 usable DSP sites), the
# rest of the MAC (2034), inverse (1256). The forward twist is a block-ROM lookup on the 8-bit digits (no DSP) and
# sits with the forward stages, so SLR0->SLR1 carries digits (2048 bits), not the twisted lanes (8192 bits). The forward stages sit in the middle SLR so that only the
# narrowest edge (MAC rows to the inverse transform) spans two boundaries.
# Every wide crossing has registered pipeline stages assigned to both sides.
# Uses only the existing platform pblocks.
if {![info exists ::env(FPT_FLOORPLAN_DIAGNOSTICS_PREFIX)]} {
  error "FPT_FLOORPLAN_DIAGNOSTICS_PREFIX is required"
}
set paper_floorplan_prefix $::env(FPT_FLOORPLAN_DIAGNOSTICS_PREFIX)
set kernels [get_cells -hierarchical -quiet -filter {REF_NAME == FptPaperStreamingKernel || ORIG_REF_NAME == FptPaperStreamingKernel}]
if {[llength $kernels] == 1} {
  set paper_root "[get_property NAME $kernels]/"
} elseif {[llength $kernels] == 0 && [get_property TOP [current_design]] eq "FptPaperStreamingKernel"} {
  set paper_root ""
} else {
  error "cannot identify a unique complete streaming kernel for floorplanning"
}
set paper_engine "${paper_root}engine"
set paper_raw "${paper_engine}/engine"
set paper_keyed "${paper_raw}/core"
set paper_blind "${paper_keyed}/core/core"
set paper_arithmetic "${paper_blind}/arithmetic"
set paper_forward "${paper_arithmetic}/forward_fft"
# {slr required hierarchical-cell}
set paper_cells [list \
  [list 1 1 "${paper_forward}/stages\[0\].fft_stage"] \
  [list 1 1 "${paper_forward}/stages\[1\].fft_stage"] \
  [list 1 1 "${paper_forward}/stages\[2\].fft_stage"] \
  [list 1 1 "${paper_forward}/stages\[3\].fft_stage"] \
  [list 1 1 "${paper_forward}/gather_last_stage"] \
  [list 1 1 "${paper_forward}/stages\[0\].output_pipeline"] \
  [list 1 1 "${paper_forward}/stages\[1\].output_pipeline"] \
  [list 1 1 "${paper_forward}/gather_input_pipeline"] \
  [list 1 1 "${paper_forward}/gather_output_pipeline"] \
  [list 1 1 "${paper_forward}/forward_twist.twist"] \
  [list 0 1 "${paper_blind}/frontend"] \
  [list 0 1 "${paper_blind}/mixed_state.store"] \
  [list 0 1 "${paper_arithmetic}/inverse_fft"] \
  [list 0 1 "${paper_engine}/sequencer"] \
  [list 2 1 "${paper_arithmetic}/mac"] \
  [list 2 1 "${paper_keyed}/cache"] \
  [list 0 1 "${paper_arithmetic}/digit_pipeline/stages\[0\].bank"] \
  [list 1 1 "${paper_arithmetic}/digit_pipeline/stages\[1\].bank"] \
  [list 1 1 "${paper_arithmetic}/spectrum_pipeline/stages\[0\].bank"] \
  [list 2 1 "${paper_arithmetic}/spectrum_pipeline/stages\[1\].bank"] \
  [list 1 1 "${paper_arithmetic}/mac/near_group.near"] \
  [list 1 1 "${paper_arithmetic}/split_output.spectrum_pipeline"] \
  [list 2 1 "${paper_arithmetic}/split_output.far_pipeline/stages\[0\].bank"] \
  [list 1 1 "${paper_arithmetic}/split_output.far_pipeline/stages\[1\].bank"] \
  [list 1 1 "${paper_arithmetic}/split_output.near_pipeline/stages\[0\].bank"] \
  [list 0 1 "${paper_arithmetic}/split_output.near_pipeline/stages\[1\].bank"]]
foreach port {0 1 2 3} {
  foreach {stage slr} {0 0 1 1 2 1 3 1} {
    lappend paper_cells [list $slr 1 "${paper_engine}/key_slices\[$port\].slices/slices\[$stage\].slice"]
  }
}
set paper_assignments {}
foreach entry $paper_cells {
  lassign $entry slr required path
  set block [get_pblocks -quiet pblock_dynamic_SLR$slr]
  if {[llength $block] != 1} {error "missing original platform SLR$slr pblock"}
  set cell [get_cells -hierarchical -quiet -filter "NAME == \"$path\""]
  if {[llength $cell] != 1 || [get_property IS_BLACKBOX $cell]} {
    if {$required} {error "missing or unresolved floorplan hierarchy $path"}
    puts "PAPER_FLOORPLAN_WARNING: optional hierarchy not found: $path"
    continue
  }
  lappend paper_assignments [list $slr $block $cell $path]
}
set paper_output [open ${paper_floorplan_prefix}_assignments.tsv w]
puts $paper_output "slr\tcell"
foreach assignment $paper_assignments {
  lassign $assignment slr block cell path
  add_cells_to_pblock $block $cell
  puts $paper_output "$slr\t$path"
}
# Register groups that are generate-block prefixes rather than hierarchy.
proc paper_assign_registers {output slr prefix minimum} {
  set cells [get_cells -hierarchical -quiet \
    -filter "IS_PRIMITIVE == 1 && REF_NAME =~ FD* && NAME =~ ${prefix}*"]
  if {[llength $cells] < $minimum} {
    error "expected at least $minimum registers under $prefix, found [llength $cells]"
  }
  add_cells_to_pblock [get_pblocks pblock_dynamic_SLR$slr] $cells
  puts $output "$slr\t${prefix}* ([llength $cells] registers)"
}
# Existing spectrum alignment bank beside the MAC.
paper_assign_registers $paper_output 2 "${paper_arithmetic}/synchronous_key" 8000
# Accepted launch and preparation-fault observation beside the compute core.
paper_assign_registers $paper_output 0 "${paper_keyed}/accepted_launch_pipeline." 2
paper_assign_registers $paper_output 0 "${paper_raw}/registered_preparation_fault." 1
# Cache fault/window status: launch register beside the cache, capture beside control.
paper_assign_registers $paper_output 2 "${paper_keyed}/cache_status_crossing.fault_near" 1
paper_assign_registers $paper_output 2 "${paper_keyed}/cache_status_crossing.window_near" 1
paper_assign_registers $paper_output 0 "${paper_keyed}/cache_status_crossing.fault_far" 1
paper_assign_registers $paper_output 0 "${paper_keyed}/cache_status_crossing.protocol_far" 1
# Registered fault tree: control levels beside the sequencer, arithmetic level in the middle.
paper_assign_registers $paper_output 0 "${paper_raw}/fault_register/" 1
paper_assign_registers $paper_output 0 "${paper_keyed}/fault_register/" 1
paper_assign_registers $paper_output 0 "${paper_keyed}/core/fault_register/" 1
paper_assign_registers $paper_output 0 "${paper_keyed}/core/core/fault_register/" 1
paper_assign_registers $paper_output 1 "${paper_arithmetic}/fault_register/" 1
# Key groups 0..4 hold exactly the keys of the near MAC group (component 0,
# lanes 0..44): their block RAMs and private command/write registers follow it.
set paper_near_cache {}
set paper_near_rams 0
foreach cell [get_cells -hierarchical -quiet -filter "IS_PRIMITIVE == 1 && NAME =~ ${paper_keyed}/cache/*"] {
  set name [get_property NAME $cell]
  # Only state follows the group; shared decode logic stays with the cache.
  if {![string match FD* [get_property REF_NAME $cell]] && ![string match RAMB* [get_property REF_NAME $cell]]} {continue}
  if {[regexp {/cache/groups\[[0-4]\]\.} $name] || [regexp {/cache/ports\[[0-3]\]\.shared\.data_(first|near)_reg} $name]} {
    lappend paper_near_cache $cell
    if {[string match RAMB* [get_property REF_NAME $cell]]} {incr paper_near_rams}
  }
}
if {$paper_near_rams != 35} {
  error "expected 35 block RAMs in key groups 0..4, found $paper_near_rams"
}
if {[llength $paper_near_cache] < 35 + 5*504 + 8*504} {
  error "key groups 0..4 and the shared loader stages are incomplete: [llength $paper_near_cache] cells"
}
add_cells_to_pblock [get_pblocks pblock_dynamic_SLR1] $paper_near_cache
puts $paper_output "1\t${paper_keyed}/cache/groups\[0..4\].* ([llength $paper_near_cache] cells, $paper_near_rams block RAMs)"
close $paper_output
set paper_launch [get_cells -hierarchical -quiet \
  -filter "IS_PRIMITIVE == 1 && REF_NAME =~ FD* && NAME =~ ${paper_keyed}/launch_pipeline.*"]
if {[llength $paper_launch] != 3} {
  error "expected three registered launch-credit bits, found [llength $paper_launch]"
}
foreach cell $paper_launch {
  add_cells_to_pblock [get_pblocks pblock_dynamic_SLR0] $cell
}
foreach slr {0 1 2} {
  report_utilization -pblocks [get_pblocks pblock_dynamic_SLR$slr] \
    -file ${paper_floorplan_prefix}_slr${slr}_pblock_utilization.rpt
}
write_checkpoint -force ${paper_floorplan_prefix}_pre_place.dcp
puts "PAPER_PHYSICAL_SLR_ASSIGNMENT: frontend/state/inverse=SLR0; ROM twist + forward stages + near MAC group + key groups 0..4=SLR1; MAC/key cache=SLR2; registered crossing banks on both sides of each boundary"

# The platform's HBM register slices cross SLRs on the 450 MHz hbm_aclk with
# almost no slack once the kernel takes most SLLs. Place them with extra setup
# margin; route_platform_margin.tcl lowers it before routing and the post-route
# gate removes it, so sign-off uses the platform's own constraints.
if {[llength [get_clocks -quiet hbm_aclk]]} {
  set_clock_uncertainty -setup 0.150 [get_clocks hbm_aclk]
  puts "PAPER_PLATFORM_MARGIN: hbm_aclk setup uncertainty 0.150 ns for placement"
}

# Preserve failed placement for diagnosis. Keep the original command's return
# code and options, including errors; this wrapper cannot admit a failed run.
if {[llength [info commands ::paper_original_place_design]]} {
  error "placement diagnostic wrapper already installed"
}
rename ::place_design ::paper_original_place_design
proc ::place_design {args} {
  set code [catch {uplevel 1 [linsert $args 0 ::paper_original_place_design]} message options]
  set prefix $::env(FPT_FLOORPLAN_DIAGNOSTICS_PREFIX)
  set state [expr {$code == 0 ? "placed" : "place_failed"}]
  foreach command [list \
      [list write_checkpoint -force ${prefix}_${state}.dcp] \
      [list report_utilization -slr -file ${prefix}_${state}_slr.rpt] \
      [list report_timing_summary -max_paths 20 -file ${prefix}_${state}_timing.rpt] \
      [list report_design_analysis -congestion -file ${prefix}_${state}_congestion.rpt]] {
    if {[catch {uplevel #0 $command} diagnostic]} {
      puts "PAPER_PLACEMENT_DIAGNOSTIC_ERROR: $diagnostic"
    }
  }
  puts "PAPER_PLACEMENT_RESULT: original_return_code=$code state=$state"
  return -options $options $message
}

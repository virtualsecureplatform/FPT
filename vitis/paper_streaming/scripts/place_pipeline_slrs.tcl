# Keep all four FFT stages together in the middle SLR, adjacent to twist and MAC.
# Use only
# existing platform pblocks: do not change their ranges, routing or DRC rules.
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
set paper_blind "${paper_root}engine/engine/core/core/core"
set paper_arithmetic "${paper_blind}/arithmetic"
set paper_forward "${paper_arithmetic}/forward_fft"
set paper_groups [dict create \
  1 [list "${paper_forward}/stages\[0\].fft_stage" \
      "${paper_forward}/stages\[1\].fft_stage" \
      "${paper_forward}/stages\[2\].fft_stage" \
      "${paper_forward}/stages\[3\].fft_stage" \
      "${paper_forward}/gather_last_stage"] \
  0 [list "${paper_forward}/forward_twist.twist" \
      "${paper_blind}/frontend" "${paper_blind}/mixed_state.store" \
      "${paper_arithmetic}/inverse_fft" "${paper_root}engine/sequencer"] \
  2 [list "${paper_arithmetic}/mac" "${paper_root}engine/engine/core/cache"]]

# Resolve every required hierarchy before applying any placement constraint.
set paper_assignments {}
dict for {slr paths} $paper_groups {
  set block [get_pblocks -quiet pblock_dynamic_SLR$slr]
  if {[llength $block] != 1} {error "missing original platform SLR$slr pblock"}
  foreach path $paths {
    set cell [get_cells -hierarchical -quiet -filter "NAME == \"$path\""]
    if {[llength $cell] != 1 || [get_property IS_BLACKBOX $cell]} {
      error "missing or unresolved floorplan hierarchy $path"
    }
    lappend paper_assignments [list $slr $block $cell $path]
  }
}
set paper_alignment_prefix "${paper_arithmetic}/synchronous_key"
set paper_alignment [get_cells -hierarchical -quiet \
  -filter "IS_PRIMITIVE == 1 && NAME =~ ${paper_alignment_prefix}*"]
# This existing bank aligns the 128 complex FFT lanes to the synchronous key
# read: 8192 payload bits, four context bits, one valid bit. Leaving it free
# permits a wide two-boundary detour between the final FFT and MAC.
if {[llength $paper_alignment] != 8197} {
  error "expected all8197 existing spectrum alignment registers, found [llength $paper_alignment]"
}
foreach type [lsort -unique [get_property REF_NAME $paper_alignment]] {
  if {![string match FD* $type]} {error "alignment selection includes non-register $type"}
}
set paper_output [open ${paper_floorplan_prefix}_assignments.tsv w]
puts $paper_output "slr\tcell"
foreach assignment $paper_assignments {
  lassign $assignment slr block cell path
  add_cells_to_pblock $block $cell
  puts $paper_output "$slr\t$path"
}
close $paper_output
add_cells_to_pblock [get_pblocks pblock_dynamic_SLR2] $paper_alignment
set paper_output [open ${paper_floorplan_prefix}_alignment_registers.tsv w]
puts $paper_output "slr\tcell"
foreach name [get_property NAME $paper_alignment] {puts $paper_output "2\t$name"}
close $paper_output
# Vivado preserves the generate-block name as a dotted leaf prefix, not
# a hierarchy separator. Select only flip-flops, excluding its ready LUTs.
set paper_launch_prefix "${paper_root}engine/engine/core/launch_pipeline."
set paper_launch [get_cells -hierarchical -quiet \
  -filter "IS_PRIMITIVE == 1 && REF_NAME =~ FD* && NAME =~ ${paper_launch_prefix}*"]
if {[llength $paper_launch] != 3} {
  error "expected three registered launch-credit bits, found [llength $paper_launch]"
}
set paper_output [open ${paper_floorplan_prefix}_launch_registers.tsv w]
puts $paper_output "slr\tcell"
foreach cell $paper_launch {
  if {![string match FD* [get_property REF_NAME $cell]]} {error "launch selection includes non-register $cell"}
  set slr [expr {[string match *cache_ready* [get_property NAME $cell]] ? 2 : 0}]
  add_cells_to_pblock [get_pblocks pblock_dynamic_SLR$slr] $cell
  puts $paper_output "$slr\t[get_property NAME $cell]"
}
close $paper_output
# The accepted request and bank terminate the cache-qualified launch path
# beside the buffered compute core. Keep this new boundary in SLR0.
set paper_accepted_prefix "${paper_root}engine/engine/core/accepted_launch_pipeline."
set paper_accepted [get_cells -hierarchical -quiet \
  -filter "IS_PRIMITIVE == 1 && REF_NAME =~ FD* && NAME =~ ${paper_accepted_prefix}*"]
if {[llength $paper_accepted] != 2} {
  error "expected two accepted-launch register bits, found [llength $paper_accepted]"
}
set paper_output [open ${paper_floorplan_prefix}_accepted_launch_registers.tsv w]
puts $paper_output "slr\tcell"
foreach cell $paper_accepted {
  add_cells_to_pblock [get_pblocks pblock_dynamic_SLR0] $cell
  puts $paper_output "0\t[get_property NAME $cell]"
}
close $paper_output
# Terminate the aggregate arithmetic-fault route beside input preparation.
set paper_prep_fault_prefix "${paper_root}engine/engine/registered_preparation_fault."
set paper_prep_fault [get_cells -hierarchical -quiet \
  -filter "IS_PRIMITIVE == 1 && REF_NAME =~ FD* && NAME =~ ${paper_prep_fault_prefix}*"]
if {[llength $paper_prep_fault] != 1} {
  error "expected one preparation-fault register, found [llength $paper_prep_fault]"
}
set paper_output [open ${paper_floorplan_prefix}_preparation_fault_registers.tsv w]
puts $paper_output "slr\tcell"
foreach cell $paper_prep_fault {
  add_cells_to_pblock [get_pblocks pblock_dynamic_SLR0] $cell
  puts $paper_output "0\t[get_property NAME $cell]"
}
close $paper_output
foreach slr {0 1 2} {
  report_utilization -pblocks [get_pblocks pblock_dynamic_SLR$slr] \
    -file ${paper_floorplan_prefix}_slr${slr}_pblock_utilization.rpt
}
write_checkpoint -force ${paper_floorplan_prefix}_pre_place.dcp
puts "PAPER_PIPELINE_SLR_ASSIGNMENT: input twist/frontend/state/inverse and launch credit=SLR0; all forward FFT stages/gather=SLR1; MAC/key cache plus8197 existing alignment registers and cache credit=SLR2; arithmetic latency unchanged"

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
      [list report_timing_summary -max_paths 20 -file ${prefix}_${state}_timing.rpt]] {
    if {[catch {uplevel #0 $command} diagnostic]} {
      puts "PAPER_PLACEMENT_DIAGNOSTIC_ERROR: $diagnostic"
    }
  }
  puts "PAPER_PLACEMENT_RESULT: original_return_code=$code state=$state"
  return -options $options $message
}

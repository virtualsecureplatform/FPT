# Re-place and route a synthesized U280 Buffered Blind Rotate checkpoint.
# This companion to the full source flow makes congestion-oriented placement
# experiments reproducible without repeating multi-hour RTL synthesis.
#
# vivado -mode batch \
#   -source chisel/scripts/implement_buffered_barrel_blind_rotate_u280_checkpoint.tcl \
#   -tclargs POST_SYNTH_DCP OUTPUT_DIR 3.333 AltSpreadLogic_high 8 full 0

set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir u280_post_route_metrics.tcl]

set input_checkpoint [lindex $argv 0]
set output_dir [lindex $argv 1]
set clock_period [lindex $argv 2]
set place_directive [lindex $argv 3]
set jobs [lindex $argv 4]
set floorplan_mode [lindex $argv 5]
set force_high_fanout [lindex $argv 6]
set pre_route_phys_opt [lindex $argv 7]
if {$input_checkpoint eq "" || $output_dir eq ""} {
    error "usage: POST_SYNTH_DCP OUTPUT_DIR ?CLOCK_PERIOD_NS? ?PLACE_DIRECTIVE? ?JOBS? ?FLOORPLAN_MODE? ?FORCE_MODE_0_NONE_1_ALL_2_ADDRESS_3_ADDRESS_AND_PENDING? ?PRE_ROUTE_PHYS_OPT_AGGRESSIVE_OR_NONE?"
}
if {$clock_period eq ""} { set clock_period 3.333 }
if {$place_directive eq ""} { set place_directive AltSpreadLogic_high }
if {$jobs eq ""} { set jobs 8 }
if {$floorplan_mode eq ""} { set floorplan_mode full }
if {$force_high_fanout eq ""} { set force_high_fanout 0 }
if {$pre_route_phys_opt eq ""} { set pre_route_phys_opt aggressive }
if {![string is double -strict $clock_period] || $clock_period <= 0} {
    error "CLOCK_PERIOD_NS must be positive: $clock_period"
}
if {![string is integer -strict $jobs] || $jobs < 1} {
    error "JOBS must be a positive integer: $jobs"
}
if {$floorplan_mode ni {full transforms-only}} {
    error "FLOORPLAN_MODE must be full or transforms-only: $floorplan_mode"
}
if {![string is integer -strict $force_high_fanout] ||
    $force_high_fanout ni {0 1 2 3}} {
    error "FORCE_MODE must be 0 (none), 1 (all), 2 (output address only), or 3 (output address and local pending-queue shift): $force_high_fanout"
}
if {$pre_route_phys_opt ni {aggressive none}} {
    error "PRE_ROUTE_PHYS_OPT must be aggressive or none: $pre_route_phys_opt"
}

set top BufferedBlindRotateAccelerator
set clock_port clock
set clock_root BUFGCE_X0Y144
set input_checkpoint [file normalize $input_checkpoint]
set output_dir [file normalize $output_dir]
if {![file isfile $input_checkpoint]} {
    error "Synthesized checkpoint not found: $input_checkpoint"
}
file mkdir $output_dir
set_param general.maxThreads $jobs

open_checkpoint $input_checkpoint
fpt_require_u280_clock $clock_period $clock_port
if {[llength [get_sites -quiet $clock_root]] != 1} {
    error "Clock-root site $clock_root is not present on the checkpoint part"
}
set_property HD.CLK_SRC $clock_root [get_ports $clock_port]

set floorplan_cells [dict create \
    forward {engine/blindRotate/blindRotate/cmux/forward} \
    coefficients {engine/blindRotate/blindRotate/cmux/coefficients} \
    external {engine/blindRotate/blindRotate/cmux/external} \
    external_output {engine/blindRotate/blindRotate/cmux/externalOutputPipeline} \
    pending_requests {engine/blindRotate/blindRotate/cmux/pendingRequests} \
    forward_tags {engine/blindRotate/blindRotate/cmux/forwardTags} \
    inverse_boundary {engine/blindRotate/blindRotate/cmux/inverseBoundary} \
    inverse_tags {engine/blindRotate/blindRotate/cmux/inverseTags} \
    key_buffer {engine/keyBuffer} \
    key_requests {engine/keyReadRequests} \
    sample_extract {engine/blindRotate/sampleExtract} \
    inverse {engine/blindRotate/blindRotate/cmux/inverse}]
set resolved_cells [dict create]
dict for {name path} $floorplan_cells {
    set cells [get_cells -quiet $path]
    if {$name eq "external_output" && [llength $cells] == 0} {
        continue
    }
    if {[llength $cells] != 1} {
        error "Expected one $name hierarchy at $path, found [llength $cells]"
    }
    dict set resolved_cells $name [lindex $cells 0]
}

create_pblock pb_forward
resize_pblock pb_forward -add CLOCKREGION_X0Y0:CLOCKREGION_X7Y3
add_cells_to_pblock pb_forward [dict get $resolved_cells forward]

if {$floorplan_mode eq "full"} {
    create_pblock pb_middle
    resize_pblock pb_middle -add CLOCKREGION_X0Y4:CLOCKREGION_X7Y7
    set middle_cells [list \
        [dict get $resolved_cells coefficients] \
        [dict get $resolved_cells external] \
        [dict get $resolved_cells pending_requests] \
        [dict get $resolved_cells forward_tags] \
        [dict get $resolved_cells inverse_boundary] \
        [dict get $resolved_cells inverse_tags] \
        [dict get $resolved_cells key_buffer] \
        [dict get $resolved_cells key_requests] \
        [dict get $resolved_cells sample_extract]]
    if {[dict exists $resolved_cells external_output]} {
        lappend middle_cells [dict get $resolved_cells external_output]
    }
    add_cells_to_pblock pb_middle $middle_cells
}

create_pblock pb_inverse
resize_pblock pb_inverse -add CLOCKREGION_X0Y8:CLOCKREGION_X7Y11
add_cells_to_pblock pb_inverse [dict get $resolved_cells inverse]

# Keep the tag FIFOs with the middle-SLR control and coefficient memories,
# rather than with either transform.  Their enqueue/dequeue payloads are only
# a few bits wide, while pinning the FIFO pointers in an outer SLR creates a
# long decoded BRAM-address path at the consuming side.  In transforms-only
# experiments they remain unconstrained so placement can make the same local
# choice from timing.

opt_design -directive ExploreWithRemap

# Guide the elastic forward-crossing payload and its valid bit into the U280's
# dedicated Laguna SLL registers. Vivado ignores USER_SLL_REG when the path
# does not actually cross an SLR, so this remains safe for unconstrained
# placement experiments where the pending queue stays beside the transform.
set guided_sll_registers [get_cells -hierarchical -quiet \
    -regexp {^.*/pendingRequests/(inputBoundary_.*_reg.*|inputBoundaryValid_reg.*)$}]
if {[llength $guided_sll_registers] > 0} {
    set_property USER_SLL_REG TRUE $guided_sll_registers
}
puts "FPT_GUIDED_SLL_REGISTERS count=[llength $guided_sll_registers]"

set forced_high_fanout_nets {}
if {$force_high_fanout} {
    # The two output-address decode nets each drive all 214 UltraRAM slices
    # of the wide accumulator bank. They are local, one-way address controls,
    # so placement-local replication cannot create the ready/CE round trip
    # avoided at the elastic SLR boundary.
    set external_output_address_nets [get_nets -hierarchical -quiet \
        -regexp {^.*/external/accumulatorMemories_0_ext/Memory_reg_uram_0_i_[12]_n_0$}]
    if {[llength $external_output_address_nets] != 2} {
        error "Expected 2 External Product output-address nets, found [llength $external_output_address_nets]"
    }

    set coefficient_write_enable_nets {}
    set sample_extract_fanout_nets {}
    set pending_request_enable_nets {}
    if {$force_high_fanout == 1} {
        # Full mode also targets the decoded prefetch enables and the
        # sample-extraction distributed-memory port. The narrower modes omit
        # them because forcing these controls encouraged BUFG insertion and
        # degraded the v44 placement estimate.
        set coefficient_write_enable_nets [get_nets -hierarchical -quiet \
            -regexp {^.*/coefficients/buffers_[01]_0_0_[0-7]_00$}]
        if {[llength $coefficient_write_enable_nets] != 16} {
            error "Expected 16 coefficient prefetch enable nets, found [llength $coefficient_write_enable_nets]"
        }
        set sample_extract_beat_nets [get_nets -hierarchical -quiet \
            -regexp {^.*/sampleExtract/inputBeat_reg\[[0-3]\]$}]
        if {[llength $sample_extract_beat_nets] != 4} {
            error "Expected 4 sample-extraction input-beat nets, found [llength $sample_extract_beat_nets]"
        }
        # Depending on optimization details, Vivado may expose the memory
        # enable only at the SampleExtract level or as both parent and child
        # net segments. Prefer the child and fall back to the parent name.
        set sample_extract_memory_nets [get_nets -hierarchical -quiet \
            -regexp {^.*/sampleExtract/maskMemory_ext/E\[0\]$}]
        if {[llength $sample_extract_memory_nets] == 0} {
            set sample_extract_memory_nets [get_nets -hierarchical -quiet \
                -regexp {^.*/sampleExtract/E\[0\]$}]
        }
        if {[llength $sample_extract_memory_nets] != 1} {
            error "Expected 1 sample-extraction memory-enable net, found [llength $sample_extract_memory_nets]"
        }
        set sample_extract_fanout_nets [concat \
            $sample_extract_beat_nets $sample_extract_memory_nets]
    }
    if {$force_high_fanout == 1 || $force_high_fanout == 3} {
        # The pending queue makes this 7,688-load SRL clock enable a function
        # of only registered occupancy and the input-boundary valid bit. It can
        # therefore be replicated without recreating the downstream-ready
        # round trip that dominated all 100 v48 placement paths. Mode 3
        # selects just this proven target plus the UltraRAM address controls.
        set pending_request_enable_nets [get_nets -hierarchical -quiet \
            -regexp {^.*/pendingRequests/enqFire$}]
        if {[llength $pending_request_enable_nets] != 1} {
            error "Expected 1 pending-request enable net, found [llength $pending_request_enable_nets]"
        }
    }

    set forced_high_fanout_nets [concat \
        $coefficient_write_enable_nets $sample_extract_fanout_nets \
        $pending_request_enable_nets $external_output_address_nets]
    if {$force_high_fanout == 1} {
        set_property FORCE_MAX_FANOUT 128 $forced_high_fanout_nets
    } elseif {$force_high_fanout == 3} {
        set_property FORCE_MAX_FANOUT 128 $pending_request_enable_nets
    }
    set_property FORCE_MAX_FANOUT 32 $external_output_address_nets
    puts "FPT_FORCE_HIGH_FANOUT mode=$force_high_fanout coefficient_nets=[llength $coefficient_write_enable_nets] sample_extract_nets=[llength $sample_extract_fanout_nets] pending_request_nets=[llength $pending_request_enable_nets] external_output_address_nets=2 pending_boundary_nets=0 max_fanout=128 external_address_max_fanout=32"
}
place_design -directive $place_directive -ultrathreads
if {$force_high_fanout && [info exists forced_high_fanout_nets] &&
    [llength $forced_high_fanout_nets] > 0} {
    # The in-placer VHFN pass can still skip a forced net when only a subset
    # of its loads is timing-critical. Run the explicit physical-synthesis
    # command as well so every selected control is replicated regardless of
    # its provisional slack.
    phys_opt_design -force_replication_on_nets $forced_high_fanout_nets
}
write_checkpoint -force [file join $output_dir raw_place.dcp]
report_design_analysis -congestion \
    -file [file join $output_dir raw_place_congestion.rpt]
report_timing_summary \
    -file [file join $output_dir raw_place_timing_summary.rpt]
report_timing -delay_type max -max_paths 100 -nworst 1 \
    -file [file join $output_dir raw_place_timing_paths.rpt]
set placed_paths [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
set placed_wns NA
if {[llength $placed_paths] > 0} {
    set placed_wns [format %.3f \
        [get_property SLACK [lindex $placed_paths 0]]]
}
puts "FPT_CHECKPOINT_PLACE directive=$place_directive floorplan=$floorplan_mode force_high_fanout=$force_high_fanout wns_ns=$placed_wns"

# Run the normal post-placement physical optimization before asking the
# router to absorb the remaining delay.  On this dense three-SLR design the
# placer estimate alone leaves replication, critical-cell movement, and
# high-fanout restructuring on paths that already consume most of a 3.333 ns
# cycle.  Preserve both reports so placement experiments can distinguish the
# directive's raw result from the netlist actually handed to routing.
if {$pre_route_phys_opt eq "aggressive"} {
    phys_opt_design -directive AggressiveExplore
    phys_opt_design -slr_crossing_opt
    write_checkpoint -force [file join $output_dir post_place.dcp]
    report_design_analysis -congestion \
        -file [file join $output_dir post_place_congestion.rpt]
    report_timing_summary \
        -file [file join $output_dir post_place_timing_summary.rpt]
    report_timing -delay_type max -max_paths 100 -nworst 1 \
        -file [file join $output_dir post_place_timing_paths.rpt]
    set post_place_paths [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
    set post_place_wns NA
    if {[llength $post_place_paths] > 0} {
        set post_place_wns [format %.3f \
            [get_property SLACK [lindex $post_place_paths 0]]]
    }
    puts "FPT_CHECKPOINT_PHYSOPT directive=AggressiveExplore/slr_crossing_opt wns_ns=$post_place_wns"
} else {
    puts "FPT_CHECKPOINT_PHYSOPT directive=none wns_ns=$placed_wns"
}

route_design -directive Explore
set initially_routed [report_route_status -boolean_check ROUTED_FULLY]
report_route_status \
    -file [file join $output_dir initial_route_status.rpt]
report_timing_summary \
    -file [file join $output_dir initial_route_timing_summary.rpt]
set initial_paths [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
set initial_wns NA
if {[llength $initial_paths] > 0} {
    set initial_wns [format %.3f \
        [get_property SLACK [lindex $initial_paths 0]]]
}
puts "FPT_CHECKPOINT_INITIAL_ROUTE routed=$initially_routed wns_ns=$initial_wns"

if {$initially_routed && $initial_wns ne "NA" && $initial_wns < 0} {
    phys_opt_design -directive AggressiveExplore
    phys_opt_design -slr_crossing_opt
    route_design -directive AggressiveExplore
} elseif {!$initially_routed} {
    route_design -directive AggressiveExplore
}

report_utilization -hierarchical \
    -file [file join $output_dir utilization.rpt]
report_timing_summary -file [file join $output_dir timing_summary.rpt]
report_timing -delay_type max -max_paths 20 -nworst 1 \
    -file [file join $output_dir timing_paths.rpt]
report_route_status -file [file join $output_dir route_status.rpt]
report_design_analysis -congestion \
    -file [file join $output_dir congestion.rpt]
set drc_name fpt_checkpoint_post_route_drc
report_drc -name $drc_name -file [file join $output_dir drc.rpt]
write_checkpoint -force [file join $output_dir $top.dcp]

set metrics [fpt_collect_u280_metrics \
    $clock_period $clock_port $drc_name]
fpt_write_u280_metrics [file join $output_dir metrics.tsv] [list \
    top $top \
    source_checkpoint $input_checkpoint \
    clock_period_ns $clock_period \
    place_directive $place_directive \
    floorplan_mode $floorplan_mode \
    force_high_fanout $force_high_fanout \
    pre_route_phys_opt $pre_route_phys_opt \
    route_directive Explore/AggressiveExplore] $metrics

set wns [dict get $metrics wns_ns]
puts "FPT_CHECKPOINT_300MHZ_METRICS period_ns=$clock_period wns_ns=$wns achieved_mhz=[dict get $metrics achieved_mhz] routed=[dict get $metrics route_fully_routed] drc_errors=[dict get $metrics drc_error]"
fpt_require_clean_u280_route $metrics
if {$wns eq "NA"} {
    error "No setup timing path was available for 300 MHz acceptance"
}
if {$wns < 0} {
    error "Routed design misses $clock_period ns by [expr {-$wns}] ns"
}

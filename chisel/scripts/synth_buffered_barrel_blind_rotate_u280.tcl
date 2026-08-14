# Synthesize and post-route the paper-width, windowed-barrel Blind Rotate
# accelerator out of context on an Alveo U280.
#
# vivado -mode batch \
#   -source chisel/scripts/synth_buffered_barrel_blind_rotate_u280.tcl \
#   -tclargs \
#     build/chisel-paper-buffered-barrel-blind-rotate/BufferedBlindRotateAccelerator.sv \
#     build/sgen-fpt/forward.v build/sgen-fpt/inverse.v \
#     build/vivado-buffered-barrel-blind-rotate-300mhz 3.333 \
#     xcu280-fsvh2892-2L-e 8

set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir u280_post_route_metrics.tcl]

set accelerator_source [lindex $argv 0]
set forward_source [lindex $argv 1]
set inverse_source [lindex $argv 2]
set output_dir [lindex $argv 3]
set clock_period [lindex $argv 4]
set part [lindex $argv 5]
set jobs [lindex $argv 6]
set stop_after_synth [lindex $argv 7]
if {$accelerator_source eq "" || $forward_source eq "" || \
    $inverse_source eq ""} {
    error "usage: ACCELERATOR_SV FORWARD_V INVERSE_V ?OUTPUT_DIR? ?CLOCK_PERIOD_NS? ?PART? ?JOBS? ?STOP_AFTER_SYNTH?"
}
if {$output_dir eq ""} {
    set output_dir build/vivado-buffered-barrel-blind-rotate-300mhz
}
if {$clock_period eq ""} { set clock_period 3.333 }
if {$part eq ""} { set part xcu280-fsvh2892-2L-e }
if {$jobs eq ""} { set jobs 8 }
if {$stop_after_synth eq ""} { set stop_after_synth 0 }
if {![string is double -strict $clock_period] || $clock_period <= 0} {
    error "CLOCK_PERIOD_NS must be positive: $clock_period"
}
if {![string is integer -strict $jobs] || $jobs < 1} {
    error "JOBS must be a positive integer: $jobs"
}
if {$stop_after_synth ni {0 1}} {
    error "STOP_AFTER_SYNTH must be 0 or 1: $stop_after_synth"
}

set top BufferedBlindRotateAccelerator
set clock_port clock
set accelerator_source [file normalize $accelerator_source]
set forward_source [file normalize $forward_source]
set inverse_source [file normalize $inverse_source]
set output_dir [file normalize $output_dir]
foreach source [list \
    $accelerator_source $forward_source $inverse_source] {
    if {![file isfile $source]} { error "RTL source not found: $source" }
}
file mkdir $output_dir
set_param general.maxThreads $jobs

# Load the clock before synthesis so retiming and every implementation pass
# optimize against the same target used for final acceptance.
set clock_xdc [file join $output_dir clock.xdc]
set clock_file [open $clock_xdc w]
puts $clock_file \
    "create_clock -name ap_clk -period $clock_period \[get_ports $clock_port\]"
close $clock_file

read_verilog -sv $accelerator_source
read_verilog $forward_source
read_verilog $inverse_source
read_xdc $clock_xdc
synth_design -top $top -part $part -mode out_of_context \
    -flatten_hierarchy rebuilt -retiming
fpt_require_u280_clock $clock_period $clock_port
set accelerator_clock \
    [fpt_require_u280_related_slow_clock $clock_period]
set pending_fast_clock \
    [fpt_require_u280_related_fast_clock $clock_period]
set has_pending_fast_clock [expr {$pending_fast_clock ne ""}]

report_utilization -hierarchical \
    -file [file join $output_dir post_synth_utilization.rpt]
report_timing_summary \
    -file [file join $output_dir post_synth_timing_summary.rpt]
write_checkpoint -force [file join $output_dir post_synth.dcp]
if {$stop_after_synth} {
    puts "FPT_STOP_AFTER_SYNTH checkpoint=[file join $output_dir post_synth.dcp]"
    exit 0
}

# Keep each wide transform in one SLR and place the coefficient selector,
# External Product, key cache, and registered inter-SLR stream boundaries
# together in the middle SLR. The explicit FIFO hierarchies keep Vivado from
# folding their control cones back through either transform pblock.
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

# A gearbox checkpoint contains one slow-domain holding bank and two equal
# fast-domain banks. Keep the holding/launch side in SLR0, capture side in
# SLR1, and mark the pure launch-to-capture cut for Laguna SLL placement.
set pending_gearbox_tx_word_registers [get_cells -hierarchical -quiet \
    -regexp {^.*/pendingRequests/gearbox/txWord_reg.*$}]
set pending_gearbox_pre_launch_registers [get_cells -hierarchical -quiet \
    -regexp {^.*/pendingRequests/gearbox/preLaunch_reg.*$}]
set pending_gearbox_tx_launch_registers [get_cells -hierarchical -quiet \
    -regexp {^.*/pendingRequests/gearbox/txLaunch/value_reg.*$}]
set pending_gearbox_rx_capture_registers [get_cells -hierarchical -quiet \
    -regexp {^.*/pendingRequests/gearbox/rxCapture/value_reg.*$}]
set pending_gearbox_crossing_count \
    [llength $pending_gearbox_tx_launch_registers]
if {$has_pending_fast_clock} {
    if {[llength $pending_gearbox_tx_word_registers] < 7689} {
        error "Expected at least 7689 pending gearbox holding registers, found [llength $pending_gearbox_tx_word_registers]"
    }
    if {[llength $pending_gearbox_pre_launch_registers] != $pending_gearbox_crossing_count} {
        error "Expected $pending_gearbox_crossing_count pending gearbox pre-launch registers, found [llength $pending_gearbox_pre_launch_registers]"
    }
    if {$pending_gearbox_crossing_count < 3846 || \
        [llength $pending_gearbox_rx_capture_registers] != $pending_gearbox_crossing_count} {
        error "Unexpected pending gearbox SLL banks: tx=$pending_gearbox_crossing_count rx=[llength $pending_gearbox_rx_capture_registers]"
    }
    set_property USER_SLL_REG TRUE [concat \
        $pending_gearbox_tx_launch_registers \
        $pending_gearbox_rx_capture_registers]
}

create_pblock pb_forward
resize_pblock pb_forward -add CLOCKREGION_X0Y0:CLOCKREGION_X7Y3
add_cells_to_pblock pb_forward [dict get $resolved_cells forward]
if {$has_pending_fast_clock} {
    add_cells_to_pblock pb_forward [concat \
        $pending_gearbox_tx_word_registers \
        $pending_gearbox_pre_launch_registers \
        $pending_gearbox_tx_launch_registers]
    create_pblock pb_pending_gearbox_rx
    resize_pblock pb_pending_gearbox_rx -add \
        CLOCKREGION_X0Y4:CLOCKREGION_X7Y7
    add_cells_to_pblock pb_pending_gearbox_rx \
        $pending_gearbox_rx_capture_registers

    # Match each launch bit with both candidate source halves in the same
    # horizontal clock-region stripe. The first gearbox placement allowed the
    # holding bank to drift across SLR0; its worst 2 ns path ran from a remote
    # slice to a Laguna launch register and missed timing by 0.601 ns.
    for {set x 0} {$x < 8} {incr x} {
        set tx_stripe {}
        foreach cell $pending_gearbox_tx_word_registers {
            set cell_name [get_property NAME $cell]
            if {![regexp {\[([0-9]+)\]$} $cell_name -> bit]} {
                error "Unexpected pending gearbox holding-register name: $cell_name"
            }
            set lane [expr {$bit < 3845 ? $bit : $bit - 3845}]
            if {[expr {$lane % 8}] == $x} { lappend tx_stripe $cell }
        }
        foreach cell $pending_gearbox_tx_launch_registers {
            set cell_name [get_property NAME $cell]
            if {![regexp {\[([0-9]+)\]$} $cell_name -> bit]} {
                error "Unexpected pending gearbox launch-register name: $cell_name"
            }
            if {[expr {$bit % 8}] == $x} { lappend tx_stripe $cell }
        }
        foreach cell $pending_gearbox_pre_launch_registers {
            set cell_name [get_property NAME $cell]
            if {![regexp {\[([0-9]+)\]$} $cell_name -> bit]} {
                error "Unexpected pending gearbox pre-launch-register name: $cell_name"
            }
            if {[expr {$bit % 8}] == $x} { lappend tx_stripe $cell }
        }
        set rx_stripe {}
        foreach cell $pending_gearbox_rx_capture_registers {
            set cell_name [get_property NAME $cell]
            if {![regexp {\[([0-9]+)\]$} $cell_name -> bit]} {
                error "Unexpected pending gearbox capture-register name: $cell_name"
            }
            if {[expr {$bit % 8}] == $x} { lappend rx_stripe $cell }
        }

        create_pblock pb_pending_gearbox_tx_x${x}
        resize_pblock pb_pending_gearbox_tx_x${x} -add \
            CLOCKREGION_X${x}Y0:CLOCKREGION_X${x}Y3
        set_property PARENT [get_pblocks pb_forward] \
            [get_pblocks pb_pending_gearbox_tx_x${x}]
        add_cells_to_pblock pb_pending_gearbox_tx_x${x} $tx_stripe

        create_pblock pb_pending_gearbox_rx_x${x}
        resize_pblock pb_pending_gearbox_rx_x${x} -add \
            CLOCKREGION_X${x}Y4:CLOCKREGION_X${x}Y7
        set_property PARENT [get_pblocks pb_pending_gearbox_rx] \
            [get_pblocks pb_pending_gearbox_rx_x${x}]
        add_cells_to_pblock pb_pending_gearbox_rx_x${x} $rx_stripe
        puts "FPT_PENDING_GEARBOX_STRIPE x=$x tx=[llength $tx_stripe] rx=[llength $rx_stripe]"
    }
}

create_pblock pb_middle
resize_pblock pb_middle -add CLOCKREGION_X0Y4:CLOCKREGION_X7Y7
set middle_cells [list \
    [dict get $resolved_cells coefficients] \
    [dict get $resolved_cells external] \
    [dict get $resolved_cells forward_tags] \
    [dict get $resolved_cells inverse_boundary] \
    [dict get $resolved_cells inverse_tags] \
    [dict get $resolved_cells key_buffer] \
    [dict get $resolved_cells key_requests] \
    [dict get $resolved_cells sample_extract]]
if {!$has_pending_fast_clock} {
    lappend middle_cells [dict get $resolved_cells pending_requests]
}
if {[dict exists $resolved_cells external_output]} {
    lappend middle_cells [dict get $resolved_cells external_output]
}
add_cells_to_pblock pb_middle $middle_cells

create_pblock pb_inverse
resize_pblock pb_inverse -add CLOCKREGION_X0Y8:CLOCKREGION_X7Y11
add_cells_to_pblock pb_inverse [dict get $resolved_cells inverse]

opt_design -directive ExploreWithRemap
place_design -directive ExtraNetDelay_high
write_checkpoint -force [file join $output_dir raw_place.dcp]
report_design_analysis -congestion \
    -file [file join $output_dir raw_place_congestion.rpt]
report_timing_summary \
    -file [file join $output_dir raw_place_timing_summary.rpt]
report_timing -delay_type max -max_paths 100 -nworst 1 \
    -file [file join $output_dir raw_place_timing_paths.rpt]
set raw_place_paths [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
if {[llength $raw_place_paths] > 0} {
    puts "FPT_RAW_PLACE_WNS_NS [format %.3f [get_property SLACK [lindex $raw_place_paths 0]]]"
}
phys_opt_design -directive AggressiveExplore
write_checkpoint -force [file join $output_dir post_place.dcp]
report_design_analysis -congestion \
    -file [file join $output_dir post_place_congestion.rpt]
report_timing_summary \
    -file [file join $output_dir post_place_timing_summary.rpt]

route_design -directive AlternateCLBRouting
set initially_routed [report_route_status -boolean_check ROUTED_FULLY]
report_route_status \
    -file [file join $output_dir initial_route_status.rpt]
report_timing_summary \
    -file [file join $output_dir initial_route_timing_summary.rpt]
if {$initially_routed} {
    phys_opt_design -directive AggressiveExplore
}
route_design -directive AggressiveExplore

report_utilization -hierarchical \
    -file [file join $output_dir utilization.rpt]
report_timing_summary \
    -file [file join $output_dir timing_summary.rpt]
report_timing -delay_type max -max_paths 20 -nworst 1 \
    -file [file join $output_dir timing_paths.rpt]
report_route_status -file [file join $output_dir route_status.rpt]
report_design_analysis -congestion \
    -file [file join $output_dir congestion.rpt]
set drc_name fpt_post_route_drc
report_drc -name $drc_name -file [file join $output_dir drc.rpt]
report_power -file [file join $output_dir power.rpt]
write_checkpoint -force [file join $output_dir $top.dcp]

set metrics [fpt_collect_u280_metrics \
    $clock_period $clock_port $drc_name]
fpt_write_u280_metrics [file join $output_dir metrics.tsv] [list \
    top $top \
    clock_port $clock_port \
    accelerator_clock_name $accelerator_clock \
    pending_fast_clock $has_pending_fast_clock \
    pending_fast_clock_name $pending_fast_clock \
    pending_gearbox_crossing_bits $pending_gearbox_crossing_count \
    part $part \
    clock_period_ns $clock_period \
    opt_directive ExploreWithRemap \
    place_directive ExtraNetDelay_high \
    phys_opt_directive AggressiveExplore \
    route_directive AlternateCLBRouting/AggressiveExplore] $metrics

set wns [dict get $metrics wns_ns]
puts "FPT_300MHZ_METRICS top=$top period_ns=$clock_period wns_ns=$wns achieved_mhz=[dict get $metrics achieved_mhz] LUT=[dict get $metrics logic_luts] FF=[dict get $metrics flip_flops] DSP=[dict get $metrics dsp48e2] RAMB18=[dict get $metrics ramb18e2] RAMB36=[dict get $metrics ramb36e2] URAM=[dict get $metrics uram288] routed=[dict get $metrics route_fully_routed] drc_errors=[dict get $metrics drc_error]"
fpt_require_clean_u280_route $metrics
if {$wns eq "NA"} {
    error "No setup timing path was available for 300 MHz acceptance"
}
if {$wns < 0} {
    error "Routed design misses $clock_period ns by [expr {-$wns}] ns"
}

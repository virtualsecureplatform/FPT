# Reassign only the wide pending-request input boundary of an already placed
# U280 checkpoint.  The source placement is preserved verbatim; payload
# captures are kept in their original SLL columns where possible and moved to
# nearby SLR1-side Laguna receive registers only when measured per-column SLL
# demand requires it.
#
# vivado -mode batch \
#   -source chisel/scripts/realign_pending_boundary_u280_checkpoint.tcl \
#   -tclargs RAW_PLACE_DCP OUTPUT_DIR 4.000 8 ?SLL_DEMAND_CSV?

set input_checkpoint [lindex $argv 0]
set output_dir [lindex $argv 1]
set clock_period [lindex $argv 2]
set jobs [lindex $argv 3]
set measured_sll_demand_csv [lindex $argv 4]
if {$input_checkpoint eq "" || $output_dir eq ""} {
    error "usage: RAW_PLACE_DCP OUTPUT_DIR ?CLOCK_PERIOD_NS? ?JOBS? ?SLL_DEMAND_CSV_OR_fixed:CSV?"
}
if {$clock_period eq ""} { set clock_period 4.000 }
if {$jobs eq ""} { set jobs 8 }
if {![string is double -strict $clock_period] || $clock_period <= 0} {
    error "CLOCK_PERIOD_NS must be positive: $clock_period"
}
if {![string is integer -strict $jobs] || $jobs < 1} {
    error "JOBS must be a positive integer: $jobs"
}

set input_checkpoint [file normalize $input_checkpoint]
set output_dir [file normalize $output_dir]
if {![file isfile $input_checkpoint]} {
    error "Placed checkpoint not found: $input_checkpoint"
}
file mkdir $output_dir
set_param general.maxThreads $jobs

proc fpt_site_xy {site} {
    set site_name [get_property NAME $site]
    if {![regexp {_X([0-9]+)Y([0-9]+)$} $site_name -> x y]} {
        error "Cannot parse physical coordinates from site $site_name"
    }
    return [list $x $y]
}

proc fpt_clock_region_x {cell} {
    set site [get_sites -quiet [get_property SITE $cell]]
    if {[llength $site] != 1} {
        error "Expected one placed site for $cell"
    }
    set clock_region [get_property CLOCK_REGION $site]
    if {![regexp {X([0-9]+)Y[0-9]+} $clock_region -> x]} {
        error "Cannot parse clock region $clock_region for $cell"
    }
    return $x
}

proc fpt_control_set_key {cell} {
    set controls [list]
    foreach ref_pin {C CE R S CLR PRE} {
        set pins [get_pins -quiet -of_objects $cell \
            -filter "REF_PIN_NAME == $ref_pin"]
        if {[llength $pins] == 0} { continue }
        set nets [lsort [get_nets -quiet -of_objects $pins]]
        lappend controls "$ref_pin=[join $nets ,]"
    }
    return [join $controls |]
}

open_checkpoint $input_checkpoint
set implemented_period [get_property PERIOD [get_clocks ap_clk]]
if {abs($implemented_period - $clock_period) > 0.0005} {
    error "Clock constraint mismatch: requested $clock_period ns, implemented $implemented_period ns"
}

set captures [lsort [get_cells -hierarchical -quiet \
    -regexp {^.*/pendingRequests/inputBoundary_decomposition_.*_reg.*$}]]
if {[llength $captures] != 7680} {
    error "Expected 7680 pending payload captures, found [llength $captures]"
}

# Record the physical source and original SLL-column location.  When a measured
# 16-column router demand vector is supplied, the original capture placement is
# preserved except where it exceeds the residual capacity of an SLL column.
set source_records [list]
set source_sequence 0
array unset original_pending_by_sll
for {set sll 0} {$sll < 16} {incr sll} {
    set original_pending_by_sll($sll) 0
}
foreach capture $captures {
    set capture_d [get_pins -quiet -of_objects $capture \
        -filter {REF_PIN_NAME == D}]
    set capture_net [get_nets -quiet -of_objects $capture_d]
    set source [get_cells -quiet -of_objects \
        [get_pins -leaf -quiet -of_objects $capture_net \
            -filter {DIRECTION == OUT}]]
    if {[llength $capture_d] != 1 || \
        [llength $capture_net] != 1 || \
        [llength $source] != 1 || \
        ![string match \
            "engine/blindRotate/blindRotate/cmux/forward/*" \
            [lindex $source 0]]} {
        error "Unexpected pending payload source for $capture: $source"
    }
    set source_site [get_sites -quiet [get_property SITE [lindex $source 0]]]
    if {[llength $source_site] != 1} {
        error "Pending payload source is not placed: $source"
    }
    set capture_site [get_sites -quiet [get_property SITE $capture]]
    if {[llength $capture_site] != 1 || \
        ![string match LAGUNA_* [lindex $capture_site 0]]} {
        error "Pending payload capture is not in a Laguna site: $capture"
    }
    lassign [fpt_site_xy $source_site] source_x source_y
    lassign [fpt_site_xy $capture_site] capture_site_x capture_site_y
    set original_sll [expr {$capture_site_x / 2}]
    if {$original_sll < 0 || $original_sll >= 16} {
        error "Invalid original SLL column $original_sll for $capture"
    }
    incr original_pending_by_sll($original_sll)
    # The sequence term makes the key unique and the sort deterministic.
    set key [expr {$source_x * 100000000 + \
        $source_y * 10000 + $source_sequence}]
    lappend source_records \
        [list $key $capture [lindex $source 0] $original_sll]
    incr source_sequence
}
set source_records [lsort -integer -index 0 $source_records]

# Free only the payload captures.  All arithmetic, memories, transform logic,
# and narrow queue controls retain their input-checkpoint placement.
set_property IS_LOC_FIXED FALSE $captures
unplace_cell $captures

# Build control-set-compatible site bins for each of the 16 physical SLL
# columns.  Two Laguna X coordinates feed each 1,440-SLL router column.
array unset site_bins_by_physical_x
array unset free_bel_count_by_sll
for {set sll 0} {$sll < 16} {incr sll} {
    set free_bel_count_by_sll($sll) 0
}
for {set x 0} {$x < 8} {incr x} {
    set boundary_sites [lsort -dictionary [get_sites -quiet \
        -of_objects [get_clock_regions X${x}Y4] \
        -filter {SITE_TYPE =~ LAGUNA*}]]
    if {[llength $boundary_sites] != 480} {
        error "Expected 480 SLR1 boundary Laguna sites in X$x, found [llength $boundary_sites]"
    }

    foreach site $boundary_sites {
        set site_bels [list]
        lassign [fpt_site_xy $site] site_x site_y
        set sll_column [expr {$site_x / 2}]
        for {set reg 0} {$reg < 6} {incr reg} {
            set bel [get_bels -quiet ${site}/RX_REG${reg}]
            set paired_site [get_sites -quiet \
                LAGUNA_X${site_x}Y[expr {$site_y - 120}]]
            set paired_bel [get_bels -quiet \
                ${paired_site}/RX_REG${reg}]
            if {[llength $bel] == 1 && \
                [llength $paired_bel] == 1 && \
                ![get_property IS_USED $bel] && \
                ![get_property IS_USED $paired_bel]} {
                lappend site_bels [lindex $bel 0]
            }
        }
        if {[llength $site_bels] > 0} {
            lappend site_bins_by_physical_x($site_x) \
                [list $site $site_bels]
            incr free_bel_count_by_sll($sll_column) \
                [llength $site_bels]
        }
    }
}

array unset free_site_bins_by_sll
for {set sll 0} {$sll < 16} {incr sll} {
    set left_x [expr {$sll * 2}]
    set right_x [expr {$left_x + 1}]
    set left_bins [list]
    set right_bins [list]
    if {[info exists site_bins_by_physical_x($left_x)]} {
        set left_bins $site_bins_by_physical_x($left_x)
    }
    if {[info exists site_bins_by_physical_x($right_x)]} {
        set right_bins $site_bins_by_physical_x($right_x)
    }
    set interleaved [list]
    set bin_count [expr {max([llength $left_bins], [llength $right_bins])}]
    for {set index 0} {$index < $bin_count} {incr index} {
        if {$index < [llength $left_bins]} {
            lappend interleaved [lindex $left_bins $index]
        }
        if {$index < [llength $right_bins]} {
            lappend interleaved [lindex $right_bins $index]
        }
    }
    set free_site_bins_by_sll($sll) $interleaved
}

# Determine per-column capture capacity.  With no measured vector, retain the
# diagnostic equal distribution (480 per router SLL column).  With a measured
# vector, subtract the original capture population to expose fixed demand and
# find the minimum possible peak by integer water filling.
array unset allowed_pending_by_sll
if {$measured_sll_demand_csv eq ""} {
    for {set sll 0} {$sll < 16} {incr sll} {
        set allowed_pending_by_sll($sll) 480
    }
    set target_peak_sll NA
} else {
    set demand_is_fixed [string match {fixed:*} $measured_sll_demand_csv]
    set demand_csv $measured_sll_demand_csv
    if {$demand_is_fixed} {
        set demand_csv [string range $measured_sll_demand_csv 6 end]
    }
    set supplied_sll_demand [split $demand_csv ,]
    if {[llength $supplied_sll_demand] != 16} {
        error "SLL demand CSV must contain exactly 16 integers"
    }
    array unset fixed_sll_demand
    set target_peak_sll 0
    for {set sll 0} {$sll < 16} {incr sll} {
        set supplied [lindex $supplied_sll_demand $sll]
        if {![string is integer -strict $supplied] || $supplied < 0} {
            error "Invalid SLL demand in column $sll: $supplied"
        }
        if {$demand_is_fixed} {
            set fixed_sll_demand($sll) $supplied
        } else {
            set fixed_sll_demand($sll) \
                [expr {$supplied - $original_pending_by_sll($sll)}]
        }
        if {$fixed_sll_demand($sll) < 0} {
            error "Measured demand is smaller than pending population in SLL column $sll"
        }
        set target_peak_sll \
            [expr {max($target_peak_sll, $fixed_sll_demand($sll))}]
    }
    while {1} {
        set total_capacity 0
        for {set sll 0} {$sll < 16} {incr sll} {
            set capacity [expr {min($free_bel_count_by_sll($sll), \
                max(0, $target_peak_sll - $fixed_sll_demand($sll)))}]
            incr total_capacity $capacity
        }
        if {$total_capacity >= 7680} { break }
        incr target_peak_sll
    }
    for {set sll 0} {$sll < 16} {incr sll} {
        set allowed_pending_by_sll($sll) \
            [expr {min($free_bel_count_by_sll($sll), \
                max(0, $target_peak_sll - $fixed_sll_demand($sll)))}]
    }
}

# Keep captures in their original SLL column up to its allowed population.
# Move only overflow captures, greedily choosing the nearest column with spare
# capacity.  This preserves the placer-created source/capture/consumer locality
# while removing the measured localized SLL overload.
array unset target_records_by_sll
array unset remaining_capacity_by_sll
array unset kept_in_original_sll
for {set sll 0} {$sll < 16} {incr sll} {
    set remaining_capacity_by_sll($sll) $allowed_pending_by_sll($sll)
    set kept_in_original_sll($sll) 0
}
set overflow_records [list]
foreach record $source_records {
    set original_sll [lindex $record 3]
    if {$kept_in_original_sll($original_sll) < \
            $allowed_pending_by_sll($original_sll)} {
        lappend target_records_by_sll($original_sll) $record
        incr kept_in_original_sll($original_sll)
        incr remaining_capacity_by_sll($original_sll) -1
    } else {
        lappend overflow_records $record
    }
}
foreach record $overflow_records {
    set original_sll [lindex $record 3]
    set best_sll -1
    set best_distance 100
    for {set candidate 0} {$candidate < 16} {incr candidate} {
        if {$remaining_capacity_by_sll($candidate) <= 0} { continue }
        set distance [expr {abs($candidate - $original_sll)}]
        if {$distance < $best_distance} {
            set best_sll $candidate
            set best_distance $distance
        }
    }
    if {$best_sll < 0} {
        error "No residual SLL-column capacity for pending capture overflow"
    }
    lappend target_records_by_sll($best_sll) $record
    incr remaining_capacity_by_sll($best_sll) -1
}

set placement_pairs [list]
set assigned_captures [list]
for {set sll 0} {$sll < 16} {incr sll} {
    set target_records [list]
    if {[info exists target_records_by_sll($sll)]} {
        set target_records $target_records_by_sll($sll)
    }
    set target_captures [list]
    set free_site_bins $free_site_bins_by_sll($sll)
    array unset records_by_control
    foreach record $target_records {
        set capture [lindex $record 1]
        lappend records_by_control([fpt_control_set_key $capture]) $record
    }

    set next_site_bin 0
    foreach control_key [lsort [array names records_by_control]] {
        set control_records $records_by_control($control_key)
        set next_control_record 0
        while {$next_control_record < [llength $control_records]} {
            if {$next_site_bin >= [llength $free_site_bins]} {
                error "Ran out of control-set-compatible Laguna sites in SLL column $sll"
            }
            set site_bin [lindex $free_site_bins $next_site_bin]
            incr next_site_bin
            set site_bels [lindex $site_bin 1]
            foreach bel $site_bels {
                if {$next_control_record >= [llength $control_records]} {
                    break
                }
                set record [lindex $control_records $next_control_record]
                incr next_control_record
                set capture [lindex $record 1]
                lappend placement_pairs $capture $bel
                lappend target_captures $capture
                lappend assigned_captures $capture
            }
        }
    }
    set fixed NA
    if {[info exists fixed_sll_demand($sll)]} {
        set fixed $fixed_sll_demand($sll)
    }
    puts "FPT_PENDING_BOUNDARY_REALIGN sll_column=$sll original=$original_pending_by_sll($sll) target=[llength $target_captures] fixed_demand=$fixed peak=$target_peak_sll control_sets=[llength [array names records_by_control]] sites_used=$next_site_bin free_rx_bels=$free_bel_count_by_sll($sll)"
}

if {[llength [lsort -unique $assigned_captures]] != 7680} {
    error "Pending payload capture assignment is not one-to-one"
}
place_cell $placement_pairs
set_property IS_LOC_FIXED TRUE $captures

array unset pair_counts
set same_x 0
foreach record $source_records {
    set capture [lindex $record 1]
    set source [lindex $record 2]
    set source_x [fpt_clock_region_x $source]
    set capture_x [fpt_clock_region_x $capture]
    incr pair_counts($source_x,$capture_x)
    if {$source_x == $capture_x} { incr same_x }
}
puts "FPT_PENDING_BOUNDARY_REALIGN same_clock_region_x=$same_x/7680"
for {set source_x 0} {$source_x < 8} {incr source_x} {
    set row [list]
    for {set capture_x 0} {$capture_x < 8} {incr capture_x} {
        set key "$source_x,$capture_x"
        if {[info exists pair_counts($key)]} {
            lappend row $pair_counts($key)
        } else {
            lappend row 0
        }
    }
    puts "FPT_PENDING_BOUNDARY_MATRIX source_x=$source_x captures=$row"
}

set drc_name fpt_pending_boundary_realign_drc
report_drc -name $drc_name \
    -file [file join $output_dir placement_drc.rpt]
set drc_errors [llength [get_drc_violations -name $drc_name -quiet \
    -filter {SEVERITY == Error || SEVERITY == Fatal}]]
if {$drc_errors != 0} {
    error "Pending boundary reassignment has $drc_errors fatal/error DRC violations"
}

report_timing_summary \
    -file [file join $output_dir timing_summary.rpt]
report_timing -delay_type max -max_paths 100 -nworst 1 \
    -file [file join $output_dir timing_paths.rpt]
report_design_analysis -congestion \
    -file [file join $output_dir congestion.rpt]
write_checkpoint -force [file join $output_dir realigned_place.dcp]

set worst_paths [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
set wns NA
if {[llength $worst_paths] == 1} {
    set wns [format %.3f [get_property SLACK [lindex $worst_paths 0]]]
}
puts "FPT_PENDING_BOUNDARY_REALIGN_DONE wns_ns=$wns drc_errors=$drc_errors checkpoint=[file join $output_dir realigned_place.dcp]"

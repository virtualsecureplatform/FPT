# Localize only the selector pairs that formed the top 100 slow-to-fast paths
# in the v179 250 MHz placement. Constraining every data lane improved that
# path class, but doubled the router's first overlap count. This sparse tail
# keeps the remaining gearbox placement timing-driven.

proc fpt_create_pending_gearbox_tail_macros {} {
    set pair_starts {
        103 145 159 167 211 279 295 303 319 471 543 551 567 655 719 735
        743 799 863 887 1039 1047 1119 1191 1279 1287 1311 1343 1351 1359
        1383 1431 1439 1503 1511 1527 1543 1591 1615 1695 1703 1783 1815
        1831 1839 2007 2055 2071 2107 2131 2271 2279 2311 2335 2343 2375
        2383 2407 2439 2455 2471 2479 2519 2559 2575 2655 2671 2759 2775
        2815 2855 2879 2935 2999 3047 3107 3119 3199 3231 3247 3287 3295
        3399 3415 3423 3431 3471 3479 3511 3543 3607 3615 3623 3631 3679
        3727 3759 3807
    }

    unset -nocomplain tx_by_bit pre_by_bit selector_by_bit
    foreach cell [get_cells -hierarchical -quiet \
        -regexp {^.*/pendingRequests/gearbox/txWord_reg\[[0-9]+\]$}] {
        set name [get_property NAME $cell]
        if {![regexp {txWord_reg\[([0-9]+)\]$} $name -> bit]} {
            error "Unexpected pending gearbox holding-register name: $name"
        }
        set tx_by_bit($bit) $cell
    }
    foreach cell [get_cells -hierarchical -quiet \
        -regexp {^.*/pendingRequests/gearbox/preLaunch_reg\[[0-9]+\]$}] {
        set name [get_property NAME $cell]
        if {![regexp {preLaunch_reg\[([0-9]+)\]$} $name -> bit]} {
            error "Unexpected pending gearbox pre-launch-register name: $name"
        }
        set pre_by_bit($bit) $cell
    }
    foreach cell [get_cells -hierarchical -quiet \
        -regexp {^.*/pendingRequests/gearbox/preLaunch\[[0-9]+\]_i_1$}] {
        set name [get_property NAME $cell]
        if {![regexp {preLaunch\[([0-9]+)\]_i_1$} $name -> bit]} {
            error "Unexpected pending gearbox selector name: $name"
        }
        if {$bit < 3845} {
            set selector_by_bit($bit) $cell
        }
    }

    if {[array size tx_by_bit] != 7689 || [array size pre_by_bit] != 3847 || \
        [array size selector_by_bit] != 3845} {
        error "Unexpected pending gearbox shape: tx=[array size tx_by_bit] pre=[array size pre_by_bit] selectors=[array size selector_by_bit]"
    }
    if {[llength [get_macros -quiet fpt_pending_gearbox_tail_*]] != 0} {
        error "Pending gearbox tail macros already exist"
    }

    unset -nocomplain released_by_x
    for {set x 0} {$x < 8} {incr x} {
        set released_by_x($x) {}
    }
    set tail_cells {}
    set released_count 0
    foreach first $pair_starts {
        if {$first % 2 != 1 || $first < 1 || $first + 1 >= 3845} {
            error "Invalid pending gearbox selector-pair start: $first"
        }
        set second [expr {$first + 1}]
        set relative_cells {}
        foreach lane [list $first $second] {
            if {![info exists tx_by_bit($lane)] || \
                ![info exists tx_by_bit([expr {$lane + 3845}])] || \
                ![info exists selector_by_bit($lane)] || \
                ![info exists pre_by_bit($lane)]} {
                error "Missing pending gearbox cells for tail lane $lane"
            }
            lappend relative_cells $tx_by_bit($lane) X0Y0
            lappend relative_cells $tx_by_bit([expr {$lane + 3845}]) X0Y0
            lappend tail_cells $tx_by_bit($lane) \
                $tx_by_bit([expr {$lane + 3845}]) \
                $selector_by_bit($lane) $pre_by_bit($lane)
            set stripe [expr {$lane % 8}]
            lappend released_by_x($stripe) $tx_by_bit($lane) \
                $tx_by_bit([expr {$lane + 3845}]) $pre_by_bit($lane)
            incr released_count 3
        }
        foreach lane [list $first $second] {
            lappend relative_cells $selector_by_bit($lane) X0Y1
            lappend relative_cells $pre_by_bit($lane) X0Y1
        }
        set macro_name fpt_pending_gearbox_tail_${first}
        create_macro $macro_name
        update_macro $macro_name $relative_cells
    }

    # The coarse transmitter stripes assign adjacent lanes to different child
    # pblocks. Release only the sparse macro members from those child pblocks;
    # they remain members of the SLR0 parent pblock.
    for {set x 0} {$x < 8} {incr x} {
        set pblocks [get_pblocks -quiet pb_pending_gearbox_tx_x${x}]
        if {[llength $pblocks] != 1} {
            error "Expected one pending gearbox transmitter stripe x=$x, found [llength $pblocks]"
        }
        if {[llength $released_by_x($x)] > 0} {
            remove_cells_from_pblock $pblocks $released_by_x($x)
        }
    }
    set parent_pblocks [get_pblocks -quiet pb_forward]
    if {[llength $parent_pblocks] != 1} {
        error "Expected one pending gearbox transmitter parent pblock, found [llength $parent_pblocks]"
    }
    add_cells_to_pblock $parent_pblocks $tail_cells

    puts "FPT_PENDING_GEARBOX_TAIL_MACROS macros=[llength $pair_starts] data_lanes=[expr {2 * [llength $pair_starts]}] released_registers=$released_count parent_cells=[llength $tail_cells]"
}

set input_checkpoint [lindex $argv 0]
set output_dir [lindex $argv 1]
set clock_period [lindex $argv 2]
set jobs [lindex $argv 3]
set minimum_loads [lindex $argv 4]
set target_fanout [lindex $argv 5]

if {$input_checkpoint eq "" || $output_dir eq ""} {
    error "usage: PLACED_DCP OUTPUT_DIR ?CLOCK_PERIOD_NS? ?JOBS? ?MINIMUM_LOADS? ?TARGET_FANOUT?"
}
if {$clock_period eq ""} { set clock_period 5.000 }
if {$jobs eq ""} { set jobs 8 }
if {$minimum_loads eq ""} { set minimum_loads 64 }
if {$target_fanout eq ""} { set target_fanout 32 }
foreach {name value} [list jobs $jobs minimum_loads $minimum_loads \
    target_fanout $target_fanout] {
    if {![string is integer -strict $value] || $value < 1} {
        error "$name must be a positive integer: $value"
    }
}

set input_checkpoint [file normalize $input_checkpoint]
set output_dir [file normalize $output_dir]
if {![file isfile $input_checkpoint]} {
    error "Placed checkpoint not found: $input_checkpoint"
}
file mkdir $output_dir
set_param general.maxThreads $jobs

open_checkpoint $input_checkpoint
set implemented_period [get_property PERIOD [get_clocks ap_clk]]
if {abs($implemented_period - $clock_period) > 0.0005} {
    error "Clock constraint mismatch: requested $clock_period ns, implemented $implemented_period ns"
}

# These exact nets launched the high-fanout subset of the 200 worst endpoints
# in the clean v280 route. They also exist in its v279 placed parent. Exact
# names are intentional: matching whole register families selects hundreds of
# already-local replicas and turns this bounded experiment into a global one.
set measured_net_names [list \
    {engine/blindRotate/blindRotate/cmux/coefficients/candidateSpanHalves_2_reg_rep__0_n_0} \
    {engine/blindRotate/blindRotate/cmux/coefficients/memory/_cmux_io_doneContext[3]} \
    {engine/blindRotate/blindRotate/cmux/coefficients/memory/_memory_io_prefetchValid_repN_48} \
    {engine/blindRotate/blindRotate/cmux/coefficients/memory/loadCommitData_1_34[0]} \
    {engine/blindRotate/blindRotate/cmux/external/laneBanks_1/selectedReadWord_registeredOutputBank} \
    {engine/blindRotate/blindRotate/cmux/forward/core/backend/generated/s10708_reg[1]_rep__0_n_0} \
    {engine/blindRotate/blindRotate/cmux/forward/core/backend/generated/s14203_reg[20]_rep__1_n_0} \
    {engine/blindRotate/blindRotate/cmux/forward/core/backend/generated/s16092[22]} \
    {engine/blindRotate/blindRotate/cmux/forward/core/backend/generated/s16092_reg[19]_rep__1_n_0} \
    {engine/blindRotate/blindRotate/cmux/forward/core/backend/generated/s16092_reg[20]_rep_n_0} \
    {engine/blindRotate/blindRotate/cmux/forward/core/backend/generated/s16092_reg[23]_rep__5_n_0} \
    {engine/blindRotate/blindRotate/cmux/forward/core/backend/generated/s31644_reg[13]_rep__1_n_0} \
    {engine/blindRotate/blindRotate/cmux/forward/core/backend/generated/s35817[17]} \
    {engine/blindRotate/blindRotate/cmux/forward/core/backend/generated/s36628_reg[21]_rep__0_n_0} \
    {engine/blindRotate/blindRotate/cmux/forward/core/backend/generated/s37432_reg[23]_rep_n_0} \
    {engine/blindRotate/blindRotate/cmux/pendingRequests/outputReadPointer_repN_10} \
    {engine/blindRotate/blindRotate/loaderTestVector[7]}]

array unset selected_by_name
array unset selected_loads_by_name
foreach net_name $measured_net_names {
    set source_net [get_nets -hierarchical -quiet \
        -filter "NAME == {$net_name}"]
    if {[llength $source_net] != 1} {
        error "Expected one measured critical net $net_name, found [llength $source_net]"
    }
    set loads [get_pins -leaf -quiet -of_objects $source_net \
        -filter {DIRECTION == IN}]
    if {[llength $loads] < $minimum_loads} {
        error "Measured critical net $net_name has only [llength $loads] loads"
    }
    set selected_by_name($net_name) [lindex $source_net 0]
    set selected_loads_by_name($net_name) [llength $loads]
}

# pairValid is the worst cross-SLR path but has only ten loads. Split it into
# small sink-local groups independently of the high-fanout threshold.
set pair_valid_nets [get_nets -hierarchical -quiet \
    -filter {NAME =~ */coefficients/_coefficients_io_pairValid}]
if {[llength $pair_valid_nets] != 1} {
    error "Expected one coefficient pairValid net, found [llength $pair_valid_nets]"
}
set pair_valid [lindex $pair_valid_nets 0]
set pair_valid_name [get_property NAME $pair_valid]
set pair_valid_loads [get_pins -leaf -quiet -of_objects $pair_valid \
    -filter {DIRECTION == IN}]
set selected_by_name($pair_valid_name) $pair_valid
set selected_loads_by_name($pair_valid_name) [llength $pair_valid_loads]

set selected_nets [list]
foreach net_name [lsort [array names selected_by_name]] {
    set net $selected_by_name($net_name)
    set per_net_target $target_fanout
    if {$net_name eq $pair_valid_name} { set per_net_target 4 }
    set_property FORCE_MAX_FANOUT $per_net_target $net
    lappend selected_nets $net
    puts "FPT_MEASURED_REPLICATION_NET loads=$selected_loads_by_name($net_name) target=$per_net_target net=$net_name"
}
if {[llength $selected_nets] != 18} {
    error "Expected exactly 18 measured critical nets, found [llength $selected_nets]"
}
puts "FPT_MEASURED_REPLICATION_SELECTED nets=[llength $selected_nets] minimum_loads=$minimum_loads target_fanout=$target_fanout"

phys_opt_design -force_replication_on_nets $selected_nets
write_checkpoint -force [file join $output_dir post_replication.dcp]
report_timing_summary \
    -file [file join $output_dir post_replication_timing_summary.rpt]

route_design -directive Explore
write_checkpoint -force [file join $output_dir replicated_route.dcp]
report_route_status \
    -file [file join $output_dir replicated_route_status.rpt]
report_timing_summary \
    -file [file join $output_dir replicated_route_timing_summary.rpt]
report_timing -delay_type max -max_paths 200 -nworst 1 \
    -slack_lesser_than 0.0 \
    -file [file join $output_dir replicated_route_timing_paths.rpt]

set routed [report_route_status -boolean_check ROUTED_FULLY]
set final_paths [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
set wns NA
if {[llength $final_paths] == 1} {
    set wns [format %.3f [get_property SLACK [lindex $final_paths 0]]]
}
puts "FPT_MEASURED_REPLICATION_DONE routed=$routed wns_ns=$wns checkpoint=[file join $output_dir replicated_route.dcp]"

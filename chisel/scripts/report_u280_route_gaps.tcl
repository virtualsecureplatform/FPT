set input_checkpoint [lindex $argv 0]
if {$input_checkpoint eq ""} {
    error "usage: ROUTED_DCP"
}
set input_checkpoint [file normalize $input_checkpoint]
if {![file isfile $input_checkpoint]} {
    error "Routed checkpoint not found: $input_checkpoint"
}

open_checkpoint $input_checkpoint
set gap_nets [report_route_status -return_nets -route_type GAPS]
puts "FPT_ROUTE_GAPS count=[llength $gap_nets]"
foreach net $gap_nets {
    set name [get_property NAME $net]
    set pins [get_pins -quiet -of_objects $net]
    set ports [get_ports -quiet -of_objects $net]
    set drivers [get_pins -quiet -leaf -of_objects $net \
        -filter {DIRECTION == OUT}]
    set loads [get_pins -quiet -leaf -of_objects $net \
        -filter {DIRECTION == IN}]
    puts "FPT_ROUTE_GAP net={$name} pins=[llength $pins] ports=[llength $ports] drivers=[llength $drivers] loads=[llength $loads] route_status={[get_property ROUTE_STATUS $net]}"
    foreach port $ports {
        puts "FPT_ROUTE_GAP_PORT name={[get_property NAME $port]} direction={[get_property DIRECTION $port]}"
    }
    foreach driver $drivers {
        puts "FPT_ROUTE_GAP_DRIVER pin={[get_property NAME $driver]} ref={[get_property REF_NAME [get_cells -of_objects $driver]]}"
    }
    foreach load [lrange $loads 0 15] {
        puts "FPT_ROUTE_GAP_LOAD pin={[get_property NAME $load]} ref={[get_property REF_NAME [get_cells -of_objects $load]]}"
    }
    if {[llength $loads] > 16} {
        puts "FPT_ROUTE_GAP_LOADS_OMITTED count=[expr {[llength $loads] - 16}]"
    }
}

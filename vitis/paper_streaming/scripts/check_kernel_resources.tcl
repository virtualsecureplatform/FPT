# Complete RTL-kernel resource gate, including its DataMover masters and CSR.
# Platform placement/timing/DRC remain separately required by the original gate.
if {![info exists ::env(FPT_KERNEL_METRICS_PREFIX)]} {error "FPT_KERNEL_METRICS_PREFIX is required"}
set prefix $::env(FPT_KERNEL_METRICS_PREFIX)
set kernel [get_cells -hierarchical -quiet -filter {REF_NAME == FptPaperStreamingKernel}]
if {[llength $kernel]!=1} {
  set kernel [get_cells -hierarchical -quiet -filter {ORIG_REF_NAME == FptPaperStreamingKernel}]
}
set standalone [expr {[llength $kernel]==0 && [get_property TOP [current_design]] eq "FptPaperStreamingKernel"}]
if {[llength $kernel]!=1 && !$standalone} {
  error "resource gate could not uniquely identify the complete streaming kernel: design=[current_design], top=[get_property TOP [current_design]], candidates=$kernel"
}
if {!$standalone && [get_property IS_BLACKBOX $kernel]} {
  error "resource gate cannot admit an unresolved kernel black box"
}
# A platform synthesis report can otherwise look small while kernel IP is
# still unresolved. Count the implemented kernel, including vendor DMA IP.
foreach box [get_cells -hierarchical -quiet -filter {IS_BLACKBOX == 1}] {
  set name [get_property NAME $box]
  if {$standalone || [string first "[get_property NAME $kernel]/" $name]==0} {
    error "resource gate found unresolved kernel cell $name"
  }
}
set report ${prefix}_utilization.rpt
if {$standalone} {
  report_utilization -hierarchical -file $report
  set kernel_name FptPaperStreamingKernel
} else {
  report_utilization -hierarchical -cells $kernel -file $report
  set kernel_name [get_property NAME $kernel]
}
set input [open $report r];set contents [read $input];close $input
proc paper_kernel_resource_row {contents} {
  set names [dict create total_lut {Total LUTs} logic_lut {Logic LUTs} lutram LUTRAMs \
    srl SRLs ff FFs ramb36 RAMB36 ramb18 RAMB18 uram URAM dsp {DSP Blocks}]
  set columns {}
  foreach line [split $contents \n] {
    if {![string match {|*|} [string trim $line]]} {continue}
    set fields {};foreach field [lrange [split $line |] 1 end-1] {lappend fields [string trim $field]}
    if {[lindex $fields 0] eq "Instance"} {
      set columns {}
      dict for {metric label} $names {
        set index [lsearch -exact $fields $label]
        if {$index<0} {error "missing kernel resource column $label"}
        dict set columns $metric $index
      }
      continue
    }
    if {[dict size $columns]==0} {continue}
    # The first data row is the aggregate requested scope. Never skip a bad
    # aggregate and accidentally admit a smaller descendant. PR reports add
    # PR Attribute / Total PPLOCs columns; their positions are not fixed.
    set result {}
    dict for {metric index} $columns {
      set value [lindex $fields $index]
      if {![string is wideinteger -strict $value] || $value<0} {
        error "non-numeric aggregate kernel resource $metric=$value"
      }
      dict set result $metric $value
    }
    return $result
  }
  error "could not parse official hierarchical kernel utilization"
}
set metrics [paper_kernel_resource_row $contents]
dict set metrics kernel_cell $kernel_name
dict set metrics bram_equivalents [expr {[dict get $metrics ramb36]+0.5*[dict get $metrics ramb18]+8*[dict get $metrics uram]}]
set output [open ${prefix}_resources.tsv w];puts $output "metric\tvalue"
dict for {name value} $metrics {puts $output "$name\t$value"};close $output
set failures {}
# bram_equivalents: the paper-derived cap was 606; the ROM forward twist adds 256 RAMB36 (and removes 768 DSP).
foreach {name limit} {total_lut 680000 ff 1099200 dsp 6592 bram_equivalents 896} {
  if {[dict get $metrics $name]>$limit} {lappend failures "$name=[dict get $metrics $name]>$limit"}
}
if {[llength $failures]} {error "complete streaming kernel exceeds resource admission: [join $failures {, }]"}
puts "PAPER_KERNEL_RESOURCE_PASS: routed timing, platform DRC and board tests remain required"

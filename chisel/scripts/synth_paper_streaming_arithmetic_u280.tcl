# Primitive characterization only; never a complete-kernel admission report.
# Usage: vivado -mode batch -source THIS_FILE -tclargs KIND OUT ?synth-only?
# Run only after the selected arithmetic schedule passes numerical screening.
if {[llength $argv] < 2 || [llength $argv] > 3} {error "usage: KIND OUTPUT_DIRECTORY ?synth-only?"}
set synth_only 0
if {[llength $argv] == 3} {
    if {[lindex $argv 2] ne "synth-only"} {error "third argument must be synth-only"}
    set synth_only 1
}
set kind [lindex $argv 0]
set output_dir [file normalize [lindex $argv 1]]
if {[file exists $output_dir]} {error "refusing to overwrite characterization: $output_dir"}
set script_dir [file dirname [file normalize [info script]]]
set resource_dir [file normalize [file join $script_dir ../src/main/resources]]
source [file join $script_dir u280_post_route_metrics.tcl]
set dependencies [list]
set include_files [list]
switch -- $kind {
    raw-mixed-candidate {
        set top PaperStreamingRawBlindRotate
        set generics [list STEPS=586 BRAM_STATE_LANES=112 DSP_INVERSE_LAST=1 FUSED_ACCUM_ROUND=0]
        set dependencies [list PaperStreamingInputPrepare17 PaperStreamingInitialBodyBuffer17 PaperStreamingKeyedBlindRotate PaperStreamingKeyCache PaperStreamingBufferedBlindRotate PaperStreamingBlindRotate PaperStreamingMixedCoefficientStore17 PaperStreamingExponentBanks PaperStreamingCoefficientStore17 PaperStreamingCoefficientFrontend17 PaperStreamingRotate17 PaperStreamingDecompose17 PaperStreamingExternalProduct PaperStreamingTransform PaperStreamingMacTile PaperStreamingMacLane PaperStreamingComplexProduct PaperStreamingFFTStage PaperStreamingTwiddleProduct PaperStreamingComplexProductSplit PaperStreamingComplexProductRounded PaperStreamingButterfly4 PaperStreamingRequantize PaperStreamingFramePermute PaperStreamingTwist PaperStreamingRoundedAdd PaperStreamingRoundedButterfly4 PaperStreamingSampleBuffer17 PaperStreamingSampleStream]
        set include_files [list [file join $resource_dir PaperStreamingTwiddles.svh]]
    }
    buffered-blind-rotate-dsp {
        set top PaperStreamingBufferedBlindRotate
        set generics [list STEPS=586 DIGIT_REVERSED=1 DSP_FORWARD_PAIRS=1]
        set dependencies [list PaperStreamingBlindRotate PaperStreamingSampleBuffer17 PaperStreamingCoefficientStore17 PaperStreamingCoefficientFrontend17 PaperStreamingRotate17 PaperStreamingDecompose17 PaperStreamingExternalProduct PaperStreamingTransform PaperStreamingMacTile PaperStreamingMacLane PaperStreamingComplexProduct PaperStreamingFFTStage PaperStreamingTwiddleProduct PaperStreamingComplexProductSplit PaperStreamingComplexProductRounded PaperStreamingButterfly4 PaperStreamingRequantize PaperStreamingFramePermute PaperStreamingTwist PaperStreamingRoundedAdd PaperStreamingRoundedButterfly4]
        set include_files [list [file join $resource_dir PaperStreamingTwiddles.svh]]
    }
    external-product-compact - external-product-reversed {
        set top PaperStreamingExternalProduct
        set generics [list ACC_WIDTH=40 LEG_GUARD=4 FUSED_ROUND=1]
        if {$kind eq "external-product-reversed"} {lappend generics DIGIT_REVERSED=1}
        set dependencies [list PaperStreamingTransform PaperStreamingMacTile PaperStreamingMacLane PaperStreamingComplexProduct PaperStreamingFFTStage PaperStreamingTwiddleProduct PaperStreamingComplexProductSplit PaperStreamingComplexProductRounded PaperStreamingButterfly4 PaperStreamingRequantize PaperStreamingFramePermute PaperStreamingTwist PaperStreamingRoundedAdd PaperStreamingRoundedButterfly4]
        set include_files [list [file join $resource_dir PaperStreamingTwiddles.svh]]
    }
    mixed-coefficient-store17 - bram-coefficient-store17 {
        set top PaperStreamingMixedCoefficientStore17
        set generics [list BRAM_LANES=112]
        if {$kind eq "bram-coefficient-store17"} {set generics [list BRAM_LANES=128]}
    }
    initial-body-buffer17 {
        set top PaperStreamingInitialBodyBuffer17
        set generics [list]
    }
    input-prepare17 {
        set top PaperStreamingInputPrepare17
        set generics [list]
    }
    key-cache {
        set top PaperStreamingKeyCache
        set generics [list]
    }
    sample-buffer17 {
        set top PaperStreamingSampleBuffer17
        set generics [list]
    }
    coefficient-store17 {
        set top PaperStreamingCoefficientStore17
        set generics [list]
    }
    product-rounded {
        set top PaperStreamingComplexProductRounded
        set generics [list A_WIDTH=32 B_WIDTH=28 OUTPUT_WIDTH=48 SHIFT=14]
        set dependencies [list PaperStreamingComplexProductSplit PaperStreamingRoundedAdd]
    }
    product-rq-reference {
        set top PaperStreamingProductRequantize
        set generics [list]
        set dependencies [list PaperStreamingComplexProductSplit PaperStreamingRequantize]
    }
    fft-inverse-stage3-guard4-dsp {
        set top PaperStreamingFFTStage
        set generics [list LANES=64 STAGE=3 INVERSE=1 FUSED_ROUND=1 LEG_GUARD=4 DSP_PAIRS=1]
        set dependencies [list PaperStreamingTwiddleProduct PaperStreamingComplexProductSplit PaperStreamingComplexProductRounded PaperStreamingButterfly4 PaperStreamingRequantize PaperStreamingRoundedAdd PaperStreamingRoundedButterfly4]
        set include_files [list [file join $resource_dir PaperStreamingTwiddles.svh]]
    }
    fft-stage3-guard4-dsp {
        set top PaperStreamingFFTStage
        set generics [list LANES=128 STAGE=3 INVERSE=0 FUSED_ROUND=1 LEG_GUARD=4 DSP_PAIRS=1]
        set dependencies [list PaperStreamingTwiddleProduct PaperStreamingComplexProductSplit PaperStreamingComplexProductRounded PaperStreamingButterfly4 PaperStreamingRequantize PaperStreamingRoundedAdd PaperStreamingRoundedButterfly4]
        set include_files [list [file join $resource_dir PaperStreamingTwiddles.svh]]
    }
    fft-stage3-fused {
        set top PaperStreamingFFTStage
        set generics [list LANES=128 STAGE=3 INVERSE=0 FUSED_ROUND=1]
        set dependencies [list PaperStreamingTwiddleProduct PaperStreamingComplexProductSplit PaperStreamingComplexProductRounded PaperStreamingButterfly4 PaperStreamingRequantize PaperStreamingRoundedAdd PaperStreamingRoundedButterfly4]
        set include_files [list [file join $resource_dir PaperStreamingTwiddles.svh]]
    }
    forward - inverse {
        set top PaperStreamingTransform
        if {$kind eq "forward"} {set generics [list LANES=128 INVERSE=0 NATIVE=1]} \
        else {set generics [list LANES=64 INVERSE=1 NATIVE=1]}
        set dependencies [list PaperStreamingFFTStage PaperStreamingTwiddleProduct PaperStreamingComplexProductSplit PaperStreamingComplexProductRounded PaperStreamingButterfly4 PaperStreamingRequantize PaperStreamingFramePermute PaperStreamingTwist PaperStreamingRoundedAdd PaperStreamingRoundedButterfly4]
        set include_files [list [file join $resource_dir PaperStreamingTwiddles.svh]]
    }
    product-split {
        set top PaperStreamingComplexProductSplit
        set generics [list A_WIDTH=32 B_WIDTH=28]
    }
    mac-lane40 - mac-lane-fused40 {
        set top PaperStreamingMacLane
        set generics [list USE_SPLIT=1 ACC_WIDTH=40]
        if {$kind eq "mac-lane-fused40"} {lappend generics FUSED_ACCUM_ROUND=1}
        set dependencies [list PaperStreamingComplexProduct PaperStreamingComplexProductSplit PaperStreamingRequantize]
    }
    mac-lane {
        set top PaperStreamingMacLane
        set generics [list]
        set dependencies [list PaperStreamingComplexProduct PaperStreamingComplexProductSplit PaperStreamingRequantize]
    }
    mac-lane-split {
        set top PaperStreamingMacLane
        set generics [list USE_SPLIT=1]
        set dependencies [list PaperStreamingComplexProduct PaperStreamingComplexProductSplit PaperStreamingRequantize]
    }
    mac-tile {
        set top PaperStreamingMacTile
        set generics [list LANES=128]
        set dependencies [list PaperStreamingMacLane PaperStreamingComplexProduct PaperStreamingComplexProductSplit PaperStreamingRequantize]
    }
    product {
        set top PaperStreamingComplexProduct
        set generics [list A_WIDTH=32 B_WIDTH=28]
    }
    butterfly {
        set top PaperStreamingButterfly4
        set generics [list WIDTH=32 INVERSE=0]
    }
    requantize {
        set top PaperStreamingRequantize
        set generics [list INPUT_WIDTH=62 OUTPUT_WIDTH=48 SHIFT=14]
    }
    default {error "unknown arithmetic primitive: $kind"}
}
file mkdir $output_dir
set input_dir [file join $output_dir inputs]
file mkdir $input_dir
set source_files [list [info script] [file join $script_dir u280_post_route_metrics.tcl] [file join $resource_dir ${top}.sv]]
foreach dependency $dependencies {lappend source_files [file join $resource_dir ${dependency}.sv]}
foreach include_file $include_files {lappend source_files $include_file}
set hashes [open [file join $output_dir inputs.sha256] w]
foreach source $source_files {
    file copy $source [file join $output_dir inputs [file tail $source]]
    puts $hashes [exec sha256sum -- $source]
}
close $hashes
set_param general.maxThreads 4
set_property include_dirs [list $input_dir] [current_fileset]
read_verilog -sv [file join $input_dir ${top}.sv]
foreach dependency $dependencies {read_verilog -sv [file join $input_dir ${dependency}.sv]}
set constraint [file join $output_dir clock.xdc]
set stream [open $constraint w]
puts $stream {create_clock -name ap_clk -period 5.000 [get_ports clk]}
close $stream
read_xdc $constraint
synth_design -top $top -part xcu280-fsvh2892-2L-e -mode out_of_context \
    -generic $generics -flatten_hierarchy rebuilt
fpt_require_u280_clock 5.000 clk
report_utilization -hierarchical -file [file join $output_dir post_synth_utilization.rpt]
report_timing_summary -file [file join $output_dir post_synth_timing.rpt]
write_checkpoint [file join $output_dir post_synth.dcp]
if {$synth_only} {puts "SYNTH_ONLY_COMPLETE: no routed timing or complete-kernel admission";exit}
opt_design
place_design
phys_opt_design
route_design
report_utilization -hierarchical -file [file join $output_dir utilization.rpt]
report_timing_summary -file [file join $output_dir timing_summary.rpt]
report_route_status -file [file join $output_dir route_status.rpt]
report_drc -name primitive_drc -file [file join $output_dir drc.rpt]
set metrics [fpt_collect_u280_metrics 5.000 clk primitive_drc]
set hold_paths [get_timing_paths -quiet -delay_type min -max_paths 1]
if {[llength $hold_paths] == 0} {error "missing hold path for primitive characterization"}
dict set metrics whs_ns [get_property SLACK [lindex $hold_paths 0]]
fpt_write_u280_metrics [file join $output_dir metrics.tsv] \
    [list scope arithmetic_primitive top $top complete_kernel_qualified false] $metrics
write_checkpoint [file join $output_dir routed.dcp]
fpt_require_clean_u280_route $metrics
if {[dict get $metrics wns_ns] eq "NA" || [dict get $metrics wns_ns] < 0 || [dict get $metrics whs_ns] < 0} {
    error "primitive misses 200 MHz setup or hold timing"
}

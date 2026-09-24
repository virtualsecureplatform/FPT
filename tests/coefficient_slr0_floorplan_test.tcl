# Exercise ownership and fail-closed hierarchy checks without Vivado.
source [file join [file dirname [info script]] .. vitis scripts apply_fpt_coefficient_slr0.tcl]
# First exercise the disabled fixture independently of the caller's build
# environment. The second fixture explicitly enables prepared input below.
set ::env(FPT_U280_PREPROCESSED_INPUT) 0
set ::env(FPT_U280_BUFFERED_OUTPUT) 0
set cmux top/controller/core/engine/blindRotate/blindRotate/cmux
set wrapper [file dirname [file dirname $cmux]]
set fixtures [dict create]
proc fixture {name primitive} {dict set ::fixtures $name $primitive}
foreach name {coefficients inverse inverseBoundary componentJoin inverseTags inverseOutputLocalBoundary external externalOutputPipeline pendingRequests forwardTags} {
  fixture $cmux/$name 0
}
foreach name {front back} {fixture $cmux/forward/core/backend/generated/$name 0}
foreach role {sourceTx middleRx middleTx destinationRx} {
  fixture $cmux/coefficientDigitLink/$role 0
  for {set i 0} {$i < 2560} {incr i} {
    fixture "$cmux/coefficientDigitLink/$role/outputPayload_payloadBoundary/value_reg\[$i\]" 1
  }
}
for {set i 0} {$i < 7680} {incr i} {
  foreach role {tx rx} {fixture "$cmux/forward/core/backend/generated/boundary/${role}_data_reg\[$i\]" 1}
}
fixture $cmux/forward/core/backend/generated/boundary/back_token_delay_reg 1
for {set i 0} {$i < 3840} {incr i} {
  fixture "$cmux/inverseOutputLocalBoundary/outputPayload_payloadBoundary/value_reg\[$i\]" 1
}
for {set i 0} {$i < 4} {incr i} {fixture "$cmux/registeredInverseTag_reg\[$i\]" 1}
fixture $cmux/registeredInverseTagValid_reg 1
fixture $wrapper/blindRotate/loaderContext_reg 1
fixture $wrapper/blindRotate/exponentMemory_ext/memory 1
fixture $wrapper/sampleExtract/maskMemory_ext/memory 1
fixture $wrapper/sampleExtract 0
fixture $wrapper/extracting_reg 1
set bk [file dirname $wrapper]/keyBuffer/memory
fixture $bk 1

proc get_cells {args} {
  set filter_index [lsearch -exact $args -filter]
  if {$filter_index < 0} {
    set name [lindex $args end]
    if {[dict exists $::fixtures $name]} {return [list $name]}
    return {}
  }
  set expression [lindex $args [expr {$filter_index + 1}]]
  if {![regexp {NAME =~ (\S+)} $expression -> pattern]} {error "unsupported mock filter $expression"}
  set excluded ""
  regexp {NAME !~ (\S+)} $expression -> excluded
  set result {}
  dict for {cell primitive} $::fixtures {
    if {![string match $pattern $cell]} {continue}
    if {$excluded ne "" && [string match $excluded $cell]} {continue}
    if {[regexp {IS_PRIMITIVE|IS_SEQUENTIAL|REF_NAME =~ FD} $expression] && !$primitive} {continue}
    lappend result $cell
  }
  return $result
}
proc set_property {property value cells} {
  foreach cell $cells {dict set ::properties $cell $property $value}
}
proc fpt_hard_pblock {name slr cells} {
  foreach cell $cells {
    if {[dict exists $::owners $cell]} {error "duplicate ownership $cell"}
    dict set ::owners $cell $slr
  }
}
set properties [dict create]
set owners [dict create]
fpt_apply_coefficient_slr0 $cmux
foreach {name slr} {
  coefficients SLR0 inverse SLR0 inverseBoundary SLR1 componentJoin SLR0
  inverseTags SLR0 inverseOutputLocalBoundary SLR0
  external SLR1 externalOutputPipeline SLR1 pendingRequests SLR1 forwardTags SLR1
  coefficientDigitLink/sourceTx SLR0 coefficientDigitLink/middleRx SLR1
  coefficientDigitLink/middleTx SLR1 coefficientDigitLink/destinationRx SLR2
  forward/core/backend/generated/front SLR2 forward/core/backend/generated/back SLR1
} {
  if {[dict get $owners $cmux/$name] ne $slr} {error "wrong ownership $name"}
}
foreach cell [list $wrapper/blindRotate/loaderContext_reg $wrapper/blindRotate/exponentMemory_ext/memory $wrapper/sampleExtract/maskMemory_ext/memory $wrapper/extracting_reg] {
  if {[dict get $owners $cell] ne "SLR0"} {error "wide load/drain escaped SLR0: $cell"}
}
if {[dict exists $owners $bk] || [dict exists $owners $cmux] || [dict exists $owners $wrapper]} {
  error "coarse parent assignment accidentally moves BK/FFT/External Product"
}
set local_cell "$cmux/inverseOutputLocalBoundary/outputPayload_payloadBoundary/value_reg\[0\]"
if {[dict get $properties $local_cell USER_SLL_REG] ne "FALSE"} {error "local inverse uses SLL"}
if {[dict get $properties $wrapper/sampleExtract USER_SLR_ASSIGNMENT] ne "SLR0"} {
  error "old packaged sample-extraction assignment not overridden"
}
set prepared top/controller/preparedWordLink
fixture $prepared 0
fixture top/controller/chain 0
foreach role {sourceTx destinationRx} {
  fixture $prepared/$role 0
  for {set bit 0} {$bit < 32} {incr bit} {
    fixture "$prepared/$role/payload/value_reg\[$bit\]" 1
  }
  fixture "$prepared/$role/token/value_reg\[0\]" 1
}
set ::env(FPT_U280_PREPROCESSED_INPUT) 1
set owners [dict create]
fpt_apply_coefficient_slr0 $cmux
foreach {cell slr} [list top/controller/chain SLR1 $prepared/sourceTx SLR1 $prepared/destinationRx SLR0] {
  if {[dict get $owners $cell] ne $slr} {error "wrong prepared ownership $cell"}
}
foreach role {sourceTx destinationRx} {
  foreach cell [fpt_prepared_word_registers $prepared $role payload] {
    if {![dict get $properties $cell DONT_TOUCH] || ![dict get $properties $cell USER_SLL_REG]} {
      error "unprotected prepared payload $cell"
    }
  }
}
set missing "$prepared/destinationRx/payload/value_reg\[31\]"
dict unset fixtures $missing
if {![catch {fpt_prepared_word_registers $prepared destinationRx payload} message] || ![string match {*count 31*} $message]} {
  error "missing prepared receive bit accepted: $message"
}
fixture $missing 1
dict unset fixtures $prepared
if {![catch {fpt_prepared_word_root} message] || ![string match {*count 0*} $message]} {
  error "missing required prepared link accepted: $message"
}
fixture $prepared 0
unset ::env(FPT_U280_PREPROCESSED_INPUT)
puts "PREPARED_WORD_FLOORPLAN_TEST_PASS"
set ::env(FPT_U280_BUFFERED_OUTPUT) 1
fixture top/controller/sequencer/outputRetirement 0
fixture top/controller/completionLink/sourceTx 0
fixture top/controller/completionLink/destinationRx 0
foreach role {sourceTx destinationRx} {
  fixture "top/controller/completionLink/$role/payload/value_reg\[0\]" 1
  fixture "top/controller/completionLink/$role/token/value_reg\[0\]" 1
}
set owners [dict create]
fpt_apply_coefficient_slr0 $cmux
if {[dict get $owners top/controller/sequencer/outputRetirement] ne "SLR0"} {
  error "output retirement escaped SLR0"
}
if {[dict exists $owners top/controller/sequencer]} {error "entire sequencer moved"}
foreach {role slr} {sourceTx SLR0 destinationRx SLR1} {
  if {[dict get $owners top/controller/completionLink/$role] ne $slr} {
    error "wrong completion ownership $role"
  }
}
dict unset fixtures top/controller/sequencer/outputRetirement
if {![catch {fpt_apply_coefficient_slr0 $cmux} message] || ![string match *missing* $message]} {
  error "missing output island accepted: $message"
}
set ::env(FPT_U280_BUFFERED_OUTPUT) 0
dict unset fixtures $cmux/coefficientDigitLink/middleTx
if {![catch {fpt_apply_coefficient_slr0 $cmux} message] || ![string match *missing* $message]} {
  error "missing middle TX was not rejected: $message"
}
puts "COEFFICIENT_SLR0_FLOORPLAN_PASS"

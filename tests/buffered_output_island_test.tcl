set repo [file normalize [file join [file dirname [info script]] ..]]
source [file join $repo vitis/scripts/check_fpt_buffered_output_island.tcl]
set ::env(FPT_U280_BUFFERED_OUTPUT) 0
fpt_check_buffered_output_island unused
set ::env(FPT_U280_BUFFERED_OUTPUT) 1
set root top/controller/sequencer/outputRetirement
set bad none
proc get_cells {args} {
  set s [lindex $args end]
  if {[string first {NAME =~ */sequencer/outputRetirement} $s] == 0} {
    if {$::bad eq "missing"} {return {}}
    return [list $::root]
  }
  if {[string first slot $s] >= 0} {
    regexp {NAME =~ (.*)/slot} $s -> slice
    set width [expr {[string match *inputSlice $slice] ? 132 : 145}]
    set result {}
    foreach slot {slot0 slot1} {
      for {set i 0} {$i < $width} {incr i} {
        if {$::bad eq "lost_registers" && $i == 20} {continue}
        if {$::bad eq "missing_last" && $i == 0} {continue}
        if {$i > 0 && $i < $width-128} {continue}
        lappend result "${slice}/${slot}_reg\[$i\]"
        lappend result "${slice}/${slot}_reg\[$i\]_rep__0"
      }
    }
    return $result
  }
  if {[string first drainResponses $s] >= 0} {
    set n [expr {$::bad eq "missing_tile" ? 8 : 9}]
    set result {}; for {set i 0} {$i < $n} {incr i} {lappend result top/drainResponses/storageTile_$i}
    return $result
  }
  if {[string first /packer/ $s] >= 0} {return [list $::root/packer/data_reg]}
  if {[string first IS_PRIMITIVE $s] >= 0} {return [list $::root/packer/data_reg]}
  return [list $s]
}
proc get_property {key cell} {
  if {$key eq "NAME"} {return $cell}
  if {$key eq "REF_PIN_NAME"} {return [file tail $cell]}
  if {$key eq "REF_NAME"} {
    if {[string first storageTile $cell] >= 0} {
      if {$::bad eq "flow_tile"} {return FptCoefficientQueueTile_512_2}
      if {$::bad eq "helper_tile"} {return FptCoefficientQueueTile_512_2_NoFlow_helper}
      return ulp_FptBlindRotateKernel_1_0_FptCoefficientQueueTile_512_2_NoFlow_1764
    }
    return FDRE
  }
  error "unexpected property $key"
}
proc get_pins {args} {
  if {[string first {REF_PIN_NAME == D} [lindex $args end]] >= 0} {return [list $::root/packer/data_reg/D]}
  set slice [lindex $args 1]
  if {$::bad eq "missing_controls"} {return [list $slice/io_output_bits $slice/sig_s_ready_out_reg]}
  return [list $slice/io_output_bits $slice/io_input_ready $slice/io_output_valid \
    $slice/_inputSlice_io_output_valid $slice/io_outputData_valid \
    $slice/sig_s_ready_out_reg "${slice}/slot0_reg\[1\]_0"]
}
proc all_fanin {args} {
  foreach pin [lindex $args end] {
    if {[string match */sig_s_ready_out_reg $pin]} {return top/downstream/ready_reg}
  }
  set pin [lindex [lindex $args end] 0]
  if {[string match */D $pin]} {
    if {$::bad eq "consumer_bypass"} {return top/sampleExtract/data_reg}
    return [list $::root/packer/count_reg $::root/inputSlice/slot0_reg0]
  }
  set data [string match *bits $pin]
  if {$data && $::bad eq "bypass"} {return top/upstream/data_reg}
  if {$data && $::bad eq "fault_in_data"} {return $::root/fault_reg}
  if {!$data && $::bad eq "ready_bypass"} {return top/downstream/ready_reg}
  set result [list [file dirname $pin]/slot0_reg0]
  if {!$data} {lappend result $::root/stopStream_stopStream/value_reg $::root/outputActive_reg}
  return $result
}
proc fpt_require_placed_slr {cell slr} {
  if {$slr ne "SLR0" || $::bad eq "placement"} {error "wrong SLR"}
}
set f [file tempfile report]; close $f
fpt_check_buffered_output_island $report 1
foreach bad {missing missing_controls lost_registers missing_last missing_tile flow_tile helper_tile bypass fault_in_data ready_bypass consumer_bypass placement} {
  if {![catch {fpt_check_buffered_output_island $report 1} message]} {error "accepted $bad"}
}
file delete $report
foreach {name expected} {
  io_input_ready 1 io_output_valid 1 _inputSlice_io_output_valid 1 io_outputData_valid 1
  sig_s_ready_out_reg 0 io_input_ready_INST_0_i_1 0 slot0_reg 0
} {
  if {[fpt_buffered_interface_control $name] != $expected} {error "wrong interface classification: $name"}
}
foreach {name ref expected} [list \
  top/output_datamover/U0/GEN_S2MM_FULL.I_S2MM_FULL_WRAPPER/I_WR_DATA_CNTL/GEN_OMIT_INDET_BTT.sig_tlast_error_reg_reg FDRE 0 \
  top/output_datamover/U0/GEN_S2MM_FULL.I_S2MM_FULL_WRAPPER/I_CMD_STATUS/GEN_INCLUDE_STATUS_FIFO.I_STS_FIFO/USE_SRL_FIFO.I_SYNC_FIFO/I_SRL_FIFO_RBU_F/DYNSHREG_F_I/INFERRED_GEN.data_reg\[3\]\[0\]_srl4 SRL16E 0 \
  $root/stopStream_stopStream/value_reg FDRE 1 \
  top/controller/chain/fault_reg FDRE 0 \
  top/output_datamover/random/data_reg FDRE 0 \
  $root/packer/data_reg FDRE 0] {
  if {[fpt_buffered_control_source $root $name $ref] != $expected} {error "incorrect authorization source: $name"}
}
puts BUFFERED_OUTPUT_ISLAND_TEST_PASS

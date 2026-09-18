source [file join [file dirname [info script]] .. vitis scripts check_fpt_local_queues.tcl]
set checks 0
foreach queue {updateQueue drainResponses} {
  foreach name [list $queue/storageTile_0 top/$queue/storageTile_15 top/${queue}_${queue}/storageTile_8] {
    if {![fpt_local_queue_tile_matches $name $queue]} {error "valid queue tile rejected: $name"}
    incr checks
  }
  foreach name [list top/other_$queue/storageTile_0 top/$queue/storageTile_0/child \
      top/$queue/storageTile_extra top/otherQueue/storageTile_0 top/${queue}Other/storageTile_0] {
    if {[fpt_local_queue_tile_matches $name $queue]} {error "unrelated tile accepted: $name"}
    incr checks
  }
}
foreach queue {updateQueue drainResponses} {
  set tile top/${queue}_${queue}/storageTile_3
  foreach leaf {enqPointer_reg enqPointer_reg[0] deqPointer_reg[2] maybeFull_reg} {
    if {[fpt_local_queue_state_owner $tile/$leaf $queue] ne $tile} {error "state owner not found: $leaf"}
    incr checks
  }
  foreach leaf {enqPointer_reg_replica enqPointer_reg[0]/child maybeFull_reg_extra storage_reg[0]} {
    if {[fpt_local_queue_state_owner $tile/$leaf $queue] ne {}} {error "non-state accepted: $leaf"}
    incr checks
  }
}
foreach {tile queue width} {updateQueue/storageTile_0 updateQueue 512 updateQueue/storageTile_15 updateQueue 5 drainResponses/storageTile_8 drainResponses 1} {
  if {[fpt_local_queue_tile_width $tile $queue] != $width} {error "wrong tile width"}
  incr checks
}
foreach {width ram logic} {512 592 4 512 592 64 256 304 4 5 16 4 1 16 4} {
  fpt_local_queue_check_fanout $width $ram $logic
  incr checks
}
foreach script {
  {fpt_local_queue_check_fanout 512 593 0}
  {fpt_local_queue_check_fanout 512 0 65}
  {fpt_local_queue_check_fanout 256 305 0}
  {fpt_local_queue_check_fanout 5 17 0}
  {fpt_local_queue_check_fanout 1 17 0}
  {fpt_local_queue_check_fanout 0 0 0}
  {fpt_local_queue_tile_width updateQueue/storageTile_16 updateQueue}
} {
  if {![catch $script]} {error "invalid queue fanout accepted: $script"}
  incr checks
}
puts "LOCAL_COEFFICIENT_QUEUE_CHECKS_PASS checks=$checks"

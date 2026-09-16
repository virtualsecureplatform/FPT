source [file join [file dirname [info script]] .. vitis scripts protect_fpt_rolling_enables.tcl]
set tile {top/rollingHeadTile_0}
fpt_rolling_enable_locality $tile $tile/command_reg [lrepeat 192 $tile/headsReg/CE]
foreach {driver loads} [list $tile/command_reg {} $tile/command_reg [lrepeat 257 $tile/headsReg/CE] top/rollingHeadTile_8/command_reg [list $tile/headsReg/CE] $tile/command_reg {top/rollingHeadTile_08/headsReg/CE} $tile/command_reg {top/rollingHeadTile_8/headsReg/CE}] {
  if {![catch {fpt_rolling_enable_locality $tile $driver $loads}]} {error "bad enable topology accepted"}
}
puts "ROLLING_ENABLE_LOCALITY_PASS"

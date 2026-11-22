# Supplemental final-checkpoint DRC audit. Run in a separate Vivado process only
# after implementation has exited. The checkpoint is read, never rewritten.
# The original check_fpt_post_route.tcl remains independently required.
# Vivado 2022.2 UG835: reset_drc_check restores severity AND enable defaults.
# Native Vivado 2022.2 command help: -no_waivers includes waived violations.
# UG906 calls the corresponding report mode Ignore all waivers.

proc fpt_default_drc_snapshot {checks path} {
  set result [dict create]
  foreach check $checks {
    set name [get_property NAME $check]
    if {$name eq "" || [regexp {[\t\r\n]} $name] || [dict exists $result $name]} {
      error "invalid or duplicate DRC check name"
    }
    set enabled [get_property IS_ENABLED $check]
    if {![string is boolean -strict $enabled]} {
      error "invalid IS_ENABLED for $name"
    }
    dict set result $name [list [get_property SEVERITY $check] [expr {$enabled ? 1 : 0}]]
  }
  set out [open $path w]
  puts $out "check\tseverity\tenabled"
  foreach name [lsort [dict keys $result]] {
    puts $out "$name\t[join [dict get $result $name] \t]"
  }
  close $out
  return $result
}

proc fpt_audit_default_drc {output_dir} {
  if {[file exists $output_dir]} {
    if {![file isdirectory $output_dir]} {
      error "DRC audit output directory must be new or empty"
    }
    foreach entry [glob -nocomplain -directory $output_dir * .*] {
      if {[file tail $entry] ni {. ..}} {
        error "DRC audit output directory must be new or empty"
      }
    }
  }
  file mkdir $output_dir
  set checks [get_drc_checks]
  if {[llength $checks] == 0} {
    error "DRC audit found no checks"
  }
  set before [fpt_default_drc_snapshot $checks [file join $output_dir checks-before.tsv]]
  reset_drc_check $checks
  set after [fpt_default_drc_snapshot [get_drc_checks] [file join $output_dir checks-default.tsv]]
  if {[lsort [dict keys $before]] ne [lsort [dict keys $after]]} {
    error "DRC check inventory changed during factory-default reset"
  }
  if {![dict exists $after HDPR-5] ||
      [string toupper [lindex [dict get $after HDPR-5] 0]] ne "ERROR" ||
      [lindex [dict get $after HDPR-5] 1] != 1} {
    error "HDPR-5 must be enabled at its factory Error severity"
  }

  set report_name fpt_default_severity_drc
  report_drc -no_waivers -name $report_name \
    -file [file join $output_dir default-drc.rpt]
  set violations [get_drc_violations -name $report_name -quiet]
  set metrics [dict create defaults_restored 1 waivers_ignored 1 \
    checks [dict size $after] hdpr_5_enabled 1 hdpr_5_severity Error \
    violations [llength $violations] fatal 0 error 0 critical_warning 0 \
    warning 0 advisory 0 unclassified 0]
  set details [open [file join $output_dir violations.tsv] w]
  puts $details "violation\tseverity"
  foreach violation $violations {
    set severity [string toupper [string trim [get_property SEVERITY $violation]]]
    switch -- $severity {
      FATAL { set key fatal }
      ERROR { set key error }
      "CRITICAL WARNING" { set key critical_warning }
      WARNING { set key warning }
      ADVISORY { set key advisory }
      default { set key unclassified }
    }
    dict incr metrics $key
    puts $details "[get_property NAME $violation]\t$severity"
  }
  close $details
  set out [open [file join $output_dir metrics.tsv] w]
  puts $out "metric\tvalue"
  dict for {key value} $metrics { puts $out "$key\t$value" }
  close $out
  set failures {}
  foreach key {fatal error critical_warning unclassified} {
    if {[dict get $metrics $key] != 0} {
      lappend failures "$key=[dict get $metrics $key]"
    }
  }
  if {[llength $failures] != 0} {
    error "factory-default unwaived DRC failed: [join $failures {, }]"
  }
  puts "FPT_DEFAULT_DRC_PASS checks=[dict size $after] violations=[llength $violations] defaults_restored=1 waivers_ignored=1 hardware_unqualified=1"
  return $metrics
}

if {![info exists ::fpt_default_drc_library_only]} {
  if {$argc != 2} {
    error "usage: audit_fpt_unwaived_drc.tcl CHECKPOINT NEW_OUTPUT_DIRECTORY"
  }
  if {[catch {
    open_checkpoint [lindex $argv 0]
    fpt_audit_default_drc [lindex $argv 1]
    close_design
  } message]} {
    puts stderr "FPT_DEFAULT_DRC_FAIL $message"
    exit 1
  }
  exit 0
}

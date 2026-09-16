require 'pathname'
abort 'usage: lane_local_route_review.rb ATTEMPT' unless ARGV.size == 1
attempt = Pathname.new(ARGV[0]).realpath
metrics = File.readlines(attempt / 'post-route-metrics.tsv').drop(1).to_h { |l| l.strip.split("\t", 2) }
rows = File.readlines(attempt / 'post-route-metrics_timing_summary.rpt').map(&:split)
row = rows.find { |a| a[0] == 'clk_kernel_00_unbuffered_net' && a[1]&.match?(/^-?\d+\.\d+$/) }
abort 'missing kernel timing row' unless row
wns, tns = row[1, 2].map(&:to_f)
endpoints = Integer(row[3])
overall = Float(metrics.fetch('wns_ns'))
hold = Float(metrics.fetch('whs_ns'))
zero = %w[route_errors route_unrouted_nets route_partial_nets route_unplaced_nets
          route_gap_nets route_conflict_nets route_antenna_nets
          drc_fatal drc_error drc_critical_warning drc_unclassified]
clean = metrics.fetch('route_fully_routed') == '1' && zero.all? { |k| Integer(metrics.fetch(k)).zero? } && hold >= 0
full = clean && overall >= 0 && wns >= 0
partial = clean && overall >= -0.308 && wns >= -0.308 && tns >= -26.111 && endpoints <= 285 &&
          (wns >= -0.258 || tns >= -26.111 * 0.9)
puts "acceptance=#{full ? 'full' : partial ? 'partial_improvement_not_timing_closed' : 'not_accepted'}"
puts "overall_wns=#{overall} kernel_wns=#{wns} kernel_tns=#{tns} kernel_failing_endpoints=#{endpoints} hold_wns=#{hold}"
tag = attempt.basename.to_s.tr('-', '_')
log = attempt.parent.parent / "vitis/build/vpp_temp/hw_A_#{tag}/link/vivado/vpl/prj/prj.runs/impl_1/runme.log"
if log.file?
  text = log.read
  global = text.scan(/Estimated Global\/Short routing congestion is level (\d+)/).last&.first
  timing = text.scan(/Estimated Timing congestion is level (\d+)/).last&.first
  puts "global_short_congestion=#{global || 'unknown'} timing_congestion=#{timing || 'unknown'}"
  puts 'timing_congestion_level_reduced=false' if timing == '7'
end
puts 'No automatic retry, commit, or hardware programming.'

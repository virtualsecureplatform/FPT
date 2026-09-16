require 'tmpdir'
require 'open3'
require 'rbconfig'
require 'fileutils'

Dir.mktmpdir('fpt-route-review-') do |dir|
  attempt = File.join(dir, 'build', 'trial')
  FileUtils.mkdir_p(attempt)
  [
    [0.01, 0.01, 0.0, 0, 0.0, 'full'],
    [-0.258, -0.258, -26.111, 285, 0.0, 'partial_improvement_not_timing_closed'],
    [-0.308, -0.308, -20.0, 280, 0.0, 'partial_improvement_not_timing_closed'],
    [-0.308, -0.308, -26.111, 285, 0.0, 'not_accepted'],
    [-0.32, -0.2, -10.0, 100, 0.0, 'not_accepted'],
    [-0.2, -0.2, -10.0, 100, -0.01, 'not_accepted']
  ].each do |overall, kernel, tns, endpoints, hold, expected|
    metrics = {'route_fully_routed' => 1, 'wns_ns' => overall, 'whs_ns' => hold}
    %w[route_errors route_unrouted_nets route_partial_nets route_unplaced_nets
       route_gap_nets route_conflict_nets route_antenna_nets drc_fatal drc_error
       drc_critical_warning drc_unclassified].each { |k| metrics[k] = 0 }
    File.write(File.join(attempt, 'post-route-metrics.tsv'), "metric\tvalue\n" + metrics.map { |k, v| "#{k}\t#{v}\n" }.join)
    File.write(File.join(attempt, 'post-route-metrics_timing_summary.rpt'),
               "clk_kernel_00_unbuffered_net #{kernel} #{tns} #{endpoints} 1000000 #{hold}\n")
    output, status = Open3.capture2e(RbConfig.ruby, File.join(__dir__, 'lane_local_route_review.rb'), attempt)
    abort output unless status.success? && output.lines.first.strip == "acceptance=#{expected}"
  end
end
puts 'LANE_LOCAL_ROUTE_REVIEW_TEST_PASS'

require_relative 'lane_local_latency_gate'
def log(cycles, extra)
  "initial key load: accepted=128 cycles=#{128 + extra} commitExtra=#{extra}\nBlind Rotate: cycles=#{cycles},"
end
%w[baseline coefficient_only].each { |v| LaneLocalLatencyGate.check(log(20_852, 0), v) }
%w[key_only combined].each { |v| LaneLocalLatencyGate.check(log(20_852, 1), v) }
[[log(20_853, 0), 'baseline'], [log(20_872, 1), 'combined'],
 [log(20_852, 0), 'combined'], [log(20_852, 2), 'combined'], ['', 'baseline']].each do |args|
  failed = false
  begin
    LaneLocalLatencyGate.check(*args)
  rescue RuntimeError
    failed = true
  end
  abort "accepted invalid latency: #{args.inspect}" unless failed
end
puts 'LANE_LOCAL_LATENCY_TEST_PASS'

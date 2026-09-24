require_relative 'buffered_congestion_gate'
def estimates(global, timing)
  "Estimated Global/Short routing congestion is level #{global}\nEstimated Timing congestion is level #{timing}\n"
end
raise 'early estimate wrongly retained' unless BufferedCongestionGate.check(estimates(7, 7) + estimates(6, 6)) == {'global_short'=>6, 'timing'=>6}
report = "* No effective congestion windows are found above level 5\n"
raise 'routed bound rejected' unless BufferedCongestionGate.check("Estimated Timing congestion is level 6\n", 6, report) == {'global_short'=>5, 'timing'=>6}
begin
  BufferedCongestionGate.check("Estimated Timing congestion is level 6\n", 6,
    "* No effective congestion windows are found above level 7\n")
  raise 'unsafe routed bound accepted'
rescue RuntimeError => e
  raise if e.message == 'unsafe routed bound accepted'
end
[estimates(6, 7), estimates(7, 6), '', 'Estimated Timing congestion is level 6'].each do |text|
  rejected = false
  begin
    BufferedCongestionGate.check(text)
  rescue RuntimeError
    rejected = true
  end
  raise 'invalid final evidence accepted' unless rejected
end
puts 'BUFFERED_CONGESTION_TEST_PASS'

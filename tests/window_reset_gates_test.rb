require_relative 'window_reset_gates'
good="U280 CMUX latency 239, II 16\nBlind Rotate: cycles=20852,\ninitial key load: accepted=128 cycles=129 commitExtra=1"
WindowResetGates.latency(good)
[good.sub("239","240"),good.sub("20852","20853"),good.sub("129","128"),good+good].each do |bad|
  begin; WindowResetGates.latency(bad); rescue RuntimeError; next; end
  raise "bad latency accepted"
end
require 'tmpdir'
Dir.mktmpdir("window-reset-gate") do |dir|
  begin; WindowResetGates.metrics(dir); rescue Errno::ENOENT; missing=true; end
  raise "missing report accepted" unless missing
end
puts "WINDOW_RESET_GATES_TEST_PASS"

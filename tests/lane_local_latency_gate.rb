# Keep the preloaded numerical timer honest about the key commit edge.
module LaneLocalLatencyGate
  def self.check(log, variant)
    raise "unknown variant #{variant}" unless %w[baseline coefficient_only key_only combined].include?(variant)
    timings = log.scan(/Blind Rotate: cycles=(\d+),/)
    preload = log.scan(/initial key load: accepted=(\d+) cycles=(\d+) commitExtra=(\d+)/)
    raise 'missing or repeated batch/preload measurement' unless timings.size == 1 && preload.size == 1
    cycles = timings.first.first.to_i
    beats, load_cycles, extra = preload.first.map(&:to_i)
    expected_extra = %w[key_only combined].include?(variant) ? 1 : 0
    raise 'key-load commit latency changed' unless beats == 128 && extra == expected_extra && load_cycles == beats + extra
    total = cycles + extra
    if expected_extra.zero?
      raise "baseline schedule changed: #{cycles}" unless cycles == 20_852
    else
      raise "accounted latency exceeds budget: #{total}" unless total <= 20_872
    end
    "LATENCY_GATE_PASS variant=#{variant} compute=#{cycles} preloadExtra=#{extra} accounted=#{total}"
  end
end

if $PROGRAM_NAME == __FILE__
  abort 'usage: lane_local_latency_gate.rb LOG VARIANT' unless ARGV.size == 2
  puts LaneLocalLatencyGate.check(File.read(ARGV[0]), ARGV[1])
end

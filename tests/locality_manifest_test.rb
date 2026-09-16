# Compare coverage, widths and pipeline schedules without depending on island names.
abort "usage: BASELINE_DIR CANDIDATE_DIR" unless ARGV.size == 2
def entries(dir, direction, kind)
  File.foreach(File.join(dir, "#{direction}.v")).filter_map do |line|
    next unless line.start_with?("// #{kind} ")
    line.split.drop(2).to_h { |field| field.split("=", 2) }
  end
end
old = entries(ARGV[0], "inverse", "mux_island")
fresh = entries(ARGV[1], "inverse", "mux_island")
def capture_map(es)
  es.flat_map { |e| e.fetch("captures").split(",").map { |r| [r, [e.fetch("source"), e.fetch("cycles"), e.fetch("bit")]] } }.sort
end
abort "IFFT capture coverage/schedule changed" unless capture_map(old) == capture_map(fresh)
abort "oversized scalar island" unless fresh.all? { |e| (1..30).cover?(e.fetch("bits").to_i) }
old_twiddle = entries(ARGV[0], "forward", "twiddle_island")
new_twiddle = entries(ARGV[1], "forward", "twiddle_island")
abort "twiddle arithmetic/schedule changed" unless old_twiddle == new_twiddle
abort "wrong U280 scalar island count" unless fresh.size == 2307
abort "wrong U280 region count" unless entries(ARGV[1], "forward", "twiddle_region").size == 90
puts "LOCALITY_MANIFEST_PASS islands=#{fresh.size} regions=90 coverage=unchanged"

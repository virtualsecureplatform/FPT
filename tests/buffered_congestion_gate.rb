# Acceptance-only: intermediate congestion estimates must never abort P&R.
module BufferedCongestionGate
  def self.check(text, limit = 6, congestion_report = nil)
    patterns = {
      'global_short' => /Estimated Global\/Short routing congestion is level (\d+)/,
      'timing' => /Estimated Timing congestion is level (\d+)/
    }
    values = patterns.to_h do |name, pattern|
      match = text.scan(pattern).last
      if match
        [name, Integer(match.first)]
      elsif name == 'global_short' && congestion_report &&
            (bound = congestion_report.match(/^\* No effective congestion windows are found above level (\d+)\s*$/))
        # report_design_analysis states an upper bound when the router omits
        # its estimate because global/short congestion is already low.
        [name, Integer(bound[1])]
      else
        raise "missing final #{name} congestion evidence"
      end
    end
    values.each { |name, value| raise "#{name} congestion #{value} exceeds #{limit}" if value > limit }
    values
  end
end
if $PROGRAM_NAME == __FILE__
  begin
    report = ARGV[2] && File.read(ARGV[2])
    values = BufferedCongestionGate.check(File.read(ARGV.fetch(0)), Integer(ARGV.fetch(1, '6')), report)
    puts "BUFFERED_CONGESTION_PASS #{values.map { |k,v| "#{k}=#{v}" }.join(' ')}"
  rescue => e
    abort "BUFFERED_CONGESTION_FAIL #{e.message}"
  end
end

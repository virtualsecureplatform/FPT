module WindowResetGates
  def self.latency(text)
    raise "CMUX schedule changed" unless text.include?("U280 CMUX latency 239, II 16")
    cycles = text.scan(/Blind Rotate: cycles=(\d+),/).flatten
    preload = text.scan(/initial key load: accepted=(\d+) cycles=(\d+) commitExtra=(\d+)/)
    raise "batch latency changed" unless cycles == ["20852"]
    raise "key preload changed" unless preload == [["128","129","1"]]
    puts "WINDOW_RESET_LATENCY_PASS compute=20852 accounted=20853"
  end
  def self.metrics(dir)
    rows = File.readlines(File.join(dir,"post-route-metrics.tsv")).drop(1).to_h { |l| l.strip.split("\t",2) }
    timing = File.readlines(File.join(dir,"post-route-metrics_timing_summary.rpt")).map(&:split)
    kernel = timing.find { |v| v[0]=="clk_kernel_00_unbuffered_net" && v[1]&.match?(/^-?\d+\.\d+$/) }
    raise "missing kernel metrics" unless kernel
    [rows,Float(kernel[1]),Float(kernel[2]),Integer(kernel[3])]
  end
  def self.clean!(m)
    required = %w[route_errors route_unrouted_nets route_partial_nets route_unplaced_nets route_gap_nets route_conflict_nets route_antenna_nets drc_fatal drc_error drc_critical_warning drc_unclassified]
    raise "unclean route/DRC" unless m.fetch("route_fully_routed")=="1" && required.all? { |k| Integer(m.fetch(k))==0 }
    raise "hold failure" unless Float(m.fetch("whs_ns"))>=0
  end
end
if $PROGRAM_NAME == __FILE__
  mode,*args=ARGV
  case mode
  when "latency"
    WindowResetGates.latency(File.read(args.fetch(0)))
  when "resources"
    reports=args.map do |path|
      File.readlines(path).filter_map do |line|
        columns=line.split("|").map(&:strip)
        [columns[1],Integer(columns[2])] if ["CLB LUTs*","CLB Registers"].include?(columns[1])
      end.to_h
    end
    current,baseline=reports
    keys=["CLB LUTs*","CLB Registers"]
    puts "resource_improvement=#{keys.all? { |k| current.fetch(k)<baseline.fetch(k) }}"
    keys.each { |k| puts "#{k}: baseline=#{baseline.fetch(k)} current=#{current.fetch(k)} reduction=#{baseline.fetch(k)-current.fetch(k)}" }
  when "baseline"
    m,*_=WindowResetGates.metrics(args.fetch(0)); WindowResetGates.clean!(m)
    puts "WINDOW_RESET_BASELINE_PASS"
  when "review"
    current,baseline,log=args
    m,w,t,e=WindowResetGates.metrics(current)
    b,bw,bt,be=WindowResetGates.metrics(baseline)
    WindowResetGates.clean!(b)
    begin
      WindowResetGates.clean!(m); clean=true
    rescue
      clean=false
    end
    overall=Float(m.fetch("wns_ns")); bo=Float(b.fetch("wns_ns"))
    full=clean && w>=0 && overall>=0
    partial=clean && overall>=bo && w>=bw && t>=bt && e<=be && (w-bw>=0.05-1e-9 || t>=bt*0.9)
    puts "acceptance=#{full ? 'full' : partial ? 'partial_improvement_not_timing_closed' : 'not_accepted'}"
    puts "overall_wns=#{overall} kernel_wns=#{w} kernel_tns=#{t} kernel_endpoints=#{e}"
    text=File.read(log)
    puts "global_short_congestion=#{text.scan(/Estimated Global\/Short routing congestion is level (\d+)/).last&.first || 'unknown'}"
    puts "timing_congestion=#{text.scan(/Estimated Timing congestion is level (\d+)/).last&.first || 'unknown'}"
  else
    abort "usage: window_reset_gates.rb latency LOG | baseline DIR | resources REPORT BASELINE_REPORT | review DIR BASELINE RUN_LOG"
  end
end

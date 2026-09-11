#!/usr/bin/env ruby
# Generate a four-state, full-transform equivalence test in an artifact directory.
# Unlike Verilator's default initialization, the candidate begins with X state.
require 'fileutils'
abort 'usage: frame_control_xsim.rb reference.v candidate.v forward|inverse output_dir' unless ARGV.size == 4
reference, candidate, direction, output = ARGV
abort 'direction must be forward or inverse' unless %w[forward inverse].include?(direction)
old = File.read(reference)
fresh = File.read(candidate)
abort 'candidate is not token mode' unless fresh.include?('// FRAME_MODE token')
latency = fresh.match(/latency of (\d+) cycles/)[1].to_i
abort 'latency changed' unless old.match(/latency of (\d+) cycles/)[1].to_i == latency
lanes = direction == 'forward' ? 128 : 64
beats = 512 / lanes
recovery = latency + beats + 2
top = direction == 'forward' ? 'FptSGenForward' : 'FptSGenInverse'
modules = old.scan(/\bmodule\s+(\w+)/).flatten.uniq
old = old.gsub(/\b(?:#{modules.map { |m| Regexp.escape(m) }.join('|')})\b/) { |name| "#{name}XReference" }
FileUtils.mkdir_p(output)
File.write(File.join(output, 'reference.v'), old)
FileUtils.cp(candidate, File.join(output, 'candidate.v'))
ports = (0...lanes).map { |i| ".i#{i}(data[#{i}]), .o#{i}(RESULT[#{i}])" }.join(",\n")
File.write(File.join(output, 'tb.sv'), <<~SV)
  `timescale 1ns/1ps
  module frame_control_tb;
    reg clk = 0;
    always #2.5 clk = ~clk;
    reg reset = 1, reference_reset = 1, next = 0, checking = 0;
    reg [59:0] data [0:#{lanes-1}];
    wire [59:0] old_data [0:#{lanes-1}], new_data [0:#{lanes-1}];
    wire old_next, new_next;
    integer valid_left = 0, checked = 0, lane;
    #{top}XReference old_core(.clk(clk), .reset(reference_reset), .next(next), .next_out(old_next),
      #{ports.gsub('RESULT', 'old_data')});
    #{top} new_core(.clk(clk), .reset(reset), .next(next), .next_out(new_next),
      #{ports.gsub('RESULT', 'new_data')});
    always @(negedge clk) begin
      if (!checking || reset) valid_left = 0;
      else begin
        if ($isunknown(new_next) || new_next !== old_next) $fatal(1, "token mismatch/X");
        if (old_next) valid_left = #{beats};
        if (valid_left > 0) begin
          for (integer l = 0; l < #{lanes}; l = l+1)
            if ($isunknown(new_data[l]) || new_data[l] !== old_data[l])
              $fatal(1, "payload mismatch/X lane=%0d beat=%0d old=%h new=%h", l, checked, old_data[l], new_data[l]);
          checked = checked + 1;
          valid_left = valid_left - 1;
        end
      end
    end
    task tick;
      begin @(posedge clk); #1; end
    endtask
    task recover;
      begin
        // Legacy counters can be poisoned by X-valued unreset trigger delays
        // after a one-clock reset. Hold only the golden reference in reset
        // until those tokens flush. Candidate still receives a one-clock reset.
        checking = 0; next = 0; reset = 1; reference_reset = 1; tick(); reset = 0;
        repeat (#{recovery}) tick();
        reference_reset = 0; checking = 1;
      end
    endtask
    task send(input integer frames, input integer limit);
      begin
        next = 1; tick();
        for (integer b = 0; b < frames*#{beats} && b < limit; b = b+1) begin
          next = b % #{beats} == #{beats-1} && b+1 < frames*#{beats};
          for (integer l = 0; l < #{lanes}; l = l+1)
            data[l] = {$random, $random};
          tick();
        end
        next = 0;
      end
    endtask
    integer before_count;
    initial begin
      for (lane = 0; lane < #{lanes}; lane = lane+1) data[lane] = 0;
      recover(); before_count = checked;
      send(48, 100000); repeat (#{recovery}) tick();
      if (checked-before_count != 48*#{beats}) $fatal(1, "wrong frame count");
      send(8, 7); recover(); before_count = checked;
      send(25, 100000); repeat (#{recovery}) tick();
      if (checked-before_count != 25*#{beats}) $fatal(1, "wrong restart count");
      $display("FRAME_XSIM_PASS direction=#{direction} checked=%0d", checked);
      $finish;
    end
    initial begin #100000; $fatal(1, "timeout"); end
  endmodule
SV

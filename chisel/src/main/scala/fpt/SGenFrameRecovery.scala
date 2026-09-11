package fpt

import chisel3._
import chisel3.util._
import java.nio.file.{Files, Path}

/** Startup-only contract: drain unreset tokens before admitting a new frame.
  * Timing is read from the actual generated RTL, never guessed from a profile.
  */
private[fpt] object SGenFrameRecovery {
  def cycles(path: String, beats: Int, lead: Int = 1): Int = {
    if (path.isEmpty) return 0
    val text = Files.readString(Path.of(path))
    if (!text.contains("// FRAME_MODE token")) return 0
    val latency = "latency of (\\d+) cycles".r.findFirstMatchIn(text)
      .getOrElse(throw new IllegalArgumentException(s"missing frame latency in $path"))
      .group(1).toInt
    latency + lead + beats + 1
  }
  def configuredCycles: Int = {
    val profile = FptRtlProfile.named("paper-set-ii")
    Seq("FPT_SGEN_FORWARD" -> profile.forward.frameBeats,
      "FPT_SGEN_INVERSE" -> profile.inverse.frameBeats).map { case (key, beats) =>
      sys.env.get(key).map(cycles(_, beats)).getOrElse(0)
    }.max
  }
  def ready(cycles: Int): Bool = {
    require(cycles >= 0)
    if (cycles == 0) true.B else {
      val remaining = RegInit(cycles.U(log2Ceil(cycles + 1).W))
      remaining.suggestName("frameRecoveryRemaining")
      when(remaining =/= 0.U) { remaining := remaining - 1.U }
      remaining === 0.U
    }
  }
}

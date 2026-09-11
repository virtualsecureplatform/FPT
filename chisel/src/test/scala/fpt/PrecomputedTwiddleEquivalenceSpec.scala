package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}

private class PrecomputedTwiddleComparison(reference: String, candidate: String) extends Module {
  val config = FptRtlProfile.named("paper-set-ii").forward
  val io = IO(new Bundle {
    val start = Input(Bool())
    val valid = Input(Bool())
    val data = Input(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val outputValid = Output(Bool())
    val equal = Output(Bool())
  })
  val baseline = Module(new SGenCyclicBackend(config, "FptSGenReference", reference))
  val optimized = Module(new SGenCyclicBackend(config, "FptSGenForward", candidate))
  for (core <- Seq(baseline, optimized)) {
    core.io.start := io.start
    core.io.inputValid := io.valid
    core.io.input := io.data
    core.io.outputReady := true.B
  }
  io.outputValid := baseline.io.outputValid
  io.equal := (baseline.io.outputValid === optimized.io.outputValid) &&
    (baseline.io.inputReady === optimized.io.inputReady) &&
    (baseline.io.done === optimized.io.done) &&
    (!baseline.io.outputValid || baseline.io.output.asUInt === optimized.io.output.asUInt)
}

final class PrecomputedTwiddleEquivalenceSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  "Precomputed twiddles" should "preserve every output bit and cycle across frames, gaps and reset" in {
    if (!sys.env.get("FPT_PRECOMPUTED_TWIDDLE_EQUIVALENCE").contains("1"))
      cancel("set FPT_PRECOMPUTED_TWIDDLE_EQUIVALENCE=1 with reference and candidate RTL")
    val sourceReference = Path.of(sys.env("FPT_SGEN_REFERENCE")).toAbsolutePath
    val referenceText = Files.readString(sourceReference)
    val modules = "\\bmodule\\s+(\\w+)".r.findAllMatchIn(referenceText).map(_.group(1)).toSeq.distinct
    val names = modules.map(n => n -> (if (n == "FptSGenForward" || n == "FptSGenReference")
      "FptSGenReference" else n + "TwiddleReference")).toMap
    val pattern = ("\\b(?:" + modules.map(java.util.regex.Pattern.quote).mkString("|") + ")\\b").r
    val directory = Path.of("target", "local-twiddle-equivalence").toAbsolutePath
    Files.createDirectories(directory)
    val reference = directory.resolve("reference.v")
    Files.writeString(reference, pattern.replaceAllIn(referenceText, m => names(m.matched)))
    val candidate = Path.of(sys.env("FPT_SGEN_FORWARD")).toAbsolutePath
    def latency(source: Path): Int = "latency of (\\d+) cycles".r
      .findFirstMatchIn(Files.readString(source)).get.group(1).toInt
    latency(candidate) should be(latency(reference))
    val recovery = latency(candidate) + 32
    test(new PrecomputedTwiddleComparison(reference.toString, candidate.toString))
      .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
        val config = dut.config
        config.frameBeats should be(4)
        val random = new scala.util.Random(0x465054)
        val limit = BigInt(1) << (config.dataWidth - 1)
        var received = 0
        def tick(): Unit = {
          dut.io.equal.expect(true.B)
          if (dut.io.outputValid.peek().litToBoolean) received += 1
          dut.clock.step()
        }
        def idle(): Unit = {
          dut.io.start.poke(false.B)
          dut.io.valid.poke(false.B)
        }
        def reset(): Unit = {
          idle()
          dut.reset.poke(true.B)
          dut.clock.step(3)
          dut.reset.poke(false.B)
          dut.clock.step(recovery)
          received = 0
        }
        for (lane <- 0 until config.lanes) {
          dut.io.data(lane).real.poke(0.S)
          dut.io.data(lane).imag.poke(0.S)
        }
        def send(frames: Int): Unit = {
          dut.io.start.poke(true.B)
          tick()
          for (frame <- 0 until frames; beat <- 0 until config.frameBeats) {
            dut.io.valid.poke(true.B)
            dut.io.start.poke((beat == config.frameBeats - 1 && frame + 1 < frames).B)
            for (lane <- 0 until config.lanes) {
              def value(): BigInt = if (frame < 2)
                Seq(-limit, limit - 1, BigInt(0), BigInt(-1), BigInt(1))((lane + beat + frame) % 5)
              else BigInt(config.dataWidth, random) - limit
              dut.io.data(lane).real.poke(value().S)
              dut.io.data(lane).imag.poke(value().S)
            }
            tick()
          }
          idle()
        }
        reset()
        send(32)
        for (_ <- 0 until 100) tick()
        received should be(128)
        send(7)
        for (_ <- 0 until 100) tick()
        received should be(156)
        send(2)
        reset()
        // SGen's existing token delay registers are not all resettable.
        // Compare the residual pipeline too, then restart after it drains.
        for (_ <- 0 until 100) tick()
        received = 0
        send(19)
        for (_ <- 0 until 100) tick()
        received should be(76)
      }
  }
}

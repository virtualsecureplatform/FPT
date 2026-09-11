package fpt

import chisel3._
import chisel3.util.ShiftRegister
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}

private class BankedShuffleComparison(reference: String, candidate: String, delta: Int) extends Module {
  val config = FptRtlProfile.named("paper-set-ii").forward
  val io = IO(new Bundle {
    val start = Input(Bool())
    val valid = Input(Bool())
    val data = Input(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val outputValid = Output(Bool())
    val equal = Output(Bool())
  })
  val baseline = Module(new SGenCyclicBackend(config, "FptBankedShuffleReference", reference))
  val candidateCore = Module(new SGenCyclicBackend(config, "FptBankedShuffleCandidate", candidate))
  for (core <- Seq(baseline, candidateCore)) {
    core.io.start := io.start
    core.io.inputValid := io.valid
    core.io.input := io.data
    core.io.outputReady := true.B
  }
  val early = if (delta >= 0) baseline else candidateCore
  val late = if (delta >= 0) candidateCore else baseline
  val earlyValid = ShiftRegister(early.io.outputValid, math.abs(delta), false.B, true.B)
  val earlyData = ShiftRegister(early.io.output.asUInt, math.abs(delta))
  val earlyDone = ShiftRegister(early.io.done, math.abs(delta), false.B, true.B)
  io.outputValid := late.io.outputValid
  io.equal := (earlyValid === late.io.outputValid) && (earlyDone === late.io.done) &&
    (baseline.io.inputReady === candidateCore.io.inputReady) &&
    (!late.io.outputValid || earlyData === late.io.output.asUInt)
}

final class BankedShuffleEquivalenceSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  "Banked shuffle FFT" should "preserve every bit, ordering and declared latency across frames and resets" in {
    if (!sys.env.get("FPT_BANKED_SHUFFLE_EQUIVALENCE").contains("1")) cancel("enable FPT_BANKED_SHUFFLE_EQUIVALENCE")
    val (reference, candidate) = SGenEquivalenceSources.stage(
      Path.of(sys.env("FPT_SGEN_REFERENCE")).toAbsolutePath,
      Path.of(sys.env("FPT_SGEN_FORWARD")).toAbsolutePath,
      Path.of("target", "banked-shuffle-equivalence"))
    def latency(path: Path): Int = "latency of (\\d+) cycles".r.findFirstMatchIn(Files.readString(path)).get.group(1).toInt
    val delta = latency(candidate) - latency(reference)
    info(s"forward latency ${latency(reference)} -> ${latency(candidate)}, delta=$delta")
    test(new BankedShuffleComparison(reference.toString, candidate.toString, delta))
      .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
        val random = new scala.util.Random(0x42414e4b)
        val config = dut.config
        val limit = BigInt(1) << (config.dataWidth - 1)
        var received = 0
        def idle(): Unit = { dut.io.start.poke(false.B); dut.io.valid.poke(false.B) }
        def tick(): Unit = {
          dut.io.equal.expect(true.B)
          if (dut.io.outputValid.peek().litToBoolean) received += 1
          dut.clock.step()
        }
        def resetAndDrain(): Unit = {
          idle(); dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
          // Aborted frames have no valid payload contract; drain unreset SGen token state.
          dut.clock.step(128 + math.abs(delta)); received = 0
        }
        for (lane <- 0 until config.lanes) {
          dut.io.data(lane).real.poke(0.S); dut.io.data(lane).imag.poke(0.S)
        }
        def send(frames: Int): Unit = {
          dut.io.start.poke(true.B); tick()
          for (frame <- 0 until frames; beat <- 0 until 4) {
            dut.io.valid.poke(true.B)
            dut.io.start.poke((beat == 3 && frame + 1 < frames).B)
            for (lane <- 0 until config.lanes) {
              def value(): BigInt = if (frame < 2)
                Seq(-limit, limit-1, BigInt(0), BigInt(-1), BigInt(1))((lane+beat+frame)%5)
              else BigInt(config.dataWidth, random) - limit
              dut.io.data(lane).real.poke(value().S); dut.io.data(lane).imag.poke(value().S)
            }
            tick()
          }
          idle()
        }
        resetAndDrain()
        send(64); for (_ <- 0 until 128 + math.abs(delta)) tick()
        received should be(256)
        send(7); for (_ <- 0 until 128 + math.abs(delta)) tick()
        received should be(284)
        send(2); resetAndDrain()
        send(19); for (_ <- 0 until 128 + math.abs(delta)) tick()
        received should be(76)
      }
  }
}

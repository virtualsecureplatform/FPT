package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.{Files, Path}

private class InverseControlComparison(reference: String, candidate: String) extends Module {
  val config = FptRtlProfile.named("paper-set-ii").inverse
  val io = IO(new Bundle {
    val start = Input(Bool())
    val valid = Input(Bool())
    val data = Input(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val outputValid = Output(Bool())
    val equal = Output(Bool())
  })
  val baseline = Module(new SGenCyclicBackend(config, "FptSGenInverseReference", reference))
  val local = Module(new SGenCyclicBackend(config, "FptSGenInverse", candidate))
  for (core <- Seq(baseline, local)) {
    core.io.start := io.start
    core.io.inputValid := io.valid
    core.io.input := io.data
    core.io.outputReady := true.B
  }
  io.outputValid := local.io.outputValid
  io.equal := (baseline.io.outputValid === local.io.outputValid) &&
    (baseline.io.done === local.io.done) && (baseline.io.inputReady === local.io.inputReady) &&
    (!local.io.outputValid || baseline.io.output.asUInt === local.io.output.asUInt)
}

final class InverseControlEquivalenceSpec extends AnyFlatSpec with ChiselScalatestTester {
  "IFFT local selectors" should "preserve every output bit and cycle across frames and resets" in {
    if (!sys.env.get("FPT_INVERSE_CONTROL_EQUIVALENCE").contains("1")) cancel("enable FPT_INVERSE_CONTROL_EQUIVALENCE")
    val original = Path.of(sys.env("FPT_SGEN_INVERSE_REFERENCE")).toAbsolutePath
    val candidate = Path.of(sys.env("FPT_SGEN_INVERSE")).toAbsolutePath
    val text = Files.readString(original)
    def latency(s: String): Int = "latency of (\\d+) cycles".r.findFirstMatchIn(s).get.group(1).toInt
    assert(latency(text) == latency(Files.readString(candidate)))
    val directory = Path.of("target", "inverse-control-equivalence").toAbsolutePath
    Files.createDirectories(directory)
    val reference = directory.resolve("FptSGenInverseReference.v")
    Files.writeString(reference, text.replaceAll("\\bFptSGenInverse\\b", "FptSGenInverseReference"))
    test(new InverseControlComparison(reference.toString, candidate.toString))
      .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
        val random = new scala.util.Random(0x49464654L)
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
          dut.clock.step(latency(text) + 32); received = 0
        }
        for (lane <- 0 until config.lanes) {
          dut.io.data(lane).real.poke(0.S); dut.io.data(lane).imag.poke(0.S)
        }
        def send(frames: Int): Unit = {
          dut.io.start.poke(true.B); tick()
          for (frame <- 0 until frames; beat <- 0 until config.frameBeats) {
            dut.io.valid.poke(true.B)
            dut.io.start.poke((beat == config.frameBeats - 1 && frame + 1 < frames).B)
            for (lane <- 0 until config.lanes) {
              def value(): BigInt = if (frame < 2)
                Seq(-limit, limit - 1, BigInt(0), BigInt(-1), BigInt(1))((lane + beat + frame) % 5)
              else BigInt(config.dataWidth, random) - limit
              dut.io.data(lane).real.poke(value().S); dut.io.data(lane).imag.poke(value().S)
            }
            tick()
          }
          idle()
        }
        resetAndDrain()
        for (frames <- Seq(64, 7)) {
          val before = received
          send(frames); for (_ <- 0 until latency(text) + 32) tick()
          assert(received - before == frames * config.frameBeats)
        }
        send(2); resetAndDrain()
        send(19); for (_ <- 0 until latency(text) + 32) tick()
        assert(received == 19 * config.frameBeats)
      }
  }
}

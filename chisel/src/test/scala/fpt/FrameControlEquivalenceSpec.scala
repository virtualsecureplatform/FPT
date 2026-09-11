package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import java.nio.file.{Files, Path}
import org.scalatest.flatspec.AnyFlatSpec

private class FrameControlMiter(val config: TransformConfig, reference: String,
    candidate: String, referenceName: String, candidateName: String) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val valid = Input(Bool())
    val data = Input(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val equal = Output(Bool())
    val validOut = Output(Bool())
  })
  val old = Module(new SGenCyclicBackend(config, referenceName, reference))
  val fresh = Module(new SGenCyclicBackend(config, candidateName, candidate))
  for (core <- Seq(old, fresh)) {
    core.io.start := io.start
    core.io.inputValid := io.valid
    core.io.input := io.data
    core.io.outputReady := true.B
  }
  io.validOut := fresh.io.outputValid
  io.equal := old.io.inputReady === fresh.io.inputReady &&
    old.io.outputValid === fresh.io.outputValid && old.io.done === fresh.io.done &&
    (!fresh.io.outputValid || old.io.output.asUInt === fresh.io.output.asUInt)
}

final class FrameControlEquivalenceSpec extends AnyFlatSpec with ChiselScalatestTester {
  for (direction <- Seq("forward", "inverse")) {
    "frame-token control" should s"preserve $direction bits and cycles, including reset recovery" in {
      if (!sys.env.get("FPT_FRAME_CONTROL_EQUIVALENCE").contains("1")) cancel("enable FPT_FRAME_CONTROL_EQUIVALENCE")
      val root = Path.of(sys.env("FPT_FRAME_REFERENCE_DIR"))
      val source = Files.readString(root.resolve(s"$direction.v"))
      val names = "\\bmodule\\s+(\\w+)".r.findAllMatchIn(source).map(_.group(1)).toSeq.distinct
      val renamed = names.foldLeft(source)((text, name) =>
        text.replaceAll(s"\\b${java.util.regex.Pattern.quote(name)}\\b", name + "FrameReference"))
      val directory = Path.of("target", "frame-control-equivalence", direction).toAbsolutePath
      Files.createDirectories(directory)
      val reference = directory.resolve("reference.v")
      Files.writeString(reference, renamed)
      val candidate = sys.env(s"FPT_SGEN_${direction.toUpperCase}")
      val moduleName = if (direction == "forward") "FptSGenForward" else "FptSGenInverse"
      val profile = FptRtlProfile.named("paper-set-ii")
      val config = if (direction == "forward") profile.forward else profile.inverse
      val recovery = SGenFrameRecovery.cycles(candidate, config.frameBeats)
      assert(recovery > 0)
      def latency(s: String): Int = "latency of (\\d+) cycles".r.findFirstMatchIn(s).get.group(1).toInt
      assert(latency(source) == latency(Files.readString(Path.of(candidate))))
      test(new FrameControlMiter(config, reference.toString, candidate,
        moduleName + "FrameReference", moduleName))
        .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
          val random = new scala.util.Random(0x4652414dL)
          var received = 0
          def idle(): Unit = { dut.io.start.poke(false.B); dut.io.valid.poke(false.B) }
          def tick(): Unit = {
            dut.io.equal.expect(true.B)
            if (dut.io.validOut.peek().litToBoolean) received += 1
            dut.clock.step()
          }
          def recover(): Unit = {
            idle(); dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
            for (_ <- 0 until recovery) {
              dut.io.validOut.expect(false.B)
              dut.clock.step()
            }
            received = 0
          }
          def send(frames: Int, abortAfter: Int = Int.MaxValue): Unit = {
            dut.io.start.poke(true.B); tick()
            for (index <- 0 until math.min(frames * config.frameBeats, abortAfter)) {
              dut.io.valid.poke(true.B)
              dut.io.start.poke((index % config.frameBeats == config.frameBeats-1 &&
                index + 1 < frames * config.frameBeats).B)
              for (lane <- 0 until config.lanes) {
                val limit = BigInt(1) << (config.dataWidth-1)
                def value(): BigInt = if (index < 2 * config.frameBeats)
                  Seq(-limit, limit-1, BigInt(0), BigInt(-1), BigInt(1))((index+lane)%5)
                  else BigInt(config.dataWidth, random)-limit
                dut.io.data(lane).real.poke(value().S)
                dut.io.data(lane).imag.poke(value().S)
              }
              tick()
            }
            idle()
          }
          for (lane <- 0 until config.lanes) {
            dut.io.data(lane).real.poke(0.S); dut.io.data(lane).imag.poke(0.S)
          }
          recover()
          for (frames <- Seq(48, 7, 13)) {
            val before = received
            send(frames); for (_ <- 0 until recovery) tick()
            assert(received-before == frames * config.frameBeats)
          }
          for (abort <- Seq(1, 7, 20)) {
            send(8, abort); recover()
            send(25); for (_ <- 0 until recovery) tick()
            assert(received == 25 * config.frameBeats)
          }
        }
    }
  }
}

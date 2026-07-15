package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

final class SGenBlackBoxSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val config = TransformConfig(
    points = 16,
    lanes = 4,
    dataWidth = 30,
    twiddleWidth = 26,
    twiddleFractionalBits = 24
  )

  private def existingPath(candidates: Seq[Path], description: String): Path =
    candidates.find(Files.exists(_)).getOrElse(fail(s"Could not find $description"))

  private def vectorPath(name: String): Path = existingPath(
    Seq(
      Path.of("..", "build", name),
      Path.of("..", "build-tfhepp", name),
      Path.of("build", name)
    ),
    s"$name; build the C++ RTL vectors first"
  )

  private def generatedPath(name: String): Path = existingPath(
    Seq(
      Path.of(
        "src",
        "test",
        "resources",
        "generated",
        "FptSGenForward16x4.v"
      ),
      Path.of("..", "build", "sgen-blackbox", name),
      Path.of("build", "sgen-blackbox", name)
    ).map(_.toAbsolutePath.normalize),
    s"generated SGen $name; run tools/generate_sgen_fpt.sh first"
  )

  private def vectors(name: String): Seq[Array[BigInt]] =
    Files
      .readAllLines(vectorPath(name))
      .asScala
      .filter(_.trim.nonEmpty)
      .map(_.trim.split("\\s+").map(BigInt(_)))
      .toSeq

  private def signed(raw: BigInt, width: Int): BigInt = {
    val modulus = BigInt(1) << width
    val masked = raw & (modulus - 1)
    if (masked.testBit(width - 1)) masked - modulus else masked
  }

  private def wrappedDifference(
      actual: BigInt,
      expected: BigInt,
      width: Int
  ): BigInt = signed(actual - expected, width).abs

  behavior of "the generated SGen cyclic FFT BlackBox"

  it should "stream C++ reference frames through the typed Chisel boundary" in {
    val frames = vectors("rtl_sgen_fft_vectors.txt").grouped(config.points).toSeq
    val generated = generatedPath("forward.v")

    test(
      new SGenCyclicBackend(
        config,
        moduleName = "FptSGenForward",
        verilogPath = generated.toString
      )
    ).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.io.start.poke(false.B)
      dut.io.inputValid.poke(false.B)
      dut.io.outputReady.poke(true.B)
      for (lane <- 0 until config.lanes) {
        dut.io.input(lane).real.poke(0.S)
        dut.io.input(lane).imag.poke(0.S)
      }
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      var maximumError = BigInt(0)
      var outputFrame = 0
      var outputBeat = 0

      def sampleOutput(): Unit =
        if (dut.io.outputValid.peek().litToBoolean) {
          outputFrame should be < frames.size
          for (lane <- 0 until config.lanes) {
            val index = outputBeat * config.lanes + lane
            val row = frames(outputFrame)(index)
            val actualReal = signed(
              dut.io.output(lane).real.peek().litValue,
              config.dataWidth
            )
            val actualImag = signed(
              dut.io.output(lane).imag.peek().litValue,
              config.dataWidth
            )
            val realError = wrappedDifference(
              actualReal,
              row(2),
              config.dataWidth
            )
            val imagError = wrappedDifference(
              actualImag,
              row(3),
              config.dataWidth
            )
            maximumError = maximumError.max(realError).max(imagError)
            withClue(
              s"frame=$outputFrame index=$index actual=($actualReal,$actualImag) " +
                s"expected=(${row(2)},${row(3)}) "
            ) {
              realError should be <= BigInt(64)
              imagError should be <= BigInt(64)
            }
          }
          if (outputBeat == config.frameBeats - 1) {
            outputBeat = 0
            outputFrame += 1
          } else {
            outputBeat += 1
          }
        }

      // SGen's full-throughput schedule starts a frame every frameBeats
      // cycles. The first marker is one cycle before its first input beat;
      // subsequent markers overlap the preceding frame's final beat.
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      for ((frame, frameIndex) <- frames.zipWithIndex) {
        for (beat <- 0 until config.frameBeats) {
          dut.io.inputReady.expect(true.B)
          for (lane <- 0 until config.lanes) {
            val row = frame(beat * config.lanes + lane)
            dut.io.input(lane).real.poke(row(0).S)
            dut.io.input(lane).imag.poke(row(1).S)
          }
          dut.io.inputValid.poke(true.B)
          dut.io.start.poke(
            (beat == config.frameBeats - 1 && frameIndex + 1 < frames.size).B
          )
          sampleOutput()
          dut.clock.step()
        }
      }
      dut.io.start.poke(false.B)
      dut.io.inputValid.poke(false.B)

      var drainCycles = 0
      while (outputFrame < frames.size) {
        sampleOutput()
        dut.clock.step()
        drainCycles += 1
        drainCycles should be < 100
      }
      info(s"maximum generated-SGen cyclic FFT error: $maximumError raw units")
    }
  }
}

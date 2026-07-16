package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

/** Opt-in numerical validation of the actual generated SGen tangent transforms
  * for either supported arithmetic profile. Each vector row contains the
  * input, the fixed radix-2 C++ result, and the quantized double-precision
  * tangent-transform result. The latter is the accuracy oracle; the radix-2
  * result makes differing rounding order visible.
  */
final class PaperSGenNumericalSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private def requireEnabled(): Unit =
    if (!sys.env.get("FPT_PAPER_SGEN_NUMERICS").contains("1")) {
      cancel(
        "set FPT_PAPER_SGEN_NUMERICS=1 to run the generated transforms"
      )
    }

  private def requiredPath(environment: String, default: String): Path = {
    val path = Path
      .of(sys.env.getOrElse(environment, default))
      .toAbsolutePath
      .normalize
    require(Files.isRegularFile(path), s"missing $path")
    path
  }

  private def vectors(path: Path): Seq[Array[BigInt]] =
    Files
      .readAllLines(path)
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

  private def annotations = Seq(
    VerilatorBackendAnnotation,
    PaperVerilator.flags
  )

  private def rtlProfile: FptRtlProfile = FptRtlProfile.named(
    sys.env.getOrElse("FPT_ARITHMETIC_PROFILE", "paper-set-ii")
  )

  behavior of "the generated SGen tangent transforms"

  it should "bound forward FTT error against the C++ oracle" in {
    requireEnabled()
    val source = requiredPath("FPT_SGEN_FORWARD", "../build/sgen-fpt/forward.v")
    val rows = vectors(
      requiredPath(
        "FPT_PAPER_SGEN_FORWARD_VECTORS",
        "../build/rtl_paper_sgen_forward_vectors.txt"
      )
    )
    val profile = rtlProfile
    val config = profile.forward
    val frames = rows.grouped(config.points).toSeq
    frames should not be empty
    rows.foreach(_.length should be(6))

    test(
      new SGenCyclicBackend(
        config,
        "FptSGenForward",
        source.toString
      )
    ).withAnnotations(annotations) { dut =>
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

      var maximumReferenceError = BigInt(0)
      var maximumRadix2Error = BigInt(0)
      var worstReference = ""
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
            val referenceError = wrappedDifference(
              actualReal,
              row(4),
              config.dataWidth
            ).max(
              wrappedDifference(actualImag, row(5), config.dataWidth)
            )
            val radix2Error = wrappedDifference(
              actualReal,
              row(2),
              config.dataWidth
            ).max(
              wrappedDifference(actualImag, row(3), config.dataWidth)
            )
            if (referenceError > maximumReferenceError) {
              maximumReferenceError = referenceError
              worstReference =
                s"frame=$outputFrame index=$index actual=($actualReal,$actualImag) " +
                  s"reference=(${row(4)},${row(5)}) " +
                  s"radix2=(${row(2)},${row(3)})"
            }
            maximumRadix2Error = maximumRadix2Error.max(radix2Error)
          }
          if (outputBeat == config.frameBeats - 1) {
            outputBeat = 0
            outputFrame += 1
          } else outputBeat += 1
        }

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
        drainCycles should be < 200
      }

      info(
        s"${profile.profileName} forward maximum error: " +
          s"reference=$maximumReferenceError " +
          s"radix2=$maximumRadix2Error ($worstReference)"
      )
      val tolerance = BigInt(2048) <<
        (profile.arithmetic.forwardFft.fractionalBits - 12)
      withClue(s"worst forward output $worstReference: ") {
        maximumReferenceError should be <= tolerance
      }
    }
  }

  it should "bound inverse FTT error against the C++ oracle" in {
    requireEnabled()
    val source = requiredPath("FPT_SGEN_INVERSE", "../build/sgen-fpt/inverse.v")
    val rows = vectors(
      requiredPath(
        "FPT_PAPER_SGEN_INVERSE_VECTORS",
        "../build/rtl_paper_sgen_inverse_vectors.txt"
      )
    )
    val profile = rtlProfile
    val config = profile.inverse
    val frames = rows.grouped(config.points).toSeq
    frames should not be empty
    rows.foreach(_.length should be(6))

    test(
      new SGenCyclicBackend(
        config,
        "FptSGenInverse",
        source.toString
      )
    ).withAnnotations(annotations) { dut =>
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

      var maximumReferenceError = BigInt(0)
      var maximumRadix2Error = BigInt(0)
      var worstReference = ""
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
            val referenceError = wrappedDifference(
              actualReal,
              row(4),
              config.dataWidth
            ).max(
              wrappedDifference(actualImag, row(5), config.dataWidth)
            )
            val radix2Error = wrappedDifference(
              actualReal,
              row(2),
              config.dataWidth
            ).max(
              wrappedDifference(actualImag, row(3), config.dataWidth)
            )
            if (referenceError > maximumReferenceError) {
              maximumReferenceError = referenceError
              worstReference =
                s"frame=$outputFrame index=$index actual=($actualReal,$actualImag) " +
                  s"reference=(${row(4)},${row(5)}) " +
                  s"radix2=(${row(2)},${row(3)})"
            }
            maximumRadix2Error = maximumRadix2Error.max(radix2Error)
          }
          if (outputBeat == config.frameBeats - 1) {
            outputBeat = 0
            outputFrame += 1
          } else outputBeat += 1
        }

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
        drainCycles should be < 250
      }

      info(
        s"${profile.profileName} inverse maximum error: " +
          s"reference=$maximumReferenceError " +
          s"radix2=$maximumRadix2Error ($worstReference)"
      )
      val tolerance = BigInt(8) <<
        (profile.arithmetic.inverseFft.fractionalBits - 3)
      withClue(s"worst inverse output $worstReference: ") {
        maximumReferenceError should be <= tolerance
      }
    }
  }
}

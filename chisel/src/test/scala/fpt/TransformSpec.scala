package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

final class TransformSpec
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

  private def vectorPath(name: String): Path = {
    val candidates = Seq(
      Path.of("..", "build", name),
      Path.of("..", "build-tfhepp", name),
      Path.of("build", name)
    )
    candidates.find(Files.exists(_)).getOrElse(
      fail(s"Could not find $name; build the C++ RTL vectors first")
    )
  }

  private def vectors(name: String): Seq[Array[BigInt]] =
    Files
      .readAllLines(vectorPath(name))
      .asScala
      .filter(_.trim.nonEmpty)
      .map(_.trim.split("\\s+").map(BigInt(_)))
      .toSeq

  private def reset(dut: Module): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def pokeTwiddle(
      twiddle: GaussTwiddle,
      row: Array[BigInt]
  ): Unit = {
    twiddle.c.poke(row(0).S)
    twiddle.cMinusD.poke(row(1).S)
    twiddle.cPlusD.poke(row(2).S)
  }

  behavior of "FPT Chisel transforms"

  it should "match all multi-lane cyclic FFT frames" in {
    val rows = vectors("rtl_fft_vectors.txt")
    val twiddles = vectors("rtl_fft_twiddles.txt")
    val frames = rows.grouped(config.points).toSeq
    test(new WideFftCore(config)) { dut =>
      dut.io.inputValid.poke(false.B)
      dut.io.outputReady.poke(false.B)
      reset(dut)

      def pokeCurrentTwiddles(): Unit =
        for (lane <- 0 until config.lanes) {
          val index = dut.io.twiddleIndex(lane).peek().litValue.toInt
          pokeTwiddle(dut.io.twiddle(lane), twiddles(index))
        }

      for ((frame, frameIndex) <- frames.zipWithIndex) {
        for (beat <- 0 until config.frameBeats) {
          dut.io.inputReady.expect(true.B)
          for (lane <- 0 until config.lanes) {
            val row = frame(beat * config.lanes + lane)
            dut.io.input(lane).real.poke(row(0).S)
            dut.io.input(lane).imag.poke(row(1).S)
          }
          dut.io.inputValid.poke(true.B)
          pokeCurrentTwiddles()
          dut.clock.step()
        }
        dut.io.inputValid.poke(false.B)
        dut.io.outputReady.poke(true.B)
        var waitCycles = 0
        while (!dut.io.outputValid.peek().litToBoolean) {
          pokeCurrentTwiddles()
          dut.clock.step()
          waitCycles += 1
          waitCycles should be < 100
        }
        for (beat <- 0 until config.frameBeats) {
          pokeCurrentTwiddles()
          for (lane <- 0 until config.lanes) {
            val index = beat * config.lanes + lane
            val row = frame(index)
            withClue(s"frame=$frameIndex index=$index real") {
              dut.io.output(lane).real.expect(row(2).S)
            }
            dut.io.output(lane).imag.expect(row(3).S)
          }
          dut.clock.step()
        }
        dut.io.done.expect(true.B)
        dut.io.outputReady.poke(false.B)
        dut.clock.step()
      }
    }
  }

  it should "match all multi-lane forward tangent FFT frames" in {
    val rows = vectors("rtl_tangent_vectors.txt")
    val fftTwiddles = vectors("rtl_fft_twiddles.txt")
    val twists = vectors("rtl_tangent_twiddles.txt")
    val frames = rows.grouped(config.points).toSeq
    test(new TangentFftCore(config)) { dut =>
      dut.io.pairValid.poke(false.B)
      dut.io.outputReady.poke(false.B)
      reset(dut)

      def pokeCurrentTwiddles(): Unit =
        for (lane <- 0 until config.lanes) {
          val fftIndex = dut.io.fftTwiddleIndex(lane).peek().litValue.toInt
          val twistIndex = dut.io.twistIndex(lane).peek().litValue.toInt
          pokeTwiddle(dut.io.fftTwiddle(lane), fftTwiddles(fftIndex))
          pokeTwiddle(dut.io.twist(lane), twists(twistIndex))
        }

      for ((frame, frameIndex) <- frames.zipWithIndex) {
        for (beat <- 0 until config.frameBeats) {
          dut.io.pairReady.expect(true.B)
          for (lane <- 0 until config.lanes) {
            val row = frame(beat * config.lanes + lane)
            dut.io.coefficientLow(lane).poke(row(0).S)
            dut.io.coefficientHigh(lane).poke(row(1).S)
          }
          dut.io.pairValid.poke(true.B)
          pokeCurrentTwiddles()
          dut.clock.step()
        }
        dut.io.pairValid.poke(false.B)
        dut.io.outputReady.poke(true.B)
        var waitCycles = 0
        while (!dut.io.outputValid.peek().litToBoolean) {
          pokeCurrentTwiddles()
          dut.clock.step()
          waitCycles += 1
          waitCycles should be < 100
        }
        for (beat <- 0 until config.frameBeats) {
          pokeCurrentTwiddles()
          for (lane <- 0 until config.lanes) {
            val index = beat * config.lanes + lane
            val row = frame(index)
            withClue(s"tangent frame=$frameIndex index=$index real") {
              dut.io.output(lane).real.expect(row(2).S)
            }
            dut.io.output(lane).imag.expect(row(3).S)
          }
          dut.clock.step()
        }
        dut.io.done.expect(true.B)
        dut.io.outputReady.poke(false.B)
        dut.clock.step()
      }
    }
  }

  it should "match all multi-lane inverse tangent FFT frames" in {
    val rows = vectors("rtl_ifft_vectors.txt")
    val fftTwiddles = vectors("rtl_ifft_twiddles.txt")
    val untwists = vectors("rtl_untwist_twiddles.txt")
    val frames = rows.grouped(config.points).toSeq
    test(new TangentIfftCore(config, normalizeShift = 4)) { dut =>
      dut.io.inputValid.poke(false.B)
      dut.io.outputReady.poke(false.B)
      reset(dut)

      def pokeCurrentTwiddles(): Unit =
        for (lane <- 0 until config.lanes) {
          val fftIndex = dut.io.fftTwiddleIndex(lane).peek().litValue.toInt
          val untwistIndex = dut.io.untwistIndex(lane).peek().litValue.toInt
          pokeTwiddle(dut.io.fftTwiddle(lane), fftTwiddles(fftIndex))
          pokeTwiddle(dut.io.untwist(lane), untwists(untwistIndex))
        }

      for ((frame, frameIndex) <- frames.zipWithIndex) {
        for (beat <- 0 until config.frameBeats) {
          dut.io.inputReady.expect(true.B)
          for (lane <- 0 until config.lanes) {
            val row = frame(beat * config.lanes + lane)
            dut.io.input(lane).real.poke(row(0).S)
            dut.io.input(lane).imag.poke(row(1).S)
          }
          dut.io.inputValid.poke(true.B)
          pokeCurrentTwiddles()
          dut.clock.step()
        }
        dut.io.inputValid.poke(false.B)
        dut.io.outputReady.poke(true.B)
        var waitCycles = 0
        while (!dut.io.outputValid.peek().litToBoolean) {
          pokeCurrentTwiddles()
          dut.clock.step()
          waitCycles += 1
          waitCycles should be < 100
        }
        for (beat <- 0 until config.frameBeats) {
          pokeCurrentTwiddles()
          for (lane <- 0 until config.lanes) {
            val index = beat * config.lanes + lane
            val row = frame(index)
            withClue(s"inverse frame=$frameIndex index=$index low") {
              dut.io.coefficientLow(lane).expect(row(2).S)
            }
            dut.io.coefficientHigh(lane).expect(row(3).S)
          }
          dut.clock.step()
        }
        dut.io.done.expect(true.B)
        dut.io.outputReady.poke(false.B)
        dut.clock.step()
      }
    }
  }
}

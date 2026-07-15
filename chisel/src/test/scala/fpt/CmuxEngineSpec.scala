package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

final class CmuxEngineSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val coefficientConfig = CmuxCoefficientConfig(
    polynomialSize = 32,
    forwardLanes = 4,
    inverseLanes = 2,
    components = 2,
    levels = 3,
    baseBits = 6,
    torusWidth = 32,
    forwardFormat = FixedFormat(18, 20),
    inverseFormat = FixedFormat(27, 14)
  )
  private val forwardConfig = TransformConfig(
    points = 16,
    lanes = 4,
    dataWidth = 38,
    twiddleWidth = 34,
    twiddleFractionalBits = 32
  )
  private val inverseConfig = TransformConfig(
    points = 16,
    lanes = 2,
    dataWidth = 41,
    twiddleWidth = 37,
    twiddleFractionalBits = 35
  )
  private val externalConfig = ExternalProductConfig(
    points = 16,
    inputLanes = 4,
    outputLanes = 2,
    rows = 6,
    outputComponents = 2,
    spectrum = FixedFormat(18, 20),
    bootstrappingKey = FixedFormat(8, 24),
    accumulator = FixedFormat(27, 14)
  )
  private val config = CmuxEngineConfig(
    coefficientConfig,
    forwardConfig,
    inverseConfig,
    externalConfig,
    inverseNormalizeShift = 4
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

  private def pokeTwiddle(target: GaussTwiddle, row: Array[BigInt]): Unit = {
    target.c.poke(row(0).S)
    target.cMinusD.poke(row(1).S)
    target.cPlusD.poke(row(2).S)
  }

  behavior of "the complete fixed-point CMUX engine"

  it should "match a C++ rotation through forward FFT, product, and inverse FFT" in {
    val rows = vectors("rtl_cmux_engine_vectors.txt")
    val initial = rows.take(coefficientConfig.polynomialSize)
    val keyCount = externalConfig.rows * externalConfig.points
    val key = rows.slice(
      coefficientConfig.polynomialSize,
      coefficientConfig.polynomialSize + keyCount
    )
    val expected = rows.takeRight(coefficientConfig.polynomialSize)
    val twiddles = vectors("rtl_cmux_engine_twiddles.txt")
    val forwardFftTwiddles = twiddles.take(forwardConfig.points / 2)
    val forwardTwists = twiddles.slice(
      forwardConfig.points / 2,
      forwardConfig.points / 2 + forwardConfig.points
    )
    val inverseOffset = forwardConfig.points / 2 + forwardConfig.points
    val inverseFftTwiddles = twiddles.slice(
      inverseOffset,
      inverseOffset + inverseConfig.points / 2
    )
    val inverseUntwists = twiddles.drop(
      inverseOffset + inverseConfig.points / 2
    )

    test(new CmuxEngine(config)) { dut =>
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(false.B)
      dut.io.commandValid.poke(false.B)
      dut.io.drainStart.poke(false.B)
      dut.io.drainReady.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.loadStart.poke(true.B)
      dut.clock.step()
      dut.io.loadStart.poke(false.B)
      for (beat <- 0 until coefficientConfig.polynomialBeats) {
        dut.io.loadReady.expect(true.B)
        for (lane <- 0 until coefficientConfig.inverseLanes) {
          val index = beat * coefficientConfig.inverseLanes + lane
          for (component <- 0 until coefficientConfig.components)
            dut.io.load(component)(lane).poke(initial(index)(component).U)
        }
        dut.io.loadValid.poke(true.B)
        dut.clock.step()
      }
      dut.io.loadValid.poke(false.B)
      dut.io.loadDone.expect(true.B)
      dut.clock.step()

      def driveReadOnlyInputs(): Unit = {
        for (lane <- 0 until forwardConfig.lanes) {
          val twistIndex = dut.io.forwardTwistIndex(lane).peek().litValue.toInt
          val fftIndex =
            dut.io.forwardFftTwiddleIndex(lane).peek().litValue.toInt
          pokeTwiddle(dut.io.forwardTwist(lane), forwardTwists(twistIndex))
          pokeTwiddle(
            dut.io.forwardFftTwiddle(lane),
            forwardFftTwiddles(fftIndex)
          )
        }
        for (lane <- 0 until inverseConfig.lanes) {
          val fftIndex =
            dut.io.inverseFftTwiddleIndex(lane).peek().litValue.toInt
          val untwistIndex =
            dut.io.inverseUntwistIndex(lane).peek().litValue.toInt
          pokeTwiddle(
            dut.io.inverseFftTwiddle(lane),
            inverseFftTwiddles(fftIndex)
          )
          pokeTwiddle(
            dut.io.inverseUntwist(lane),
            inverseUntwists(untwistIndex)
          )
        }
        val keyRow = dut.io.keyRow.peek().litValue.toInt
        for (lane <- 0 until externalConfig.inputLanes) {
          val point = dut.io.keyPoint(lane).peek().litValue.toInt
          val vector = key(keyRow * externalConfig.points + point)
          for (component <- 0 until externalConfig.outputComponents) {
            dut.io.bootstrappingKey(component)(lane).real.poke(
              vector(2 * component).S
            )
            dut.io.bootstrappingKey(component)(lane).imag.poke(
              vector(2 * component + 1).S
            )
          }
        }
      }

      dut.io.commandReady.expect(true.B)
      dut.io.exponent.poke(13.U)
      dut.io.commandValid.poke(true.B)
      driveReadOnlyInputs()
      dut.clock.step()
      dut.io.commandValid.poke(false.B)

      var cycles = 0
      while (!dut.io.done.peek().litToBoolean) {
        driveReadOnlyInputs()
        dut.clock.step()
        cycles += 1
        cycles should be < 1000
      }
      info(s"native Chisel CMUX latency: $cycles cycles")

      dut.io.drainStart.poke(true.B)
      dut.clock.step()
      dut.io.drainStart.poke(false.B)
      dut.io.drainReady.poke(true.B)
      for (beat <- 0 until coefficientConfig.polynomialBeats) {
        dut.io.drainValid.expect(true.B)
        for (lane <- 0 until coefficientConfig.inverseLanes) {
          val index = beat * coefficientConfig.inverseLanes + lane
          for (component <- 0 until coefficientConfig.components) {
            withClue(s"component=$component index=$index") {
              dut.io.drain(component)(lane).expect(expected(index)(component).U)
            }
          }
        }
        dut.clock.step()
      }
      dut.io.drainDone.expect(true.B)
    }
  }
}

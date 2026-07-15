package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

final class BatchedCmuxEngineSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val coefficient = CmuxCoefficientConfig(
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
  private val forward = TransformConfig(
    points = 16,
    lanes = 4,
    dataWidth = 38,
    twiddleWidth = 34,
    twiddleFractionalBits = 32
  )
  private val inverse = TransformConfig(
    points = 16,
    lanes = 2,
    dataWidth = 41,
    twiddleWidth = 37,
    twiddleFractionalBits = 35
  )
  private val external = ExternalProductConfig(
    points = 16,
    inputLanes = 4,
    outputLanes = 2,
    rows = 6,
    outputComponents = 2,
    spectrum = FixedFormat(18, 20),
    bootstrappingKey = FixedFormat(8, 24),
    accumulator = FixedFormat(27, 14)
  )

  private def generatedPath(name: String): String =
    Path
      .of("src", "test", "resources", "generated", name)
      .toAbsolutePath
      .normalize
      .toString

  private val engine = CmuxEngineConfig(
    coefficient,
    forward,
    inverse,
    external,
    inverseNormalizeShift = 4,
    forwardSGen = Some(
      SGenBackendConfig(
        "FptSGenForwardGuarded16x4",
        generatedPath("FptSGenForwardGuarded16x4.v"),
        integratedTangent = true
      )
    ),
    inverseSGen = Some(
      SGenBackendConfig(
        "FptSGenInverseGuarded16x2",
        generatedPath("FptSGenInverseGuarded16x2.v"),
        inputLeadCycles = 4,
        integratedTangent = true
      )
    )
  )
  private val registerConfig = BatchedCmuxEngineConfig(
    engine,
    batchContexts = 2
  )

  private def vectors(name: String): Seq[Array[BigInt]] = {
    val candidates = Seq(
      Path.of("..", "build", name),
      Path.of("..", "build-tfhepp", name),
      Path.of("build", name)
    )
    val path = candidates.find(Files.exists(_)).getOrElse(
      fail(s"Could not find $name; build the C++ RTL vectors first")
    )
    Files
      .readAllLines(path)
      .asScala
      .filter(_.trim.nonEmpty)
      .map(_.trim.split("\\s+").map(BigInt(_)))
      .toSeq
  }

  private def pokeTwiddle(target: GaussTwiddle, row: Array[BigInt]): Unit = {
    target.c.poke(row(0).S)
    target.cMinusD.poke(row(1).S)
    target.cPlusD.poke(row(2).S)
  }

  private def wrappedDifference(actual: BigInt, expected: BigInt): BigInt = {
    val modulus = BigInt(1) << coefficient.torusWidth
    val raw = (actual - expected) & (modulus - 1)
    val signed = if (raw.testBit(coefficient.torusWidth - 1)) {
      raw - modulus
    } else {
      raw
    }
    signed.abs
  }

  behavior of "the tagged batched CMUX engine"

  private def exercise(
      config: BatchedCmuxEngineConfig,
      drainUsesPrefetch: Boolean
  ): Unit = {
    val rows = vectors("rtl_cmux_engine_vectors.txt")
    val initial = rows.take(coefficient.polynomialSize)
    val keyCount = external.rows * external.points
    val key = rows.slice(
      coefficient.polynomialSize,
      coefficient.polynomialSize + keyCount
    )
    val expected = rows.takeRight(coefficient.polynomialSize)
    val twiddles = vectors("rtl_cmux_engine_twiddles.txt")
    val forwardTwists = twiddles.slice(
      forward.points / 2,
      forward.points / 2 + forward.points
    )
    val inverseOffset = forward.points / 2 + forward.points
    val inverseUntwists = twiddles.drop(
      inverseOffset + inverse.points / 2
    )

    test(new BatchedCmuxEngine(config))
      .withAnnotations(
        Seq(
          VerilatorBackendAnnotation,
          VerilatorFlags(
            Seq(
              "--output-split",
              "99999999",
              "--output-split-cfuncs",
              "99999999"
            )
          )
        )
      ) { dut =>
        dut.io.loadStart.poke(false.B)
        dut.io.loadValid.poke(false.B)
        dut.io.commandValid.poke(false.B)
        dut.io.commandContext.poke(0.U)
        dut.io.exponent.poke(13.U)
        dut.io.drainStart.poke(false.B)
        dut.io.drainReady.poke(false.B)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        for (context <- 0 until config.batchContexts) {
          dut.io.loadContext.poke(context.U)
          dut.io.loadStart.poke(true.B)
          dut.clock.step()
          dut.io.loadStart.poke(false.B)
          dut.io.loadValid.poke(true.B)
          for (beat <- 0 until coefficient.polynomialBeats) {
            for (lane <- 0 until coefficient.inverseLanes) {
              val index = beat * coefficient.inverseLanes + lane
              for (component <- 0 until coefficient.components) {
                dut.io.load(component)(lane).poke(initial(index)(component).U)
              }
            }
            dut.clock.step()
          }
          dut.io.loadValid.poke(false.B)
          dut.io.loadDone.expect(true.B)
          dut.clock.step()
        }

        def driveReadOnlyInputs(): Unit = {
          for (lane <- 0 until forward.lanes) {
            val index = dut.io.forwardTwistIndex(lane).peek().litValue.toInt
            pokeTwiddle(dut.io.forwardTwist(lane), forwardTwists(index))
          }
          for (lane <- 0 until inverse.lanes) {
            val index = dut.io.inverseUntwistIndex(lane).peek().litValue.toInt
            pokeTwiddle(dut.io.inverseUntwist(lane), inverseUntwists(index))
          }
          val keyRow = dut.io.keyRow.peek().litValue.toInt
          for (lane <- 0 until external.inputLanes) {
            val point = dut.io.keyPoint(lane).peek().litValue.toInt
            val vector = key(keyRow * external.points + point)
            for (component <- 0 until external.outputComponents) {
              dut.io.bootstrappingKey(component)(lane).real.poke(
                vector(2 * component).S
              )
              dut.io.bootstrappingKey(component)(lane).imag.poke(
                vector(2 * component + 1).S
              )
            }
          }
        }

        var cycle = 0
        def step(): Unit = {
          driveReadOnlyInputs()
          dut.clock.step()
          cycle += 1
        }

        dut.io.commandContext.poke(0.U)
        dut.io.commandValid.poke(true.B)
        dut.io.commandReady.expect(true.B)
        step()
        dut.io.commandValid.poke(false.B)
        val firstAcceptance = 0
        dut.io.commandContext.poke(1.U)

        while (!dut.io.commandReady.peek().litToBoolean) {
          step()
          cycle should be <= config.commandInterval
        }
        val secondAcceptance = cycle
        secondAcceptance - firstAcceptance should be(config.commandInterval)
        dut.io.commandValid.poke(true.B)
        step()
        dut.io.commandValid.poke(false.B)

        val doneCycles = ArrayBuffer.empty[Int]
        val doneContexts = ArrayBuffer.empty[Int]
        while (doneContexts.size < config.batchContexts) {
          if (dut.io.doneValid.peek().litToBoolean) {
            doneCycles += cycle
            doneContexts += dut.io.doneContext.peek().litValue.toInt
          }
          step()
          cycle should be < 400
        }
        doneContexts.toSeq should be(Seq(0, 1))
        doneCycles(1) - doneCycles(0) should be(config.commandInterval)
        info(
          s"batched CMUX accepts/completes every ${config.commandInterval} " +
            s"cycles with first completion at cycle ${doneCycles.head}"
        )

        for (context <- 0 until config.batchContexts) {
          dut.io.drainContext.poke(context.U)
          dut.io.drainStart.poke(true.B)
          step()
          dut.io.drainStart.poke(false.B)
          dut.io.drainReady.poke(true.B)
          if (drainUsesPrefetch) {
            var drainWait = 0
            while (!dut.io.drainValid.peek().litToBoolean) {
              step()
              drainWait += 1
              drainWait should be <= coefficient.inverseBeats + 2
            }
          }
          var maximumError = BigInt(0)
          for (beat <- 0 until coefficient.polynomialBeats) {
            dut.io.drainValid.expect(true.B)
            for (lane <- 0 until coefficient.inverseLanes) {
              val index = beat * coefficient.inverseLanes + lane
              for (component <- 0 until coefficient.components) {
                val actual = dut.io.drain(component)(lane).peek().litValue
                maximumError = maximumError.max(
                  wrappedDifference(actual, expected(index)(component))
                )
              }
            }
            step()
          }
          maximumError should be <= (BigInt(1) << 18)
          dut.io.drainReady.poke(false.B)
          step()
        }
      }
  }

  it should "accept adjacent register contexts at the row-stream interval" in {
    exercise(registerConfig, drainUsesPrefetch = false)
  }

  it should "run the same CMUX through replicated accumulator banks" in {
    exercise(
      registerConfig.copy(
        coefficientStorage = BatchedCoefficientStorage.ReplicatedBanks
      ),
      drainUsesPrefetch = true
    )
  }
}

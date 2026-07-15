package fpt

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

final class BlindRotateEngineSpec
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
  private val contexts = 7
  private val domainDimension = 2
  private val config = BatchedBlindRotateEngineConfig(
    BatchedCmuxEngineConfig(engine, contexts),
    domainDimension
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
    val signed =
      if (raw.testBit(coefficient.torusWidth - 1)) raw - modulus else raw
    signed.abs
  }

  behavior of "the batched fixed-point Blind Rotate engine"

  it should "modswitch TLWEs and preserve key tags at sustained CMUX II" in {
    val torusMask = (BigInt(1) << config.inputTorusWidth) - 1
    val exponentMask = (BigInt(1) << config.exponentWidth) - 1
    val testVectors = Seq.tabulate(contexts) { context =>
      (BigInt("10000000", 16) + BigInt("01111111", 16) * context) &
        torusMask
    }
    val desiredMasks = Seq.tabulate(contexts) { context =>
      Seq((3 + 5 * context) & 63, (17 + 7 * context) & 63)
    }
    val residuals = Seq.tabulate(contexts) { context =>
      Seq(context - 3, -(context + 1))
    }
    val bodyRounded = Seq.tabulate(contexts)(context => (11 + 3 * context) & 63)
    val initialExponents = bodyRounded.map(value => (-value) & 63)

    def rawMask(exponent: Int, residual: Int): BigInt =
      ((BigInt(exponent) << config.modulusShift) + residual) & torusMask

    def correctionHalf(context: Int): Int = {
      val correction = residuals(context).sum
      if (correction < 0) -((-correction) / 2) else correction / 2
    }

    val inputs = Seq.tabulate(contexts) { context =>
      desiredMasks(context)
        .zip(residuals(context))
        .map { case (exponent, residual) => rawMask(exponent, residual) } :+
        (((BigInt(bodyRounded(context)) << config.modulusShift) +
          correctionHalf(context)) & torusMask)
    }

    test(new BatchedBlindRotateEngine(config))
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
        dut.io.inputStart.poke(false.B)
        dut.io.inputContext.poke(0.U)
        dut.io.testVector.poke(0.U)
        dut.io.inputValid.poke(false.B)
        dut.io.inputCoefficient.poke(0.U)
        dut.io.runStart.poke(false.B)
        dut.io.drainStart.poke(false.B)
        dut.io.drainContext.poke(0.U)
        dut.io.drainReady.poke(false.B)

        for (component <- 0 until external.outputComponents) {
          for (lane <- 0 until external.inputLanes) {
            dut.io.bootstrappingKey(component)(lane).real.poke(0.S)
            dut.io.bootstrappingKey(component)(lane).imag.poke(0.S)
          }
        }
        for (lane <- 0 until forward.lanes) {
          dut.io.forwardTwist(lane).c.poke(0.S)
          dut.io.forwardTwist(lane).cMinusD.poke(0.S)
          dut.io.forwardTwist(lane).cPlusD.poke(0.S)
        }
        for (lane <- 0 until inverse.lanes) {
          dut.io.inverseUntwist(lane).c.poke(0.S)
          dut.io.inverseUntwist(lane).cMinusD.poke(0.S)
          dut.io.inverseUntwist(lane).cPlusD.poke(0.S)
        }

        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        for (context <- 0 until contexts) {
          dut.io.inputContext.poke(context.U)
          dut.io.testVector.poke(testVectors(context).U)
          dut.io.inputStartReady.expect(true.B)
          dut.io.inputStart.poke(true.B)
          dut.clock.step()
          dut.io.inputStart.poke(false.B)
          dut.io.inputValid.poke(true.B)
          for (value <- inputs(context)) {
            dut.io.inputReady.expect(true.B)
            dut.io.inputCoefficient.poke(value.U)
            dut.clock.step()
          }
          dut.io.inputValid.poke(false.B)
          var loadWait = 0
          while (!dut.io.inputDone.peek().litToBoolean) {
            dut.clock.step()
            loadWait += 1
            loadWait should be <= coefficient.polynomialBeats + 4
          }
          dut.io.inputDoneContext.expect(context.U)
          dut.clock.step()
          dut.io.contextInitialized(context).expect(true.B)
        }

        dut.io.runReady.expect(true.B)
        dut.io.runStart.poke(true.B)
        dut.clock.step()
        dut.io.runStart.poke(false.B)

        var cycle = 0
        val keyCycles = ArrayBuffer.empty[Int]
        val keyTransactions = ArrayBuffer.empty[(Int, Int, Int)]
        val completedContexts = ArrayBuffer.empty[Int]

        def observe(): Unit = {
          if (dut.io.keyValid.peek().litToBoolean &&
              dut.io.keyFirst.peek().litToBoolean) {
            keyCycles += cycle
            keyTransactions += ((
              dut.io.keyContext.peek().litValue.toInt,
              dut.io.keyIndex.peek().litValue.toInt,
              dut.io.keyExponent.peek().litValue.toInt
            ))
          }
          if (dut.io.contextDoneValid.peek().litToBoolean) {
            completedContexts += dut.io.contextDone.peek().litValue.toInt
          }
        }

        while (!dut.io.done.peek().litToBoolean) {
          observe()
          dut.clock.step()
          cycle += 1
          cycle should be < 1000
        }
        observe()
        dut.clock.step()
        cycle += 1

        val expectedTransactions =
          (0 until domainDimension).flatMap(dimension =>
            (0 until contexts).map(context =>
              (context, dimension, desiredMasks(context)(dimension))
            )
          )
        keyTransactions.toSeq should be(expectedTransactions)
        keyCycles.sliding(2).foreach { pair =>
          pair(1) - pair(0) should be(config.cmux.commandInterval)
        }
        completedContexts.toSeq should be(0 until contexts)
        info(
          s"Blind Rotate issued ${keyTransactions.size} tagged CMUXes at " +
            s"II=${config.cmux.commandInterval}"
        )

        dut.io.drainReady.poke(true.B)
        for (context <- 0 until contexts) {
          dut.io.drainContext.poke(context.U)
          dut.io.drainStartReady.expect(true.B)
          dut.io.drainStart.poke(true.B)
          dut.clock.step()
          dut.io.drainStart.poke(false.B)
          for (beat <- 0 until coefficient.polynomialBeats) {
            dut.io.drainValid.expect(true.B)
            for (lane <- 0 until coefficient.inverseLanes) {
              val index = beat * coefficient.inverseLanes + lane
              dut.io.drain(0)(lane).expect(0.U)
              val exponent = initialExponents(context)
              val low = exponent & (coefficient.polynomialSize - 1)
              val high = (exponent >> log2Ceil(coefficient.polynomialSize)) != 0
              val negate = high ^ (index < low)
              val expected =
                if (negate) (-testVectors(context)) & torusMask
                else testVectors(context)
              dut.io.drain(1)(lane).expect(expected.U)
            }
            dut.clock.step()
          }
          dut.io.drainDone.expect(true.B)
          dut.io.drainDoneContext.expect(context.U)
        }

        desiredMasks.flatten.map(BigInt(_)).foreach { exponent =>
          (exponent & exponentMask) should be(exponent)
        }
      }
  }

  private def exerciseOracle(
      testConfig: BatchedBlindRotateEngineConfig,
      label: String
  ): Unit = {
    val rows = vectors("rtl_blind_rotate_vectors.txt")
    val inputRows = rows.take(contexts)
    val keyCount = domainDimension * external.rows * external.points
    val key = rows.slice(contexts, contexts + keyCount)
    val expected = rows
      .drop(contexts + keyCount)
      .grouped(coefficient.polynomialSize)
      .toSeq
    inputRows.size should be(contexts)
    key.size should be(keyCount)
    expected.size should be(contexts)

    val twiddles = vectors("rtl_cmux_engine_twiddles.txt")
    val forwardTwists = twiddles.slice(
      forward.points / 2,
      forward.points / 2 + forward.points
    )
    val inverseOffset = forward.points / 2 + forward.points
    val inverseUntwists = twiddles.drop(inverseOffset + inverse.points / 2)

    test(new BatchedBlindRotateEngine(testConfig))
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
        dut.io.inputStart.poke(false.B)
        dut.io.inputContext.poke(0.U)
        dut.io.testVector.poke(0.U)
        dut.io.inputValid.poke(false.B)
        dut.io.inputCoefficient.poke(0.U)
        dut.io.runStart.poke(false.B)
        dut.io.drainStart.poke(false.B)
        dut.io.drainContext.poke(0.U)
        dut.io.drainReady.poke(false.B)

        for (component <- 0 until external.outputComponents) {
          for (lane <- 0 until external.inputLanes) {
            dut.io.bootstrappingKey(component)(lane).real.poke(0.S)
            dut.io.bootstrappingKey(component)(lane).imag.poke(0.S)
          }
        }
        for (lane <- 0 until forward.lanes) {
          dut.io.forwardTwist(lane).c.poke(0.S)
          dut.io.forwardTwist(lane).cMinusD.poke(0.S)
          dut.io.forwardTwist(lane).cPlusD.poke(0.S)
        }
        for (lane <- 0 until inverse.lanes) {
          dut.io.inverseUntwist(lane).c.poke(0.S)
          dut.io.inverseUntwist(lane).cMinusD.poke(0.S)
          dut.io.inverseUntwist(lane).cPlusD.poke(0.S)
        }

        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        def driveReadOnlyInputs(): Unit = {
          for (lane <- 0 until forward.lanes) {
            val index = dut.io.forwardTwistIndex(lane).peek().litValue.toInt
            pokeTwiddle(dut.io.forwardTwist(lane), forwardTwists(index))
          }
          for (lane <- 0 until inverse.lanes) {
            val index = dut.io.inverseUntwistIndex(lane).peek().litValue.toInt
            pokeTwiddle(dut.io.inverseUntwist(lane), inverseUntwists(index))
          }
          val keyIndex = dut.io.keyIndex.peek().litValue.toInt
          val keyRow = dut.io.keyRow.peek().litValue.toInt
          for (lane <- 0 until external.inputLanes) {
            val point = dut.io.keyPoint(lane).peek().litValue.toInt
            val vector = key(
              (keyIndex * external.rows + keyRow) * external.points + point
            )
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

        for (context <- 0 until contexts) {
          dut.io.inputContext.poke(context.U)
          dut.io.testVector.poke(inputRows(context)(0).U)
          dut.io.inputStartReady.expect(true.B)
          dut.io.inputStart.poke(true.B)
          step()
          dut.io.inputStart.poke(false.B)
          dut.io.inputValid.poke(true.B)
          for (value <- inputRows(context).drop(1)) {
            dut.io.inputReady.expect(true.B)
            dut.io.inputCoefficient.poke(value.U)
            step()
          }
          dut.io.inputValid.poke(false.B)
          var loadWait = 0
          while (!dut.io.inputDone.peek().litToBoolean) {
            step()
            loadWait += 1
            loadWait should be <= coefficient.polynomialBeats + 4
          }
          dut.io.inputDoneContext.expect(context.U)
          step()
          dut.io.contextInitialized(context).expect(true.B)
        }

        dut.io.runReady.expect(true.B)
        dut.io.runStart.poke(true.B)
        step()
        dut.io.runStart.poke(false.B)

        val keyCycles = ArrayBuffer.empty[Int]
        val keyTransactions = ArrayBuffer.empty[(Int, Int, Int)]
        val completedContexts = ArrayBuffer.empty[Int]

        def observe(): Unit = {
          if (dut.io.keyValid.peek().litToBoolean &&
              dut.io.keyFirst.peek().litToBoolean) {
            keyCycles += cycle
            keyTransactions += ((
              dut.io.keyContext.peek().litValue.toInt,
              dut.io.keyIndex.peek().litValue.toInt,
              dut.io.keyExponent.peek().litValue.toInt
            ))
          }
          if (dut.io.contextDoneValid.peek().litToBoolean) {
            completedContexts += dut.io.contextDone.peek().litValue.toInt
          }
        }

        while (!dut.io.done.peek().litToBoolean) {
          driveReadOnlyInputs()
          observe()
          dut.clock.step()
          cycle += 1
          cycle should be < 1500
        }
        driveReadOnlyInputs()
        observe()
        dut.clock.step()
        cycle += 1

        val expectedTransactions =
          (0 until domainDimension).flatMap(dimension =>
            (0 until contexts).map { context =>
              val exponent =
                if (context == 0) 0
                else if (dimension == 0) (3 + 5 * context) & 63
                else (17 + 7 * context) & 63
              (context, dimension, exponent)
            }
          )
        keyTransactions.toSeq should be(expectedTransactions)
        keyCycles.grouped(contexts).foreach { wave =>
          wave.sliding(2).foreach { pair =>
            pair(1) - pair(0) should be(testConfig.cmux.commandInterval)
          }
        }
        completedContexts.toSeq should be(0 until contexts)

        dut.io.drainReady.poke(true.B)
        var maximumError = BigInt(0)
        for (context <- 0 until contexts) {
          dut.io.drainContext.poke(context.U)
          var drainStartWait = 0
          while (!dut.io.drainStartReady.peek().litToBoolean) {
            step()
            drainStartWait += 1
            drainStartWait should be <= coefficient.polynomialBeats + 4
          }
          dut.io.drainStart.poke(true.B)
          step()
          dut.io.drainStart.poke(false.B)
          var drainWait = 0
          while (!dut.io.drainValid.peek().litToBoolean) {
            step()
            drainWait += 1
            drainWait should be <= coefficient.inverseBeats + 2
          }
          for (beat <- 0 until coefficient.polynomialBeats) {
            dut.io.drainValid.expect(true.B)
            for (lane <- 0 until coefficient.inverseLanes) {
              val index = beat * coefficient.inverseLanes + lane
              for (component <- 0 until coefficient.components) {
                val actual = dut.io.drain(component)(lane).peek().litValue
                val error = wrappedDifference(
                  actual,
                  expected(context)(index)(component)
                )
                if (context == 0) error should be(0)
                maximumError = maximumError.max(error)
              }
            }
            step()
          }
          dut.io.drainDone.expect(true.B)
          dut.io.drainDoneContext.expect(context.U)
        }
        maximumError should be <= (BigInt(1) << 19)
        info(
          s"$label C++ Blind Rotate oracle maximum wrapped error: " +
            s"$maximumError"
        )
      }
  }

  it should "match the C++ oracle with register-backed contexts" in {
    exerciseOracle(config, "register-backed")
  }

  it should "match the C++ oracle with replicated accumulator banks" in {
    exerciseOracle(
      config.copy(
        cmux = config.cmux.copy(
          coefficientStorage = BatchedCoefficientStorage.ReplicatedBanks
        )
      ),
      "replicated-bank"
    )
  }

  it should "match the C++ oracle with the folded bitwise store" in {
    exerciseOracle(
      config.copy(
        cmux = config.cmux.copy(
          engine = engine.copy(bitwiseBitsPerCycle = Some(2)),
          coefficientStorage =
            BatchedCoefficientStorage.BitwiseReplicatedBanks
        )
      ),
      "bitwise-bank"
    )
  }
}

package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

final class BlindRotateSampleExtractSpec
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
  private val contexts = 3
  private def generatedPath(name: String): String =
    Path
      .of("src", "test", "resources", "generated", name)
      .toAbsolutePath
      .normalize
      .toString

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

  private val config = BatchedBlindRotateEngineConfig(
    BatchedCmuxEngineConfig(
      CmuxEngineConfig(
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
      ),
      contexts
    ),
    domainDimension = 1
  )

  behavior of "sample-extracted batched Blind Rotate"

  it should "return one ordered TLWE per completed context" in {
    test(new BatchedBlindRotateSampleExtractEngine(config))
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
      dut.io.resultReady.poke(false.B)
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

      val testVectors = Seq.tabulate(contexts) { context =>
        BigInt("10000000", 16) * (context + 1)
      }
      for (context <- 0 until contexts) {
        dut.io.inputContext.poke(context.U)
        dut.io.testVector.poke(testVectors(context).U)
        dut.io.inputStartReady.expect(true.B)
        dut.io.inputStart.poke(true.B)
        dut.clock.step()
        dut.io.inputStart.poke(false.B)
        dut.io.inputValid.poke(true.B)
        for (_ <- 0 until config.inputBeats) {
          dut.io.inputReady.expect(true.B)
          dut.io.inputCoefficient.poke(0.U)
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
      }

      dut.io.runReady.expect(true.B)
      dut.io.runStart.poke(true.B)
      dut.clock.step()
      dut.io.runStart.poke(false.B)

      val expected = testVectors.flatMap { testVector =>
        Seq.fill(coefficient.polynomialSize)(BigInt(0)) :+ testVector
      }
      val actual = ArrayBuffer.empty[BigInt]
      val actualContexts = ArrayBuffer.empty[Int]
      var cycle = 0
      var computeDoneCycle = Option.empty[Int]
      var firstResultCycle = Option.empty[Int]
      var finalDone = false
      while (!finalDone) {
        val ready = cycle % 7 != 2
        dut.io.resultReady.poke(ready.B)
        if (dut.io.computeDone.peek().litToBoolean) {
          computeDoneCycle = Some(cycle)
        }
        val resultFire = dut.io.resultValid.peek().litToBoolean && ready
        if (resultFire) {
          if (firstResultCycle.isEmpty) firstResultCycle = Some(cycle)
          actual += dut.io.result.peek().litValue
          actualContexts += dut.io.resultContext.peek().litValue.toInt
          val expectedLast = actual.size == expected.size
          dut.io.resultLast.expect(expectedLast.B)
          dut.io.done.expect(expectedLast.B)
          finalDone = expectedLast
        }
        if (!finalDone) dut.io.active.expect(true.B)
        dut.clock.step()
        cycle += 1
        cycle should be < 2000
      }

      actual.toSeq should be(expected)
      actualContexts.toSeq should be(
        (0 until contexts).flatMap(context =>
          Seq.fill(coefficient.polynomialSize + 1)(context)
        )
      )
      computeDoneCycle should not be empty
      firstResultCycle.get should be > computeDoneCycle.get
      dut.io.active.expect(false.B)
      dut.io.runReady.expect(true.B)
    }
  }

  it should "match the nonzero C++ Blind Rotate oracle through sample extraction" in {
    val oracleContexts = 7
    val oracleDimensions = 2
    val oracleConfig = config.copy(
      cmux = config.cmux.copy(
        engine = config.cmux.engine.copy(bitwiseBitsPerCycle = Some(2)),
        batchContexts = oracleContexts,
        coefficientStorage = BatchedCoefficientStorage.BitwiseReplicatedBanks
      ),
      domainDimension = oracleDimensions
    )
    val rows = vectors("rtl_blind_rotate_vectors.txt")
    val inputRows = rows.take(oracleContexts)
    val keyCount = oracleDimensions * external.rows * external.points
    val key = rows.slice(oracleContexts, oracleContexts + keyCount)
    val expectedTrlwe = rows
      .drop(oracleContexts + keyCount)
      .grouped(coefficient.polynomialSize)
      .toSeq
    inputRows.size should be(oracleContexts)
    key.size should be(keyCount)
    expectedTrlwe.size should be(oracleContexts)

    val torusMask = (BigInt(1) << coefficient.torusWidth) - 1
    val expectedTlwe = expectedTrlwe.map { polynomial =>
      Seq(polynomial.head(0)) ++
        (coefficient.polynomialSize - 1 to 1 by -1).map(index =>
          (-polynomial(index)(0)) & torusMask
        ) ++
        Seq(polynomial.head(1))
    }

    val twiddles = vectors("rtl_cmux_engine_twiddles.txt")
    val forwardTwists = twiddles.slice(
      forward.points / 2,
      forward.points / 2 + forward.points
    )
    val inverseOffset = forward.points / 2 + forward.points
    val inverseUntwists = twiddles.drop(inverseOffset + inverse.points / 2)

    test(new BatchedBlindRotateSampleExtractEngine(oracleConfig))
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
        dut.io.resultReady.poke(false.B)
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

        for (context <- 0 until oracleContexts) {
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
        }

        dut.io.runReady.expect(true.B)
        dut.io.runStart.poke(true.B)
        step()
        dut.io.runStart.poke(false.B)

        var outputIndex = 0
        var maximumError = BigInt(0)
        var computeDoneCycle = Option.empty[Int]
        var firstResultCycle = Option.empty[Int]
        var finalDone = false
        val outputsPerContext = coefficient.polynomialSize + 1
        val totalOutputs = oracleContexts * outputsPerContext
        while (!finalDone) {
          driveReadOnlyInputs()
          val ready = cycle % 7 != 2
          dut.io.resultReady.poke(ready.B)
          if (dut.io.computeDone.peek().litToBoolean) {
            computeDoneCycle = Some(cycle)
          }
          val resultFire = dut.io.resultValid.peek().litToBoolean && ready
          if (resultFire) {
            if (firstResultCycle.isEmpty) firstResultCycle = Some(cycle)
            val context = outputIndex / outputsPerContext
            val coefficientIndex = outputIndex % outputsPerContext
            val actual = dut.io.result.peek().litValue
            val error = wrappedDifference(
              actual,
              expectedTlwe(context)(coefficientIndex)
            )
            if (context == 0) error should be(0)
            maximumError = maximumError.max(error)
            dut.io.resultContext.expect(context.U)
            val expectedLast = outputIndex + 1 == totalOutputs
            dut.io.resultLast.expect(expectedLast.B)
            dut.io.done.expect(expectedLast.B)
            outputIndex += 1
            finalDone = expectedLast
          }
          if (!finalDone) dut.io.active.expect(true.B)
          dut.clock.step()
          cycle += 1
          cycle should be < 3000
        }

        outputIndex should be(totalOutputs)
        maximumError should be <= (BigInt(1) << 19)
        computeDoneCycle should not be empty
        firstResultCycle.get should be > computeDoneCycle.get
        dut.io.active.expect(false.B)
        dut.io.runReady.expect(true.B)
        info(
          "sample-extracted C++ Blind Rotate oracle maximum wrapped error: " +
            maximumError
        )
      }
  }
}

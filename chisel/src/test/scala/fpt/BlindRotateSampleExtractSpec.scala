package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
import scala.collection.mutable.ArrayBuffer

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
}

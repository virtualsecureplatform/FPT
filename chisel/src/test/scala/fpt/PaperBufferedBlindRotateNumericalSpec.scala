package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** Opt-in nonzero numerical validation of the exact host-facing Set-II FPT
  * accelerator boundary. The test crosses raw-TLWE loading, corrected modulus
  * switching, the narrow ping-pong key loader, all generated tangent FTTs,
  * the folded bitwise CMUX path, and automatic sample extraction.
  */
final class PaperBufferedBlindRotateNumericalSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
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

  private def wrappedDifference(actual: BigInt, expected: BigInt): BigInt = {
    val width = PaperSetII.coefficient.torusWidth
    val modulus = BigInt(1) << width
    val raw = (actual - expected) & (modulus - 1)
    val signed = if (raw.testBit(width - 1)) raw - modulus else raw
    signed.abs
  }

  behavior of "the physical buffered Set-II Blind Rotate accelerator"

  it should "bound a complete nonzero batch against the C++ oracle" in {
    if (!sys.env
          .get("FPT_PAPER_BUFFERED_BLIND_ROTATE_NUMERICS")
          .contains("1")) {
      cancel(
        "set FPT_PAPER_BUFFERED_BLIND_ROTATE_NUMERICS=1 to run the " +
          "paper-size physical Blind Rotate"
      )
    }

    val forwardPath = requiredPath(
      "FPT_SGEN_FORWARD",
      "../build/sgen-fpt/forward.v"
    )
    val inversePath = requiredPath(
      "FPT_SGEN_INVERSE",
      "../build/sgen-fpt/inverse.v"
    )
    val rows = vectors(
      requiredPath(
        "FPT_PAPER_BLIND_ROTATE_VECTORS",
        "../build/rtl_paper_blind_rotate_vectors.txt"
      )
    )

    val config = PaperSetII.bufferedBlindRotate(
      forwardPath.toString,
      inversePath.toString,
      domainDimension = 1,
      includeVerilogSource = true
    )
    val blind = config.blindRotate
    val coefficient = blind.cmux.engine.coefficient
    val external = blind.cmux.engine.externalProduct
    val contexts = blind.batchContexts
    val inputRows = rows.take(contexts)
    val keyRows = external.rows * external.points
    val key = rows.slice(contexts, contexts + keyRows)
    val expectedRows = rows.drop(contexts + keyRows)
    val outputsPerContext = coefficient.polynomialSize + 1
    val expected = expectedRows.map(_.head)

    contexts should be(PaperSetII.bitwiseBatchContexts)
    inputRows.size should be(contexts)
    inputRows.foreach(_.length should be(3))
    key.size should be(keyRows)
    key.foreach(_.length should be(2 * external.outputComponents))
    expectedRows.size should be(contexts * outputsPerContext)
    expectedRows.foreach(_.length should be(1))

    test(new BufferedBlindRotateAccelerator(config))
      .withAnnotations(
        Seq(
          VerilatorBackendAnnotation,
          PaperVerilator.flags
        )
      ) { dut =>
        dut.io.inputStart.poke(false.B)
        dut.io.inputContext.poke(0.U)
        dut.io.testVector.poke(0.U)
        dut.io.inputValid.poke(false.B)
        dut.io.inputCoefficient.poke(0.U)
        dut.io.keyLoadStart.poke(false.B)
        dut.io.keyLoadIndex.poke(0.U)
        dut.io.keyLoadValid.poke(false.B)
        dut.io.runStart.poke(false.B)
        dut.io.resultReady.poke(false.B)
        for (lane <- 0 until config.keyBuffer.loadLanes) {
          dut.io.keyLoad(lane).real.poke(0.S)
          dut.io.keyLoad(lane).imag.poke(0.S)
        }

        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.keyLoadIndex.poke(0.U)
        dut.io.keyLoadStartReady.expect(true.B)
        dut.io.keyLoadStart.poke(true.B)
        dut.clock.step()
        dut.io.keyLoadStart.poke(false.B)
        dut.io.keyLoadValid.poke(true.B)
        for (
          word <- 0 until config.keyBuffer.wordsPerCoefficient;
          group <- 0 until config.keyBuffer.loadGroupsPerRead
        ) {
          val keyRow = word / external.inputFrameBeats
          val beat = word % external.inputFrameBeats
          for (loadLane <- 0 until config.keyBuffer.loadLanes) {
            val scalar = group * config.keyBuffer.loadLanes + loadLane
            val lane = scalar / external.outputComponents
            val component = scalar % external.outputComponents
            val point = beat * external.inputLanes + lane
            val vector = key(keyRow * external.points + point)
            dut.io.keyLoad(loadLane).real.poke(vector(2 * component).S)
            dut.io.keyLoad(loadLane).imag.poke(
              vector(2 * component + 1).S
            )
          }
          dut.io.keyLoadReady.expect(true.B)
          dut.clock.step()
        }
        dut.io.keyLoadValid.poke(false.B)
        dut.io.keyLoadDone.expect(true.B)
        dut.io.keyLoadDoneIndex.expect(0.U)
        dut.clock.step()

        for (context <- 0 until contexts) {
          val input = inputRows(context)
          dut.io.inputContext.poke(context.U)
          dut.io.testVector.poke(input(0).U)
          dut.io.inputStartReady.expect(true.B)
          dut.io.inputStart.poke(true.B)
          dut.clock.step()
          dut.io.inputStart.poke(false.B)
          dut.io.inputValid.poke(true.B)
          for (value <- input.drop(1)) {
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
        }

        dut.io.runReady.expect(true.B)
        dut.io.runStart.poke(true.B)
        dut.clock.step()
        dut.io.runStart.poke(false.B)

        val rawUnit = BigInt(1) << coefficient.torusShift
        val errors = Array.fill(5)(0)
        val observedContexts = ArrayBuffer.empty[Int]
        var outputIndex = 0
        var exact = 0
        var withinOne = 0
        var maximumError = BigInt(0)
        var computeDoneCycle = Option.empty[Int]
        var firstResultCycle = Option.empty[Int]
        var cycle = 0
        var finalDone = false
        while (!finalDone) {
          val ready = cycle % 11 != 3 && cycle % 11 != 7
          dut.io.resultReady.poke(ready.B)
          if (dut.io.computeDone.peek().litToBoolean) {
            computeDoneCycle = Some(cycle)
          }
          val fire = dut.io.resultValid.peek().litToBoolean && ready
          if (fire) {
            if (firstResultCycle.isEmpty) firstResultCycle = Some(cycle)
            outputIndex should be < expected.size
            val actual = dut.io.result.peek().litValue
            val error = wrappedDifference(actual, expected(outputIndex))
            maximumError = maximumError.max(error)
            if (error == 0) exact += 1
            if (error <= rawUnit) withinOne += 1
            val errorBin = (error / rawUnit).toInt
            errorBin should be < errors.length
            errors(errorBin) += 1
            val context = outputIndex / outputsPerContext
            dut.io.resultContext.expect(context.U)
            observedContexts += dut.io.resultContext.peek().litValue.toInt
            val expectedLast = outputIndex + 1 == expected.size
            dut.io.resultLast.expect(expectedLast.B)
            dut.io.done.expect(expectedLast.B)
            outputIndex += 1
            finalDone = expectedLast
          }
          if (!finalDone) dut.io.active.expect(true.B)
          dut.clock.step()
          cycle += 1
          cycle should be < 30000
        }

        outputIndex should be(expected.size)
        observedContexts.toSeq should be(
          (0 until contexts).flatMap(context =>
            Seq.fill(outputsPerContext)(context)
          )
        )
        computeDoneCycle should not be empty
        firstResultCycle.get should be > computeDoneCycle.get
        maximumError should be <= 4 * rawUnit
        exact * 4 should be > expected.size
        withinOne * 3 should be > expected.size * 2
        dut.io.active.expect(false.B)
        dut.io.runReady.expect(false.B)
        val histogram = errors.mkString("[", ",", "]")
        info(
          s"physical Set-II Blind Rotate: cycles=$cycle, " +
            s"maximum Torus error=$maximumError " +
            s"(${maximumError / rawUnit} inverse raw units), " +
            s"exact=$exact/${expected.size}, " +
            s"within one inverse raw unit=$withinOne/${expected.size}, " +
            s"error histogram=$histogram"
        )
      }
  }
}

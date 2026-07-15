package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

/** Opt-in nonzero arithmetic validation of one complete Set-II CMUX. The
  * checked path includes coefficient rotation and decomposition, all four
  * generated forward transforms, the full external product, both generated
  * inverse transforms, and the final Torus update.
  */
final class PaperCmuxNumericalSpec
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

  private def clearTwiddle(target: GaussTwiddle): Unit = {
    target.c.poke(0.S)
    target.cMinusD.poke(0.S)
    target.cPlusD.poke(0.S)
  }

  behavior of "the complete generated Set-II CMUX"

  it should "bound a dense nonzero external product against the C++ model" in {
    val bitwise = sys.env
      .get("FPT_PAPER_BITWISE_CMUX_NUMERICS")
      .contains("1")
    if (!sys.env.get("FPT_PAPER_CMUX_NUMERICS").contains("1") && !bitwise) {
      cancel(
        "set FPT_PAPER_CMUX_NUMERICS=1 or " +
          "FPT_PAPER_BITWISE_CMUX_NUMERICS=1 to run the nonzero Set-II CMUX"
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
        "FPT_PAPER_CMUX_VECTORS",
        "../build/rtl_paper_cmux_vectors.txt"
      )
    )
    val config = PaperSetII.cmuxEngine(
      forwardPath.toString,
      inversePath.toString,
      includeVerilogSource = true,
      bitwiseBitsPerCycle = if (bitwise) Some(2) else None
    )
    val coefficient = config.coefficient
    val external = config.externalProduct
    val initial = rows.take(coefficient.polynomialSize)
    val keyCount = external.rows * external.points
    val key = rows.slice(
      coefficient.polynomialSize,
      coefficient.polynomialSize + keyCount
    )
    val expected = rows.takeRight(coefficient.polynomialSize)
    rows.size should be(coefficient.polynomialSize * 2 + keyCount)
    initial.foreach(_.length should be(coefficient.components))
    key.foreach(_.length should be(external.outputComponents * 2))
    expected.foreach(_.length should be(coefficient.components))

    val expectedChanges = (for {
      index <- 0 until coefficient.polynomialSize
      component <- 0 until coefficient.components
      if expected(index)(component) != initial(index)(component)
    } yield 1).size
    expectedChanges should be > coefficient.polynomialSize

    test(new CmuxEngine(config))
      .withAnnotations(
        Seq(
          VerilatorBackendAnnotation,
          PaperVerilator.flags
        )
      ) { dut =>
        dut.io.loadStart.poke(false.B)
        dut.io.loadValid.poke(false.B)
        dut.io.commandValid.poke(false.B)
        dut.io.exponent.poke(733.U)
        dut.io.drainStart.poke(false.B)
        dut.io.drainReady.poke(false.B)

        for (lane <- 0 until config.forwardTransform.lanes) {
          clearTwiddle(dut.io.forwardTwist(lane))
          clearTwiddle(dut.io.forwardFftTwiddle(lane))
        }
        for (lane <- 0 until config.inverseTransform.lanes) {
          clearTwiddle(dut.io.inverseFftTwiddle(lane))
          clearTwiddle(dut.io.inverseUntwist(lane))
        }

        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.loadStart.poke(true.B)
        dut.clock.step()
        dut.io.loadStart.poke(false.B)
        dut.io.loadValid.poke(true.B)
        for (beat <- 0 until coefficient.polynomialBeats) {
          dut.io.loadReady.expect(true.B)
          for (component <- 0 until coefficient.components) {
            for (lane <- 0 until coefficient.inverseLanes) {
              val index = beat * coefficient.inverseLanes + lane
              dut.io.load(component)(lane).poke(initial(index)(component).U)
            }
          }
          dut.clock.step()
        }
        dut.io.loadValid.poke(false.B)
        dut.io.loadDone.expect(true.B)
        dut.clock.step()

        def driveKey(): Unit = {
          val row = dut.io.keyRow.peek().litValue.toInt
          for (lane <- 0 until external.inputLanes) {
            val point = dut.io.keyPoint(lane).peek().litValue.toInt
            val values = key(row * external.points + point)
            for (component <- 0 until external.outputComponents) {
              dut.io.bootstrappingKey(component)(lane).real.poke(
                values(2 * component).S
              )
              dut.io.bootstrappingKey(component)(lane).imag.poke(
                values(2 * component + 1).S
              )
            }
          }
        }

        dut.io.commandReady.expect(true.B)
        dut.io.commandValid.poke(true.B)
        driveKey()
        dut.clock.step()
        dut.io.commandValid.poke(false.B)

        var cycles = 0
        while (!dut.io.done.peek().litToBoolean) {
          driveKey()
          dut.clock.step()
          cycles += 1
          cycles should be < 256
        }
        cycles should be(if (bitwise) 224 else 207)

        dut.io.drainStart.poke(true.B)
        dut.clock.step()
        dut.io.drainStart.poke(false.B)
        dut.io.drainReady.poke(true.B)
        var maximumError = BigInt(0)
        var exact = 0
        var actualChanges = 0
        val errorHistogram = Array.fill(5)(0)
        for (beat <- 0 until coefficient.polynomialBeats) {
          dut.io.drainValid.expect(true.B)
          for (component <- 0 until coefficient.components) {
            for (lane <- 0 until coefficient.inverseLanes) {
              val index = beat * coefficient.inverseLanes + lane
              val actual = dut.io.drain(component)(lane).peek().litValue
              val error = wrappedDifference(
                actual,
                expected(index)(component)
              )
              maximumError = maximumError.max(error)
              if (error == 0) exact += 1
              errorHistogram((error >> coefficient.torusShift).toInt) += 1
              if (actual != initial(index)(component)) actualChanges += 1
            }
          }
          dut.clock.step()
        }
        dut.io.drainDone.expect(true.B)
        val total = coefficient.polynomialSize * coefficient.components
        val withinOne = errorHistogram(0) + errorHistogram(1)
        info(
          s"Set-II nonzero ${if (bitwise) "bitwise " else ""}CMUX: " +
            s"latency=$cycles, " +
            s"maximum Torus error=$maximumError " +
            s"(${maximumError >> coefficient.torusShift} inverse raw units), " +
            s"exact=$exact/$total, within one inverse raw unit=$withinOne/$total, " +
            s"changed=$actualChanges, " +
            s"error histogram=${errorHistogram.mkString("[", ",", "]")}"
        )
        maximumError should be <= (BigInt(4) << coefficient.torusShift)
        exact * 4 should be > total
        withinOne * 3 should be > total * 2
        actualChanges should be > coefficient.polynomialSize
      }
  }
}

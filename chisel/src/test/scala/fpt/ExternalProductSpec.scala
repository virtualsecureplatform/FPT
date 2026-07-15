package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

final class ExternalProductSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val config = ExternalProductConfig(
    points = 16,
    lanes = 4,
    rows = 6,
    outputComponents = 2,
    spectrum = FixedFormat(18, 20),
    bootstrappingKey = FixedFormat(8, 24),
    accumulator = FixedFormat(27, 14)
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

  behavior of "the FPT External Product accumulator"

  it should "accumulate every key row and stream both TRLWE components" in {
    val rows = vectors("rtl_external_product_vectors.txt")
    rows.size should be(config.rows * config.points)

    test(new ExternalProductAccumulator(config)) { dut =>
      dut.io.start.poke(false.B)
      dut.io.inputValid.poke(false.B)
      dut.io.outputReady.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      for (row <- 0 until config.rows) {
        for (beat <- 0 until config.frameBeats) {
          dut.io.inputReady.expect(true.B)
          dut.io.keyRow.expect(row.U)
          for (lane <- 0 until config.lanes) {
            val point = beat * config.lanes + lane
            val vector = rows(row * config.points + point)
            dut.io.pointIndex(lane).expect(point.U)
            dut.io.decomposition(lane).real.poke(vector(0).S)
            dut.io.decomposition(lane).imag.poke(vector(1).S)
            for (component <- 0 until config.outputComponents) {
              val offset = 2 + 2 * component
              dut.io.bootstrappingKey(component)(lane).real.poke(
                vector(offset).S
              )
              dut.io.bootstrappingKey(component)(lane).imag.poke(
                vector(offset + 1).S
              )
            }
          }
          dut.io.inputValid.poke(true.B)
          dut.clock.step()
        }
      }
      dut.io.inputValid.poke(false.B)
      dut.io.outputValid.expect(true.B)

      for (beat <- 0 until config.frameBeats) {
        // Exercise the supported output backpressure without changing data.
        if (beat == 1) {
          dut.io.outputReady.poke(false.B)
          val heldReal = dut.io.output(0)(0).real.peek().litValue
          val heldImag = dut.io.output(0)(0).imag.peek().litValue
          dut.clock.step(2)
          dut.io.outputValid.expect(true.B)
          dut.io.output(0)(0).real.expect(heldReal.S)
          dut.io.output(0)(0).imag.expect(heldImag.S)
        }
        dut.io.outputReady.poke(true.B)
        for (lane <- 0 until config.lanes) {
          val point = beat * config.lanes + lane
          val vector = rows((config.rows - 1) * config.points + point)
          for (component <- 0 until config.outputComponents) {
            val offset = 2 + 2 * config.outputComponents + 2 * component
            withClue(
              s"beat=$beat point=$point component=$component real"
            ) {
              dut.io.output(component)(lane).real.expect(vector(offset).S)
            }
            dut.io.output(component)(lane).imag.expect(vector(offset + 1).S)
          }
        }
        dut.clock.step()
      }
      dut.io.done.expect(true.B)
      dut.io.busy.expect(false.B)
      dut.clock.step()
      dut.io.done.expect(false.B)
    }
  }
}

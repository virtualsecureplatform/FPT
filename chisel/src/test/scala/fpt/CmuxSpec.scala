package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

final class CmuxSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val config = CmuxCoefficientConfig(
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

  behavior of "the FPT CMUX coefficient store"

  it should "rotate, decompose, update, and drain a TRLWE accumulator" in {
    val allRows = vectors("rtl_cmux_frontend_vectors.txt")
    val loadRows = allRows.take(config.polynomialSize)
    val transformRowCount = config.components * config.levels * config.points
    val transformRows = allRows
      .slice(config.polynomialSize, config.polynomialSize + transformRowCount)
    val updateRows = allRows.drop(config.polynomialSize + transformRowCount)
    updateRows.size should be(config.points)

    test(new CmuxCoefficientStore(config)) { dut =>
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(false.B)
      dut.io.rowStart.poke(false.B)
      dut.io.pairReady.poke(false.B)
      dut.io.updateStart.poke(false.B)
      dut.io.updateValid.poke(false.B)
      dut.io.drainStart.poke(false.B)
      dut.io.drainReady.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.loadStart.poke(true.B)
      dut.clock.step()
      dut.io.loadStart.poke(false.B)
      for (beat <- 0 until config.polynomialBeats) {
        dut.io.loadReady.expect(true.B)
        for (lane <- 0 until config.inverseLanes) {
          val index = beat * config.inverseLanes + lane
          for (component <- 0 until config.components)
            dut.io.load(component)(lane).poke(loadRows(index)(component).U)
        }
        dut.io.loadValid.poke(true.B)
        dut.clock.step()
      }
      dut.io.loadValid.poke(false.B)
      dut.io.loadDone.expect(true.B)
      dut.io.loaded.expect(true.B)
      dut.clock.step()

      for (component <- 0 until config.components) {
        for (level <- 0 until config.levels) {
          val base = (component * config.levels + level) * config.points
          val expected = transformRows.slice(base, base + config.points)
          expected.head(0) should be(component)
          expected.head(1) should be(level)
          dut.io.rowComponent.poke(component.U)
          dut.io.rowLevel.poke(level.U)
          dut.io.exponent.poke(expected.head(2).U)
          dut.io.rowStart.poke(true.B)
          dut.clock.step()
          dut.io.rowStart.poke(false.B)
          dut.io.pairReady.poke(true.B)
          for (beat <- 0 until config.forwardBeats) {
            dut.io.pairValid.expect(true.B)
            for (lane <- 0 until config.forwardLanes) {
              val point = beat * config.forwardLanes + lane
              val row = expected(point)
              row(3) should be(point)
              withClue(
                s"component=$component level=$level point=$point low"
              ) {
                dut.io.coefficientLow(lane).expect(row(4).S)
              }
              dut.io.coefficientHigh(lane).expect(row(5).S)
            }
            dut.clock.step()
          }
          dut.io.pairReady.poke(false.B)
          dut.io.rowDone.expect(true.B)
          dut.clock.step()
        }
      }

      dut.io.updateStart.poke(true.B)
      dut.clock.step()
      dut.io.updateStart.poke(false.B)
      for (beat <- 0 until config.inverseBeats) {
        dut.io.updateReady.expect(true.B)
        for (lane <- 0 until config.inverseLanes) {
          val point = beat * config.inverseLanes + lane
          val row = updateRows(point)
          row(0) should be(point)
          for (component <- 0 until config.components) {
            dut.io.updateLow(component)(lane).poke(row(1 + 2 * component).S)
            dut.io.updateHigh(component)(lane).poke(row(2 + 2 * component).S)
          }
        }
        dut.io.updateValid.poke(true.B)
        dut.clock.step()
      }
      dut.io.updateValid.poke(false.B)
      dut.io.updateDone.expect(true.B)
      dut.clock.step()

      dut.io.drainStart.poke(true.B)
      dut.clock.step()
      dut.io.drainStart.poke(false.B)
      dut.io.drainReady.poke(true.B)
      for (beat <- 0 until config.polynomialBeats) {
        dut.io.drainValid.expect(true.B)
        for (lane <- 0 until config.inverseLanes) {
          val index = beat * config.inverseLanes + lane
          val point = index & (config.points - 1)
          val halfOffset = if (index < config.points) 0 else 1
          for (component <- 0 until config.components) {
            val expectedOffset = 1 + 2 * config.components +
              2 * component + halfOffset
            withClue(s"component=$component index=$index") {
              dut.io.drain(component)(lane).expect(
                updateRows(point)(expectedOffset).U
              )
            }
          }
        }
        dut.clock.step()
      }
      dut.io.drainDone.expect(true.B)
      dut.io.idle.expect(true.B)
    }
  }
}

package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

final class BatchedCoefficientStoreSpec
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
  private val contexts = 2

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

  behavior of "the batched FPT coefficient store"

  it should "switch contexts at the row-stream boundary and route updates" in {
    val allRows = vectors("rtl_cmux_frontend_vectors.txt")
    val loadRows = allRows.take(config.polynomialSize)
    val transformRowCount = config.components * config.levels * config.points
    val transformRows = allRows
      .slice(config.polynomialSize, config.polynomialSize + transformRowCount)
    val updateRows = allRows.drop(config.polynomialSize + transformRowCount)
    val exponent = transformRows.head(2)
    val rows = config.components * config.levels
    val torusMask = (BigInt(1) << config.torusWidth) - 1

    def expectedCoefficient(component: Int, level: Int, index: Int): BigInt = {
      val rotation = exponent.toInt & (config.polynomialSize - 1)
      val sourceIndex = (index - rotation) & (config.polynomialSize - 1)
      val source = loadRows(sourceIndex)(component)
      val current = loadRows(index)(component)
      val rotated = if (index < rotation) (-source) & torusMask else source
      val difference = (rotated - current) & torusMask
      val biased = (difference + config.decompositionBias) & torusMask
      val shift = config.torusWidth - (level + 1) * config.baseBits
      val digit = (biased >> shift) &
        ((BigInt(1) << config.baseBits) - 1)
      val centered = digit - (BigInt(1) << (config.baseBits - 1))
      centered << config.forwardFormat.fractionalBits
    }

    test(new BatchedCmuxCoefficientStore(config, contexts)) { dut =>
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(false.B)
      dut.io.commandValid.poke(false.B)
      dut.io.commandContext.poke(0.U)
      dut.io.exponent.poke(exponent.U)
      dut.io.pairReady.poke(true.B)
      dut.io.updateValid.poke(false.B)
      dut.io.updateFirst.poke(false.B)
      dut.io.updateContext.poke(0.U)
      dut.io.drainStart.poke(false.B)
      dut.io.drainReady.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      for (context <- 0 until contexts) {
        dut.io.loadContext.poke(context.U)
        dut.io.loadStart.poke(true.B)
        dut.clock.step()
        dut.io.loadStart.poke(false.B)
        dut.io.loadValid.poke(true.B)
        for (beat <- 0 until config.polynomialBeats) {
          dut.io.loadReady.expect(true.B)
          for (lane <- 0 until config.inverseLanes) {
            val index = beat * config.inverseLanes + lane
            for (component <- 0 until config.components) {
              dut.io.load(component)(lane).poke(loadRows(index)(component).U)
            }
          }
          dut.clock.step()
        }
        dut.io.loadValid.poke(false.B)
        dut.io.loadDone.expect(true.B)
        dut.io.loadDoneContext.expect(context.U)
        dut.clock.step()
      }

      dut.io.commandContext.poke(0.U)
      dut.io.commandValid.poke(true.B)
      dut.io.commandReady.expect(true.B)
      dut.io.transformStart.expect(true.B)
      dut.clock.step()
      dut.io.commandValid.poke(false.B)

      for (transaction <- 0 until contexts) {
        for (row <- 0 until rows) {
          val component = row / config.levels
          val level = row % config.levels
          for (beat <- 0 until config.forwardBeats) {
            val finalTransactionBeat = row == rows - 1 &&
              beat == config.forwardBeats - 1
            if (transaction == 0 && finalTransactionBeat) {
              dut.io.commandContext.poke(1.U)
              dut.io.commandValid.poke(true.B)
              dut.io.commandReady.expect(true.B)
            }
            dut.io.pairValid.expect(true.B)
            dut.io.rowIndex.expect(row.U)
            dut.io.pairLast.expect((beat == config.forwardBeats - 1).B)
            dut.io.transformStart.expect(
              ((beat == config.forwardBeats - 1 && row < rows - 1) ||
                (transaction == 0 && finalTransactionBeat)).B
            )
            for (lane <- 0 until config.forwardLanes) {
              val point = beat * config.forwardLanes + lane
              dut.io.coefficientLow(lane).expect(
                expectedCoefficient(component, level, point).S
              )
              dut.io.coefficientHigh(lane).expect(
                expectedCoefficient(
                  component,
                  level,
                  point + config.points
                ).S
              )
            }
            dut.clock.step()
            if (transaction == 0 && finalTransactionBeat) {
              dut.io.commandValid.poke(false.B)
            }
          }
        }
      }
      dut.io.pairValid.expect(false.B)
      for (context <- 0 until contexts) {
        dut.io.contextBusy(context).expect(true.B)
      }

      for (context <- 0 until contexts) {
        for (beat <- 0 until config.inverseBeats) {
          dut.io.updateFirst.poke((beat == 0).B)
          dut.io.updateContext.poke(context.U)
          dut.io.updateReady.expect(true.B)
          for (lane <- 0 until config.inverseLanes) {
            val point = beat * config.inverseLanes + lane
            val update = updateRows(point)
            for (component <- 0 until config.components) {
              dut.io.updateLow(component)(lane).poke(
                update(1 + 2 * component).S
              )
              dut.io.updateHigh(component)(lane).poke(
                update(2 + 2 * component).S
              )
            }
          }
          dut.io.updateValid.poke(true.B)
          dut.clock.step()
        }
        dut.io.updateValid.poke(false.B)
        dut.io.updateFirst.poke(false.B)
        dut.io.updateDone.expect(true.B)
        dut.io.updateDoneContext.expect(context.U)
        dut.io.contextBusy(context).expect(false.B)
        dut.clock.step()
      }

      for (context <- 0 until contexts) {
        dut.io.drainContext.poke(context.U)
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
              dut.io.drain(component)(lane).expect(
                updateRows(point)(expectedOffset).U
              )
            }
          }
          dut.clock.step()
        }
        dut.io.drainDone.expect(true.B)
        dut.io.drainDoneContext.expect(context.U)
        dut.io.drainReady.poke(false.B)
        dut.clock.step()
      }
    }
  }
}

package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer

final class WindowedPrefetchedCoefficientStoreSpec
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
    inverseFormat = FixedFormat(27, 14),
    windowedRotator = true
  )
  private val contexts = 2
  private val rows = config.components * config.levels
  private val commandInterval = rows * config.forwardBeats
  private val torusMask = (BigInt(1) << config.torusWidth) - 1
  private val exponent = 13

  private def initial(component: Int, index: Int): BigInt =
    (BigInt("89abcdef", 16) +
      BigInt("10204081", 16) * component +
      BigInt("10101", 16) * index) & torusMask

  private def update(
      high: Boolean,
      component: Int,
      point: Int
  ): BigInt = {
    val magnitude = 3 + component * 29 + point
    if (high) -magnitude else magnitude
  }

  private def expectedCoefficient(
      component: Int,
      level: Int,
      index: Int
  ): BigInt = {
    val rotation = exponent & (config.polynomialSize - 1)
    val sourceIndex = (index - rotation) & (config.polynomialSize - 1)
    val source = initial(component, sourceIndex)
    val current = initial(component, index)
    val rotated = if (index < rotation) (-source) & torusMask else source
    val difference = (rotated - current) & torusMask
    val biased = (difference + config.decompositionBias) & torusMask
    val shift = config.torusWidth - (level + 1) * config.baseBits
    val digit = (biased >> shift) &
      ((BigInt(1) << config.baseBits) - 1)
    val centered = digit - (BigInt(1) << (config.baseBits - 1))
    centered << config.forwardFormat.fractionalBits
  }

  private def expectedUpdated(component: Int, index: Int): BigInt = {
    val high = index >= config.points
    val point = index & (config.points - 1)
    val delta = update(high, component, point) << config.torusShift
    (initial(component, index) + delta) & torusMask
  }

  behavior of "the BRAM-prefetched coefficient frontend"

  it should "overlap context prefetch and preserve the CMUX row interval" in {
    test(new PrefetchedBatchedCmuxCoefficientStore(config, contexts))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
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
      for (component <- 0 until config.components) {
        for (lane <- 0 until config.inverseLanes) {
          dut.io.load(component)(lane).poke(0.U)
          dut.io.updateLow(component)(lane).poke(0.S)
          dut.io.updateHigh(component)(lane).poke(0.S)
        }
      }
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
          for (component <- 0 until config.components) {
            for (lane <- 0 until config.inverseLanes) {
              val index = beat * config.inverseLanes + lane
              dut.io.load(component)(lane).poke(initial(component, index).U)
            }
          }
          dut.clock.step()
        }
        dut.io.loadValid.poke(false.B)
        dut.io.loadDone.expect(true.B)
        dut.clock.step()
      }

      var cycle = 0
      var pairCount = 0
      val acceptCycles = ArrayBuffer.empty[Int]
      val firstPairCycles = ArrayBuffer.empty[Int]

      def observePair(): Unit = {
        if (dut.io.pairValid.peek().litToBoolean) {
          val transaction = pairCount / commandInterval
          val transactionBeat = pairCount % commandInterval
          val row = transactionBeat / config.forwardBeats
          val beat = transactionBeat % config.forwardBeats
          if (beat == 0 && row == 0) {
            firstPairCycles += cycle
          }
          dut.io.rowIndex.expect(row.U)
          dut.io.pairLast.expect((beat == config.forwardBeats - 1).B)
          val component = row / config.levels
          val level = row % config.levels
          for (lane <- 0 until config.forwardLanes) {
            val point = beat * config.forwardLanes + lane
            dut.io.coefficientLow(lane).expect(
              expectedCoefficient(component, level, point).S
            )
            dut.io.coefficientHigh(lane).expect(
              expectedCoefficient(
                component,
                level,
                config.points + point
              ).S
            )
          }
          pairCount += 1
          transaction should be < contexts
        }
      }

      def step(): Unit = {
        observePair()
        dut.clock.step()
        cycle += 1
      }

      for (context <- 0 until contexts) {
        dut.io.commandContext.poke(context.U)
        while (!dut.io.commandReady.peek().litToBoolean) {
          step()
          cycle should be < 100
        }
        acceptCycles += cycle
        dut.io.commandValid.poke(true.B)
        step()
        dut.io.commandValid.poke(false.B)
      }
      while (pairCount < contexts * commandInterval) {
        step()
        cycle should be < 150
      }
      acceptCycles.toSeq should be(Seq(0, commandInterval))
      firstPairCycles(1) - firstPairCycles(0) should be(commandInterval)
      info(
        s"prefetched coefficient rows remain ${commandInterval} cycles apart"
      )

      // Apply one continuous inverse frame to each context. The memory bank
      // performs the read-modify-write one cycle behind this input stream.
      for (context <- 0 until contexts) {
        dut.io.updateContext.poke(context.U)
        dut.io.updateValid.poke(true.B)
        for (beat <- 0 until config.inverseBeats) {
          dut.io.updateFirst.poke((beat == 0).B)
          for (component <- 0 until config.components) {
            for (lane <- 0 until config.inverseLanes) {
              val point = beat * config.inverseLanes + lane
              dut.io.updateLow(component)(lane).poke(
                update(high = false, component, point).S
              )
              dut.io.updateHigh(component)(lane).poke(
                update(high = true, component, point).S
              )
            }
          }
          dut.io.updateReady.expect(true.B)
          dut.clock.step()
        }
        dut.io.updateValid.poke(false.B)
        dut.io.updateFirst.poke(false.B)
        dut.io.updateDone.expect(false.B)
        dut.clock.step()
        dut.io.updateDone.expect(true.B)
        dut.io.updateDoneContext.expect(context.U)
        dut.clock.step()
        dut.io.contextBusy(context).expect(false.B)
      }

      for (context <- 0 until contexts) {
        dut.io.drainContext.poke(context.U)
        dut.io.drainStart.poke(true.B)
        dut.clock.step()
        dut.io.drainStart.poke(false.B)
        dut.io.drainReady.poke(true.B)
        var waitCycles = 0
        while (!dut.io.drainValid.peek().litToBoolean) {
          dut.clock.step()
          waitCycles += 1
          waitCycles should be <= config.inverseBeats + 3
        }
        for (beat <- 0 until config.polynomialBeats) {
          dut.io.drainValid.expect(true.B)
          for (component <- 0 until config.components) {
            for (lane <- 0 until config.inverseLanes) {
              val index = beat * config.inverseLanes + lane
              dut.io.drain(component)(lane).expect(
                expectedUpdated(component, index).U
              )
            }
          }
          dut.clock.step()
        }
        dut.io.drainReady.poke(false.B)
        dut.io.drainDone.expect(true.B)
        dut.io.drainDoneContext.expect(context.U)
        dut.clock.step()
      }
    }
  }

  it should "alternate transposed worksets at the CMUX row interval" in {
    val config = CmuxCoefficientConfig(
      polynomialSize = 16,
      forwardLanes = 4,
      inverseLanes = 4,
      components = 2,
      levels = 2,
      baseBits = 3,
      torusWidth = 8,
      forwardFormat = FixedFormat(5, 3),
      inverseFormat = FixedFormat(5, 3)
    )
    val contexts = 2
    val commandInterval = config.components * config.levels *
      config.forwardBeats
    val exponent = 5
    val torusMask = (BigInt(1) << config.torusWidth) - 1

    def initial(component: Int, index: Int): BigInt =
      BigInt(component * 83 + index * 29 + 7) & torusMask

    def expectedCoefficient(
        component: Int,
        level: Int,
        index: Int
    ): BigInt = {
      val rotation = exponent & (config.polynomialSize - 1)
      val sourceIndex = (index - rotation) & (config.polynomialSize - 1)
      val source = initial(component, sourceIndex)
      val current = initial(component, index)
      val rotated = if (index < rotation) (-source) & torusMask else source
      val biased = (rotated - current + config.decompositionBias) & torusMask
      val shift = config.torusWidth - (level + 1) * config.baseBits
      val digit = (biased >> shift) &
        ((BigInt(1) << config.baseBits) - 1)
      val centered = digit - (BigInt(1) << (config.baseBits - 1))
      centered << config.forwardFormat.fractionalBits
    }

    test(
      new BitwisePrefetchedBatchedCmuxCoefficientStore(
        config,
        contexts,
        bitsPerCycle = 1
      )
    ) { dut =>
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
      for (component <- 0 until config.components) {
        for (lane <- 0 until config.inverseLanes) {
          dut.io.load(component)(lane).poke(0.U)
          dut.io.updateLow(component)(lane).poke(0.S)
          dut.io.updateHigh(component)(lane).poke(0.S)
        }
      }
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
          for (component <- 0 until config.components) {
            for (lane <- 0 until config.inverseLanes) {
              val index = beat * config.inverseLanes + lane
              dut.io.load(component)(lane).poke(initial(component, index).U)
            }
          }
          dut.clock.step()
        }
        dut.io.loadValid.poke(false.B)
        dut.io.loadDone.expect(true.B)
        dut.clock.step()
      }

      var cycle = 0
      var pairCount = 0
      val acceptCycles = ArrayBuffer.empty[Int]
      val firstPairCycles = ArrayBuffer.empty[Int]
      val transformStartCycles = ArrayBuffer.empty[Int]

      def step(): Unit = {
        if (dut.io.transformStart.peek().litToBoolean) {
          transformStartCycles += cycle
        }
        if (dut.io.pairValid.peek().litToBoolean) {
          val transactionBeat = pairCount % commandInterval
          val row = transactionBeat / config.forwardBeats
          val beat = transactionBeat % config.forwardBeats
          if (transactionBeat == 0) firstPairCycles += cycle
          dut.io.rowIndex.expect(row.U)
          dut.io.pairLast.expect((beat == config.forwardBeats - 1).B)
          val component = row / config.levels
          val level = row % config.levels
          for (lane <- 0 until config.forwardLanes) {
            val point = beat * config.forwardLanes + lane
            dut.io.coefficientLow(lane).expect(
              expectedCoefficient(component, level, point).S
            )
            dut.io.coefficientHigh(lane).expect(
              expectedCoefficient(
                component,
                level,
                config.points + point
              ).S
            )
          }
          pairCount += 1
        }
        dut.clock.step()
        cycle += 1
      }

      for (context <- 0 until contexts) {
        dut.io.commandContext.poke(context.U)
        while (!dut.io.commandReady.peek().litToBoolean) {
          step()
          cycle should be < 160
        }
        acceptCycles += cycle
        dut.io.commandValid.poke(true.B)
        step()
        dut.io.commandValid.poke(false.B)
      }
      while (pairCount < contexts * commandInterval) {
        step()
        cycle should be < 200
      }

      acceptCycles.toSeq should be(Seq(0, commandInterval))
      firstPairCycles(1) - firstPairCycles(0) should be(commandInterval)
      transformStartCycles.size should be(contexts * config.components *
        config.levels)
      transformStartCycles.head should be(firstPairCycles.head)
      transformStartCycles.last should be(firstPairCycles(1) +
        commandInterval - config.forwardBeats - 1)
      transformStartCycles(config.components * config.levels) should be(
        firstPairCycles(1) - 1
      )
      info(
        s"bitwise prefetched rows remain ${commandInterval} cycles apart " +
          "with an early SGen marker at the command boundary"
      )

      for (context <- 0 until contexts) {
        dut.io.updateContext.poke(context.U)
        dut.io.updateValid.poke(true.B)
        for (beat <- 0 until config.inverseBeats) {
          dut.io.updateFirst.poke((beat == 0).B)
          dut.io.updateReady.expect(true.B)
          dut.clock.step()
        }
        dut.io.updateValid.poke(false.B)
        dut.io.updateFirst.poke(false.B)
        dut.io.updateDone.expect(false.B)
        dut.clock.step()
        dut.io.updateDone.expect(true.B)
        dut.clock.step()
      }

      for (context <- 0 until contexts) {
        dut.io.drainContext.poke(context.U)
        dut.io.drainStart.poke(true.B)
        dut.clock.step()
        dut.io.drainStart.poke(false.B)
        dut.io.drainReady.poke(true.B)
        var drainWait = 0
        while (!dut.io.drainValid.peek().litToBoolean) {
          dut.clock.step()
          drainWait += 1
          drainWait should be <= config.inverseBeats + 3
        }
        for (beat <- 0 until config.polynomialBeats) {
          dut.io.drainValid.expect(true.B)
          for (component <- 0 until config.components) {
            for (lane <- 0 until config.inverseLanes) {
              val index = beat * config.inverseLanes + lane
              dut.io.drain(component)(lane).expect(
                initial(component, index).U
              )
            }
          }
          dut.clock.step()
        }
        dut.io.drainReady.poke(false.B)
        dut.io.drainDone.expect(true.B)
        dut.io.drainDoneContext.expect(context.U)
        dut.clock.step()
      }
    }
  }
}

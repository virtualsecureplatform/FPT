package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer

final class PrecomputedWindowedCoefficientStoreSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val config = CmuxCoefficientConfig(
    polynomialSize = 32,
    forwardLanes = 4,
    inverseLanes = 2,
    components = 2,
    levels = 2,
    baseBits = 3,
    torusWidth = 8,
    forwardFormat = FixedFormat(5, 3),
    inverseFormat = FixedFormat(5, 3),
    windowedRotator = true
  )
  private val contexts = 4
  private val commandInterval = config.components * config.levels *
    config.forwardBeats
  private val torusMask = (BigInt(1) << config.torusWidth) - 1
  private val preprocessGuardBits =
    sys.env.get("FPT_TEST_COEFFICIENT_GUARD_BITS").map(_.toInt)

  private def initial(context: Int, component: Int, index: Int): BigInt =
    BigInt(context * 97 + component * 53 + index * 29 + 7) & torusMask

  private def expected(
      context: Int,
      exponent: Int,
      component: Int,
      level: Int,
      index: Int
  ): BigInt = {
    val preprocessWidth = preprocessGuardBits
      .map(config.levels * config.baseBits + _)
      .getOrElse(config.torusWidth)
    val discardBits = config.torusWidth - preprocessWidth
    val preprocessMask = (BigInt(1) << preprocessWidth) - 1
    val rotation = exponent & (config.polynomialSize - 1)
    val sourceIndex = (index - rotation) & (config.polynomialSize - 1)
    val source = initial(context, component, sourceIndex) >> discardBits
    val current = initial(context, component, index) >> discardBits
    val highNegate = (exponent & config.polynomialSize) != 0
    val wraps = index < rotation
    val rotated =
      if (wraps ^ highNegate) (-source) & preprocessMask else source
    val biased =
      (rotated - current + (config.decompositionBias >> discardBits)) &
        preprocessMask
    val shift = preprocessWidth - (level + 1) * config.baseBits
    val digit = (biased >> shift) &
      ((BigInt(1) << config.baseBits) - 1)
    val centered = digit - (BigInt(1) << (config.baseBits - 1))
    centered << config.forwardFormat.fractionalBits
  }

  behavior of "the precomputed windowed coefficient frontend"

  for (exponentBase <- 0 until 2 * config.polynomialSize by contexts) {
  it should s"preserve data and cycles for exponents $exponentBase through ${exponentBase + contexts - 1}" in {
    test(
      if (sys.env.get("FPT_U280_COEFFICIENT_LOCALITY").contains("1")) {
        new PrecomputedWindowedBatchedCmuxCoefficientStore(
          config, contexts,
          bufferedSingleAccumulator = sys.env.get("FPT_TEST_SINGLE_ACCUMULATOR").contains("1"),
          coefficientPreprocessGuardBits = preprocessGuardBits,
          coefficientLocality = true,
          groupedScratchControls = sys.env.get("FPT_COEFFICIENT_SCRATCH_CONTROL").contains("grouped")
        )
      } else if (sys.env.get("FPT_COEFFICIENT_SCRATCH_CONTROL").contains("grouped")) {
        new PrecomputedWindowedBatchedCmuxCoefficientStore(
          config, contexts,
          bufferedSingleAccumulator = sys.env.get("FPT_TEST_SINGLE_ACCUMULATOR").contains("1"),
          coefficientPreprocessGuardBits = preprocessGuardBits,
          fieldSelectedScratchReads = true,
          groupedScratchControls = true
        )
      } else new FieldSelectedFrontendMiter(
        config,
        contexts,
        guardBits = preprocessGuardBits,
        singleAccumulator = sys.env.get("FPT_TEST_SINGLE_ACCUMULATOR").contains("1")
      )
    ).withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(false.B)
      dut.io.commandValid.poke(false.B)
      dut.io.commandContext.poke(0.U)
      dut.io.exponent.poke(0.U)
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
              dut.io.load(component)(lane).poke(
                initial(context, component, index).U
              )
            }
          }
          dut.clock.step()
        }
        dut.io.loadValid.poke(false.B)
        dut.io.loadDone.expect(true.B)
        dut.clock.step()
      }

      val exponents = Seq.tabulate(contexts)(exponentBase + _)
      var cycle = 0
      var pairCount = 0
      val backpressureCycles = 3
      val markerBackpressureCycles = 2
      var exercisedBackpressure = false
      var exercisedMarkerBackpressure = false
      val acceptCycles = ArrayBuffer.empty[Int]
      val firstPairCycles = ArrayBuffer.empty[Int]
      val transformStartCycles = ArrayBuffer.empty[Int]

      def observe(): Unit = {
        if (dut.io.transformStart.peek().litToBoolean) {
          transformStartCycles += cycle
        }
        if (dut.io.pairValid.peek().litToBoolean) {
          val transaction = pairCount / commandInterval
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
            withClue(
              s"transaction=$transaction row=$row beat=$beat lane=$lane: "
            ) {
              dut.io.coefficientLow(lane).expect(
                expected(
                  transaction,
                  exponents(transaction),
                  component,
                  level,
                  point
                ).S
              )
              dut.io.coefficientHigh(lane).expect(
                expected(
                  transaction,
                  exponents(transaction),
                  component,
                  level,
                  config.points + point
                ).S
              )
            }
          }
          pairCount += 1
        }
      }

      def step(): Unit = {
        observe()
        dut.clock.step()
        cycle += 1
      }

      for (context <- 0 until contexts) {
        dut.io.commandContext.poke(context.U)
        dut.io.exponent.poke(exponents(context).U)
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
        val stalledTransaction = contexts - 1
        val markerStalledPair = stalledTransaction * commandInterval +
          config.forwardBeats - 1
        val stalledPair = contexts * commandInterval - 1
        if (
          !exercisedMarkerBackpressure && pairCount == markerStalledPair &&
          dut.io.pairValid.peek().litToBoolean
        ) {
          dut.io.pairLast.expect(true.B)
          dut.io.transformStart.expect(true.B)
          dut.io.pairReady.poke(false.B)
          dut.io.transformStart.expect(false.B)
          for (_ <- 0 until markerBackpressureCycles) {
            dut.io.pairValid.expect(true.B)
            dut.io.pairLast.expect(true.B)
            dut.io.transformStart.expect(false.B)
            dut.clock.step()
            cycle += 1
          }
          dut.io.pairReady.poke(true.B)
          dut.io.transformStart.expect(true.B)
          exercisedMarkerBackpressure = true
        }
        if (
          !exercisedBackpressure && pairCount == stalledPair &&
          dut.io.pairValid.peek().litToBoolean
        ) {
          dut.io.pairReady.poke(false.B)
          for (_ <- 0 until backpressureCycles) {
            dut.io.pairValid.expect(true.B)
            dut.io.rowIndex.expect((config.components * config.levels - 1).U)
            dut.io.pairLast.expect(true.B)
            for (lane <- 0 until config.forwardLanes) {
              val point =
                (config.forwardBeats - 1) * config.forwardLanes + lane
              dut.io.coefficientLow(lane).expect(
                expected(
                  stalledTransaction,
                  exponents(stalledTransaction),
                  config.components - 1,
                  config.levels - 1,
                  point
                ).S
              )
              dut.io.coefficientHigh(lane).expect(
                expected(
                  stalledTransaction,
                  exponents(stalledTransaction),
                  config.components - 1,
                  config.levels - 1,
                  config.points + point
                ).S
              )
            }
            dut.clock.step()
            cycle += 1
          }
          dut.io.pairReady.poke(true.B)
          exercisedBackpressure = true
        }
        step()
        cycle should be < 160
      }

      acceptCycles.toSeq should be(
        Seq.tabulate(contexts)(_ * commandInterval)
      )
      info(s"locality=${sys.env.getOrElse("FPT_U280_COEFFICIENT_LOCALITY", "0")} " +
        s"firstPairCycles=${firstPairCycles.mkString(",")}")
      firstPairCycles.sliding(2).foreach { pair =>
        withClue(s"firstPairCycles=$firstPairCycles: ") {
          pair(1) - pair(0) should be(commandInterval)
        }
      }
      exercisedMarkerBackpressure should be(true)
      exercisedBackpressure should be(true)
      transformStartCycles.size should be(contexts * config.components *
        config.levels)
      val rowCount = config.components * config.levels
      for {
        transaction <- 0 until contexts
        row <- 0 until rowCount
      } {
        val markerStallDelay =
          if (transaction == contexts - 1 && row > 0) {
            markerBackpressureCycles
          } else {
            0
          }
        transformStartCycles(transaction * config.components * config.levels +
          row) should be(firstPairCycles(transaction) +
          row * config.forwardBeats - 1 + markerStallDelay)
      }
    }
  }
  }
}

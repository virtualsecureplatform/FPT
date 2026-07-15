package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class BitwiseFoldSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  behavior of "the folded bitwise CMUX frontend"

  it should "stream both components and levels in forward-transform order" in {
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
    val bitsPerCycle = 1
    val modulus = BigInt(1) << config.torusWidth
    val mask = modulus - 1
    val baseMask = (BigInt(1) << config.baseBits) - 1
    val halfBase = BigInt(1) << (config.baseBits - 1)
    val inputs = Seq.tabulate(config.components, config.polynomialSize) {
      case (_, 0) => BigInt(0)
      case (component, position) =>
        BigInt((component * 97 + position * 41 + 13) & mask.toInt)
    }

    def rotate(component: Int, exponent: Int): Seq[BigInt] = {
      val shift = exponent & (config.polynomialSize - 1)
      val highNegate = (exponent & config.polynomialSize) != 0
      Seq.tabulate(config.polynomialSize) { position =>
        val source = (position - shift + config.polynomialSize) %
          config.polynomialSize
        val wraps = position < shift
        val value = inputs(component)(source)
        if (wraps ^ highNegate) (-value) & mask else value
      }
    }

    def expected(
        component: Int,
        level: Int,
        position: Int,
        exponent: Int
    ): BigInt = {
      val rotated = rotate(component, exponent)
      val biased = (rotated(position) - inputs(component)(position) +
        config.decompositionBias) & mask
      val shift = config.torusWidth - (level + 1) * config.baseBits
      val centered = ((biased >> shift) & baseMask) - halfBase
      centered << config.forwardFormat.fractionalBits
    }

    test(new BitwiseCmuxForwardFrontend(config, bitsPerCycle)) { dut =>
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(false.B)
      dut.io.rotateStart.poke(false.B)
      dut.io.updateStart.poke(false.B)
      dut.io.updateValid.poke(false.B)
      dut.io.drainStart.poke(false.B)
      dut.io.drainReady.poke(false.B)
      dut.io.exponent.poke(0.U)
      dut.io.pairReady.poke(false.B)
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

      dut.io.loadStart.poke(true.B)
      dut.clock.step()
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(true.B)
      for (beat <- 0 until config.polynomialBeats) {
        for (component <- 0 until config.components) {
          for (lane <- 0 until config.inverseLanes) {
            dut.io.load(component)(lane).poke(
              inputs(component)(beat * config.inverseLanes + lane).U
            )
          }
        }
        dut.clock.step()
      }
      dut.io.loadValid.poke(false.B)
      dut.io.loadDone.expect(true.B)
      dut.clock.step()

      for (exponent <- 0 until 2 * config.polynomialSize) {
        dut.io.exponent.poke(exponent.U)
        dut.io.rotateStart.poke(true.B)
        dut.clock.step()
        dut.io.rotateStart.poke(false.B)

        var outputBeat = 0
        var insertedStall = false
        while (!dut.io.done.peek().litToBoolean) {
          if (dut.io.pairValid.peek().litToBoolean) {
            val row = outputBeat / config.forwardBeats
            val beat = outputBeat % config.forwardBeats
            val component = row / config.levels
            val level = row % config.levels
            dut.io.rowIndex.expect(row.U)
            for (lane <- 0 until config.forwardLanes) {
              val low = beat * config.forwardLanes + lane
              val high = low + config.points
              dut.io.coefficientLow(lane).expect(
                expected(component, level, low, exponent).S
              )
              dut.io.coefficientHigh(lane).expect(
                expected(component, level, high, exponent).S
              )
            }
            val stall = outputBeat == 3 && (exponent & 1) != 0 &&
              !insertedStall
            dut.io.pairReady.poke((!stall).B)
            if (stall) insertedStall = true else outputBeat += 1
          } else {
            dut.io.pairReady.poke(false.B)
          }
          dut.clock.step()
        }
        outputBeat should be(config.components * config.levels *
          config.forwardBeats)
        dut.clock.step()
      }

      val updated = inputs.map(_.toArray).toArray
      dut.io.updateStart.poke(true.B)
      dut.clock.step()
      dut.io.updateStart.poke(false.B)
      dut.io.updateValid.poke(true.B)
      for (beat <- 0 until config.inverseBeats) {
        dut.io.updateReady.expect(true.B)
        for (component <- 0 until config.components) {
          for (lane <- 0 until config.inverseLanes) {
            val lowDelta = component * 3 + beat * 2 + lane - 4
            val highDelta = component * 2 - beat * 3 - lane + 1
            dut.io.updateLow(component)(lane).poke(lowDelta.S)
            dut.io.updateHigh(component)(lane).poke(highDelta.S)
            val lowIndex = beat * config.inverseLanes + lane
            val highIndex = config.points + lowIndex
            updated(component)(lowIndex) =
              (updated(component)(lowIndex) +
                (BigInt(lowDelta) << config.torusShift)) & mask
            updated(component)(highIndex) =
              (updated(component)(highIndex) +
                (BigInt(highDelta) << config.torusShift)) & mask
          }
        }
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
        for (component <- 0 until config.components) {
          for (lane <- 0 until config.inverseLanes) {
            dut.io.drain(component)(lane).expect(
              updated(component)(beat * config.inverseLanes + lane).U
            )
          }
        }
        dut.clock.step()
      }
      dut.io.drainDone.expect(true.B)
    }
  }

  it should "sustain one rotation per bitwise pass with ping-pong buffers" in {
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
    val bitsPerCycle = 1
    val chunks = config.torusWidth / bitsPerCycle
    val transactions = config.components * config.levels *
      config.forwardBeats
    val modulus = BigInt(1) << config.torusWidth
    val mask = modulus - 1
    val baseMask = (BigInt(1) << config.baseBits) - 1
    val halfBase = BigInt(1) << (config.baseBits - 1)
    val inputs = Seq.tabulate(config.components, config.polynomialSize) {
      case (component, position) =>
        BigInt((component * 83 + position * 29 + 7) & mask.toInt)
    }
    val exponents = Seq(1, 7, 16, 29, 4)

    def expected(
        component: Int,
        level: Int,
        position: Int,
        exponent: Int
    ): BigInt = {
      val shiftAmount = exponent & (config.polynomialSize - 1)
      val source = (position - shiftAmount + config.polynomialSize) %
        config.polynomialSize
      val negate = (position < shiftAmount) ^
        ((exponent & config.polynomialSize) != 0)
      val sourceValue = inputs(component)(source)
      val rotated = if (negate) (-sourceValue) & mask else sourceValue
      val biased = (rotated - inputs(component)(position) +
        config.decompositionBias) & mask
      val digitShift = config.torusWidth -
        (level + 1) * config.baseBits
      val centered = ((biased >> digitShift) & baseMask) - halfBase
      centered << config.forwardFormat.fractionalBits
    }

    test(new BitwiseCmuxForwardFrontend(config, bitsPerCycle)) { dut =>
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(false.B)
      dut.io.rotateStart.poke(false.B)
      dut.io.updateStart.poke(false.B)
      dut.io.updateValid.poke(false.B)
      dut.io.drainStart.poke(false.B)
      dut.io.drainReady.poke(false.B)
      dut.io.exponent.poke(0.U)
      dut.io.pairReady.poke(true.B)
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

      dut.io.loadStart.poke(true.B)
      dut.clock.step()
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(true.B)
      for (beat <- 0 until config.polynomialBeats) {
        for (component <- 0 until config.components) {
          for (lane <- 0 until config.inverseLanes) {
            dut.io.load(component)(lane).poke(
              inputs(component)(beat * config.inverseLanes + lane).U
            )
          }
        }
        dut.clock.step()
      }
      dut.io.loadValid.poke(false.B)
      dut.clock.step()

      val acceptedCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
      val outputCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
      var nextCommand = 0
      var outputTransaction = 0
      var cycle = 0
      while (
        outputTransaction < exponents.size * transactions && cycle < 256
      ) {
        if (
          nextCommand < exponents.size &&
          dut.io.rotateReady.peek().litToBoolean
        ) {
          dut.io.exponent.poke(exponents(nextCommand).U)
          dut.io.rotateStart.poke(true.B)
          acceptedCycles += cycle
          nextCommand += 1
        } else {
          dut.io.rotateStart.poke(false.B)
        }

        if (dut.io.pairValid.peek().litToBoolean) {
          val command = outputTransaction / transactions
          val transaction = outputTransaction % transactions
          val row = transaction / config.forwardBeats
          val beat = transaction % config.forwardBeats
          val component = row / config.levels
          val level = row % config.levels
          dut.io.rowIndex.expect(row.U)
          for (lane <- 0 until config.forwardLanes) {
            val low = beat * config.forwardLanes + lane
            val high = low + config.points
            dut.io.coefficientLow(lane).expect(
              expected(component, level, low, exponents(command)).S
            )
            dut.io.coefficientHigh(lane).expect(
              expected(component, level, high, exponents(command)).S
            )
          }
          outputCycles += cycle
          outputTransaction += 1
        }

        dut.clock.step()
        cycle += 1
      }

      nextCommand should be(exponents.size)
      outputTransaction should be(exponents.size * transactions)
      acceptedCycles.sliding(2).foreach { pair =>
        pair(1) - pair(0) should be(chunks)
      }
      outputCycles.sliding(2).foreach { pair =>
        pair(1) - pair(0) should be(1)
      }
    }
  }
}

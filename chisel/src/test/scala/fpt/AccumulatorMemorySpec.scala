package fpt

import chisel3._
import chiseltest._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class AccumulatorMemorySpec
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
  private val contexts = 3
  private val config = ReplicatedAccumulatorBanksConfig(
    coefficient,
    contexts
  )
  private val torusMask = (BigInt(1) << coefficient.torusWidth) - 1

  private def initial(context: Int, component: Int, index: Int): BigInt =
    (BigInt("9e3779b9", 16) * (context + 1) +
      BigInt("1020304", 16) * component +
      BigInt("10001", 16) * index) & torusMask

  private def update(
      high: Boolean,
      component: Int,
      point: Int
  ): BigInt = {
    val magnitude = 1 + component * 23 + point
    if (high) -magnitude else magnitude
  }

  private def expectedAfterUpdate(
      context: Int,
      component: Int,
      index: Int
  ): BigInt = {
    val high = index >= coefficient.points
    val point = index & (coefficient.points - 1)
    val delta = update(high, component, point) << coefficient.torusShift
    (initial(context, component, index) + delta) & torusMask
  }

  private def clearInputs(dut: ReplicatedAccumulatorBanks): Unit = {
    dut.io.loadStart.poke(false.B)
    dut.io.loadContext.poke(0.U)
    dut.io.loadValid.poke(false.B)
    dut.io.prefetchStart.poke(false.B)
    dut.io.prefetchContext.poke(0.U)
    dut.io.drainStart.poke(false.B)
    dut.io.drainContext.poke(0.U)
    dut.io.drainReady.poke(false.B)
    dut.io.updateValid.poke(false.B)
    dut.io.updateFirst.poke(false.B)
    dut.io.updateContext.poke(0.U)
    for (component <- 0 until coefficient.components) {
      for (lane <- 0 until coefficient.inverseLanes) {
        dut.io.load(component)(lane).poke(0.U)
        dut.io.updateLow(component)(lane).poke(0.S)
        dut.io.updateHigh(component)(lane).poke(0.S)
      }
    }
  }

  private def loadContext(
      dut: ReplicatedAccumulatorBanks,
      context: Int
  ): Unit = {
    dut.io.loadContext.poke(context.U)
    dut.io.loadStart.poke(true.B)
    dut.clock.step()
    dut.io.loadStart.poke(false.B)
    dut.io.loadValid.poke(true.B)
    for (beat <- 0 until config.loadBeats) {
      dut.io.loadReady.expect(true.B)
      val high = beat >= config.halfBeats
      val halfBeat = beat & (config.halfBeats - 1)
      for (component <- 0 until coefficient.components) {
        for (lane <- 0 until coefficient.inverseLanes) {
          val point = halfBeat * coefficient.inverseLanes + lane
          val index = point + (if (high) coefficient.points else 0)
          dut.io.load(component)(lane).poke(
            initial(context, component, index).U
          )
        }
      }
      dut.clock.step()
    }
    dut.io.loadValid.poke(false.B)
    dut.io.loadDone.expect(true.B)
    dut.io.loadDoneContext.expect(context.U)
    dut.io.contextLoaded(context).expect(true.B)
    dut.clock.step()
  }

  private def expectPrefetchBeat(
      dut: ReplicatedAccumulatorBanks,
      context: Int,
      beat: Int,
      updated: Boolean
  ): Unit = {
    dut.io.prefetchValid.expect(true.B)
    dut.io.prefetchBeat.expect(beat.U)
    dut.io.prefetchOutputContext.expect(context.U)
    dut.io.prefetchDone.expect((beat == config.halfBeats - 1).B)
    for (component <- 0 until coefficient.components) {
      for (lane <- 0 until coefficient.inverseLanes) {
        val point = beat * coefficient.inverseLanes + lane
        val expectedLow = if (updated) {
          expectedAfterUpdate(context, component, point)
        } else {
          initial(context, component, point)
        }
        val highIndex = coefficient.points + point
        val expectedHigh = if (updated) {
          expectedAfterUpdate(context, component, highIndex)
        } else {
          initial(context, component, highIndex)
        }
        dut.io.prefetchLow(component)(lane).expect(expectedLow.U)
        dut.io.prefetchHigh(component)(lane).expect(expectedHigh.U)
      }
    }
  }

  behavior of "the replicated accumulator memory banks"

  it should "prefetch one context while continuously updating another" in {
    config.logicalBits should be(BigInt(6144))
    config.replicatedBits should be(BigInt(12288))

    test(new ReplicatedAccumulatorBanks(config)) { dut =>
      clearInputs(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      for (context <- 0 until contexts) {
        loadContext(dut, context)
      }

      // Context zero occupies the forward read copy while context one uses
      // the independent update read copy. Both streams issue one beat/cycle.
      dut.io.prefetchReady.expect(true.B)
      dut.io.prefetchContext.poke(0.U)
      dut.io.prefetchStart.poke(true.B)
      dut.clock.step()
      dut.io.prefetchStart.poke(false.B)
      // The request register is followed by the BRAM read and its dedicated
      // output register before the first response beat becomes visible.
      dut.clock.step()
      dut.io.updateContext.poke(1.U)
      dut.io.updateValid.poke(true.B)
      for (beat <- 0 until config.halfBeats) {
        dut.io.updateFirst.poke((beat == 0).B)
        dut.io.updateReady.expect(true.B)
        for (component <- 0 until coefficient.components) {
          for (lane <- 0 until coefficient.inverseLanes) {
            val point = beat * coefficient.inverseLanes + lane
            dut.io.updateLow(component)(lane).poke(
              update(high = false, component, point).S
            )
            dut.io.updateHigh(component)(lane).poke(
              update(high = true, component, point).S
            )
          }
        }
        dut.clock.step()
        expectPrefetchBeat(dut, context = 0, beat, updated = false)
      }
      dut.io.prefetchStart.poke(false.B)
      dut.io.updateValid.poke(false.B)
      dut.io.updateFirst.poke(false.B)
      dut.io.updateDone.expect(false.B)
      dut.clock.step()
      dut.io.updateDone.expect(true.B)
      dut.io.updateDoneContext.expect(1.U)

      // Commit the final mirrored write before reading the updated context.
      dut.clock.step()
      dut.io.updateDone.expect(false.B)
      dut.io.prefetchReady.expect(true.B)
      dut.io.prefetchContext.poke(1.U)
      dut.io.prefetchStart.poke(true.B)
      dut.clock.step()
      dut.io.prefetchStart.poke(false.B)
      dut.clock.step()
      for (beat <- 0 until config.halfBeats) {
        dut.clock.step()
        expectPrefetchBeat(dut, context = 1, beat, updated = true)
      }
      dut.clock.step()
      dut.io.prefetchValid.expect(false.B)

      // An untouched third context proves that mirrored updates did not
      // alias neighboring context addresses.
      dut.io.prefetchContext.poke(2.U)
      dut.io.prefetchStart.poke(true.B)
      dut.clock.step()
      dut.io.prefetchStart.poke(false.B)
      dut.clock.step()
      for (beat <- 0 until config.halfBeats) {
        dut.clock.step()
        expectPrefetchBeat(dut, context = 2, beat, updated = false)
      }
    }
  }

  it should "pause and resume a shared-port update around a prefetch" in {
    test(new ReplicatedAccumulatorBanks(config, replicateReads = false)) { dut =>
      clearInputs(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      loadContext(dut, context = 0)
      loadContext(dut, context = 1)

      def driveUpdateBeat(beat: Int): Unit = {
        dut.io.updateContext.poke(1.U)
        dut.io.updateFirst.poke((beat == 0).B)
        for (component <- 0 until coefficient.components) {
          for (lane <- 0 until coefficient.inverseLanes) {
            val point = beat * coefficient.inverseLanes + lane
            dut.io.updateLow(component)(lane).poke(
              update(high = false, component, point).S
            )
            dut.io.updateHigh(component)(lane).poke(
              update(high = true, component, point).S
            )
          }
        }
      }

      dut.io.updateValid.poke(true.B)
      for (beat <- 0 until 2) {
        driveUpdateBeat(beat)
        dut.io.updateReady.expect(true.B)
        dut.clock.step()
      }

      // The prefetch request and update beat two are accepted together before
      // the registered prefetch owns the read port. Beat three then remains
      // stable until the atomic prefetch releases that port.
      driveUpdateBeat(2)
      dut.io.prefetchContext.poke(0.U)
      dut.io.prefetchStart.poke(true.B)
      dut.io.prefetchReady.expect(true.B)
      dut.io.updateReady.expect(true.B)
      dut.clock.step()
      dut.io.prefetchStart.poke(false.B)
      driveUpdateBeat(3)
      dut.io.updateReady.expect(false.B)

      var responseBeat = 0
      var waitCycles = 0
      while (!dut.io.updateReady.peek().litToBoolean) {
        if (dut.io.prefetchValid.peek().litToBoolean) {
          expectPrefetchBeat(dut, context = 0, responseBeat, updated = false)
          responseBeat += 1
        }
        dut.clock.step()
        waitCycles += 1
        waitCycles should be < 16
      }
      responseBeat should be > 0

      for (beat <- 3 until config.halfBeats) {
        driveUpdateBeat(beat)
        dut.io.updateReady.expect(true.B)
        if (dut.io.prefetchValid.peek().litToBoolean) {
          expectPrefetchBeat(dut, context = 0, responseBeat, updated = false)
          responseBeat += 1
        }
        dut.clock.step()
      }
      dut.io.updateValid.poke(false.B)
      dut.io.updateFirst.poke(false.B)
      while (responseBeat < config.halfBeats) {
        if (dut.io.prefetchValid.peek().litToBoolean) {
          expectPrefetchBeat(dut, context = 0, responseBeat, updated = false)
          responseBeat += 1
        }
        dut.clock.step()
      }

      var done = dut.io.updateDone.peek().litToBoolean
      var doneWait = 0
      while (!done) {
        dut.clock.step()
        done = dut.io.updateDone.peek().litToBoolean
        doneWait += 1
        doneWait should be < 8
      }
      dut.io.updateDoneContext.expect(1.U)

      dut.clock.step()
      dut.io.prefetchContext.poke(1.U)
      dut.io.prefetchStart.poke(true.B)
      dut.io.prefetchReady.expect(true.B)
      dut.clock.step()
      dut.io.prefetchStart.poke(false.B)
      dut.clock.step(2)
      for (beat <- 0 until config.halfBeats) {
        expectPrefetchBeat(dut, context = 1, beat, updated = true)
        dut.clock.step()
      }
    }
  }

  it should "drain the full-width accumulator without using coefficient scratch" in {
    test(new ReplicatedAccumulatorBanks(config, replicateReads = false)) { dut =>
      clearInputs(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      loadContext(dut, context = 1)

      dut.io.drainContext.poke(1.U)
      dut.io.drainStartReady.expect(true.B)
      dut.io.drainStart.poke(true.B)
      dut.clock.step()
      dut.io.drainStart.poke(false.B)

      var beat = 0
      var cycles = 0
      while (beat < config.loadBeats) {
        val ready = cycles % 3 != 1
        dut.io.drainReady.poke(ready.B)
        if (dut.io.drainValid.peek().litToBoolean) {
          val high = beat >= config.halfBeats
          val halfBeat = beat & (config.halfBeats - 1)
          for (component <- 0 until coefficient.components) {
            for (lane <- 0 until coefficient.inverseLanes) {
              val point = halfBeat * coefficient.inverseLanes + lane
              val index = point + (if (high) coefficient.points else 0)
              dut.io.drain(component)(lane).expect(
                initial(1, component, index).U
              )
            }
          }
          if (ready) beat += 1
        }
        dut.clock.step()
        cycles += 1
        cycles should be < 100
      }
      dut.io.drainDone.expect(true.B)
      dut.io.drainDoneContext.expect(1.U)
    }
  }

  it should "describe the Set-II accumulator capacity explicitly" in {
    val paper = ReplicatedAccumulatorBanksConfig(
      PaperSetII.coefficient,
      batchContexts = 12
    )
    paper.wordWidth should be(128)
    paper.addressDepth should be(96)
    paper.logicalBits should be(BigInt(786432))
    paper.replicatedBits should be(BigInt(1572864))

    val systemVerilog = ChiselStage.emitSystemVerilog(
      new ReplicatedAccumulatorBanks(paper),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info"
      )
    )
    val bankInstances = "(?m)^  mem_96x128 ".r
      .findAllMatchIn(systemVerilog)
      .length
    bankInstances should be(2 * paper.coefficient.inverseLanes)
    systemVerilog should include("reg [127:0] Memory[0:95]")
  }
}

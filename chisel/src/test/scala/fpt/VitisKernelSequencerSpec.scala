package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class VitisKernelSequencerSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val base = PaperU280BufferedBarrelConfig(
    "forward.v",
    "inverse.v",
    PaperSetII.blindRotateDomainDimension
  )
  private val config = base.copy(blindRotate = base.blindRotate.copy(sampleExtractLanes = 1))

  private def initialize(dut: FptBlindRotateKernelSequencer): Unit = {
    dut.io.start.poke(false.B)
    dut.io.inputPointer.poke(0.U)
    dut.io.keyLowPointer.poke(0.U)
    dut.io.keyHighPointer.poke(0.U)
    dut.io.keyLow1Pointer.poke(0.U); dut.io.keyHigh1Pointer.poke(0.U)
    dut.io.outputPointer.poke(0.U)
    dut.io.inputCommand.ready.poke(true.B)
    dut.io.keyLowCommand.ready.poke(true.B)
    dut.io.keyHighCommand.ready.poke(true.B)
    dut.io.keyLow1Command.ready.poke(true.B); dut.io.keyHigh1Command.ready.poke(true.B)
    dut.io.outputCommand.ready.poke(true.B)
    dut.io.inputData.valid.poke(false.B)
    dut.io.inputData.bits.poke(0.U)
    dut.io.keyLowData.valid.poke(false.B)
    dut.io.keyLowData.bits.poke(0.U)
    dut.io.keyHighData.valid.poke(false.B)
    dut.io.keyHighData.bits.poke(0.U)
    dut.io.keyLow1Data.valid.poke(false.B); dut.io.keyLow1Data.bits.poke(0.U)
    dut.io.keyHigh1Data.valid.poke(false.B); dut.io.keyHigh1Data.bits.poke(0.U)
    dut.io.outputData.ready.poke(true.B)
    dut.io.inputStatus.valid.poke(false.B)
    dut.io.inputStatus.bits.poke("h80".U)
    dut.io.keyLowStatus.valid.poke(false.B)
    dut.io.keyLowStatus.bits.poke("h80".U)
    dut.io.keyHighStatus.valid.poke(false.B)
    dut.io.keyHighStatus.bits.poke("h80".U)
    dut.io.keyLow1Status.valid.poke(false.B); dut.io.keyLow1Status.bits.poke(128.U)
    dut.io.keyHigh1Status.valid.poke(false.B); dut.io.keyHigh1Status.bits.poke(128.U)
    dut.io.outputStatus.valid.poke(false.B)
    dut.io.outputStatus.bits.poke("h80".U)
    dut.io.inputError.poke(false.B)
    dut.io.keyLowError.poke(false.B)
    dut.io.keyHighError.poke(false.B)
    dut.io.keyLow1Error.poke(false.B); dut.io.keyHigh1Error.poke(false.B)
    dut.io.outputError.poke(false.B)
    dut.io.coreInputStartReady.poke(true.B)
    dut.io.coreInputReady.poke(true.B)
    dut.io.coreInputDone.poke(false.B)
    dut.io.coreInputDoneContext.poke(0.U)
    dut.io.coreKeyLoadStartReady.poke(true.B)
    dut.io.coreKeyLoadReady.poke(true.B)
    dut.io.coreKeyLoadDone.poke(false.B)
    dut.io.coreKeyLoadDoneIndex.poke(0.U)
    dut.io.coreRunReady.poke(false.B)
    dut.io.coreDone.poke(false.B)
    dut.io.coreResultValid.poke(false.B)
    dut.io.coreResult.poke(0.U)
    dut.io.coreResultCount.poke(1.U)
    dut.io.coreResultLast.poke(false.B)
  }

  private def launch(dut: FptBlindRotateKernelSequencer): Unit = {
    dut.io.start.poke(true.B)
    var waited = 0
    while (!dut.io.ready.peek().litToBoolean && waited < SGenFrameRecovery.configuredCycles) {
      dut.io.inputCommand.valid.expect(false.B)
      dut.io.keyLowCommand.valid.expect(false.B)
      dut.io.outputCommand.valid.expect(false.B)
      dut.clock.step()
      waited += 1
    }
    dut.io.ready.expect(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    dut.io.ready.expect(false.B)
  }

  behavior of "the fixed-batch Vitis kernel sequencer"

  it should "launch the next buffered execution before old output status without changing its DMA pointer" in {
    val buffered = config.copy(blindRotate = config.blindRotate.copy(
      sampleExtractLanes = 4, preprocessedInput = true, bufferedOutput = true))
    test(new FptBlindRotateKernelSequencer(buffered, strictCompletion = true))
        .withAnnotations(Seq(PhysicalRegisterModel.annotation)) { d =>
      initialize(d); d.clock.setTimeout(0)
      d.io.launchId.get.poke(false.B); d.io.coreCaptured.get.poke(false.B)
      d.io.chainFailure.get.valid.poke(false.B); d.io.chainFailure.get.bits.poke(0.U)
      d.io.preparedBank.get.poke(false.B); d.io.preparationCycles.get.poke(10165.U)
      d.io.preparedActivate.get.ready.poke(true.B)
      d.io.preparedInitialized.get.poke(false.B); d.io.coreComputeDone.get.poke(false.B)
      d.io.outputPointer.poke(4096.U); d.io.outputCommand.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      launch(d)
      d.io.coreRunReady.poke(true.B)
      d.io.preparedInitialized.get.poke(true.B); d.clock.step(); d.io.preparedInitialized.get.poke(false.B)
      // Mock completed core key loads; no old key transaction may survive release.
      for (i <- 0 until 630) {
        d.clock.step()
        d.io.coreKeyLoadDoneIndex.poke(i.U); d.io.coreKeyLoadDone.poke(true.B)
        d.clock.step(); d.io.coreKeyLoadDone.poke(false.B)
      }
      d.io.coreComputeDone.get.poke(true.B); d.clock.step(); d.io.coreComputeDone.get.poke(false.B)
      d.io.coreCaptured.get.poke(true.B); d.clock.step(); d.io.coreCaptured.get.poke(false.B)
      d.io.executionReleased.get.expect(false.B)
      val statuses = Seq(d.io.keyLowStatus, d.io.keyHighStatus, d.io.keyLow1Status, d.io.keyHigh1Status)
      statuses.take(3).foreach(_.valid.poke(true.B)); d.clock.step()
      statuses.foreach(_.valid.poke(false.B)); d.io.executionReleased.get.expect(false.B)
      statuses.last.valid.poke(true.B); d.clock.step(); statuses.last.valid.poke(false.B)
      d.io.executionReleased.get.expect(true.B); d.io.done.expect(false.B); d.clock.step()
      d.io.launchId.get.poke(true.B); d.io.outputPointer.poke(8192.U)
      d.io.preparedBank.get.poke(true.B); launch(d)
      d.io.outputCommand.valid.expect(true.B)
      ((d.io.outputCommand.bits.peek().litValue >> 32) & ((BigInt(1) << 64) - 1)) should be(BigInt(4096))
      d.io.outputCommand.ready.poke(true.B); d.clock.step()
      d.io.coreResultValid.poke(true.B); d.io.coreResultCount.poke(4.U)
      d.io.coreResult.poke(123.U); d.io.coreResultLast.poke(true.B)
      d.io.coreResultReady.expect(true.B); d.clock.step(); d.io.coreResultValid.poke(false.B)
      d.clock.step(5); d.io.done.expect(false.B); d.io.coreRetire.get.expect(false.B)
      d.io.outputStatus.valid.poke(true.B); d.clock.step(); d.io.outputStatus.valid.poke(false.B)
      d.clock.step()
      d.io.done.expect(true.B); d.io.completionId.get.expect(false.B)
      d.io.coreRetire.get.expect(true.B); d.io.stageCycles.get(0).expect(10165.U)
      d.clock.step(); d.io.idle.expect(false.B) // Execution 2 remains active.
      d.io.chainFailure.get.valid.poke(true.B); d.io.chainFailure.get.bits.poke(4083.U)
      d.clock.step(); d.io.chainFailure.get.valid.poke(false.B)
      d.io.done.expect(true.B); d.io.completionId.get.expect(true.B)
      d.io.status.expect(4083.U); d.io.coreRetire.get.expect(false.B)
      d.clock.step(); d.io.start.poke(true.B); d.io.ready.expect(false.B)
    }
  }

  it should "activate prepared input without raw replay and measure initialization separately" in {
    val prepared = config.copy(blindRotate = config.blindRotate.copy(preprocessedInput = true))
    test(new FptBlindRotateKernelSequencer(prepared, strictCompletion = true)) { dut =>
      initialize(dut)
      dut.io.preparedBank.get.poke(true.B)
      dut.io.preparationCycles.get.poke(10120.U)
      dut.io.preparedActivate.get.ready.poke(false.B)
      dut.io.preparedInitialized.get.poke(false.B)
      dut.io.coreComputeDone.get.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      launch(dut)
      dut.io.preparedBank.get.poke(false.B)
      dut.io.preparationCycles.get.poke(0.U)
      dut.io.preparedActivate.get.valid.expect(true.B)
      dut.io.preparedActivate.get.bits.expect(true.B)
      dut.io.inputCommand.valid.expect(false.B)
      dut.io.coreInputStart.expect(false.B); dut.io.coreInputValid.expect(false.B)
      dut.io.coreRunReady.poke(true.B)
      dut.io.coreKeyLoadDone.poke(true.B); dut.io.coreKeyLoadDoneIndex.poke(0.U)
      dut.clock.step(); dut.io.coreKeyLoadDone.poke(false.B)
      dut.clock.step(7); dut.io.coreRunStart.expect(false.B)
      dut.io.preparedActivate.get.ready.poke(true.B); dut.clock.step()
      dut.io.preparedActivate.get.ready.poke(false.B)
      dut.io.preparedActivate.get.valid.expect(false.B)
      dut.io.preparedInitialized.get.poke(true.B); dut.clock.step()
      dut.io.preparedInitialized.get.poke(false.B)
      dut.io.coreRunStart.expect(true.B); dut.clock.step()
      dut.io.stageCycles.get(0).expect(10120.U)
      dut.io.stageCycles.get(1).peek().litValue should be < BigInt(32)
      dut.clock.step(10)
      dut.io.coreComputeDone.get.poke(true.B); dut.clock.step()
      dut.io.coreComputeDone.get.poke(false.B)
      dut.clock.step(5)
      dut.io.coreDone.poke(true.B); dut.clock.step(); dut.io.coreDone.poke(false.B)
      Seq(dut.io.keyLowStatus, dut.io.keyHighStatus, dut.io.keyLow1Status,
        dut.io.keyHigh1Status).foreach(_.valid.poke(true.B))
      dut.clock.step()
      Seq(dut.io.keyLowStatus, dut.io.keyHighStatus, dut.io.keyLow1Status,
        dut.io.keyHigh1Status).foreach(_.valid.poke(false.B))
      dut.clock.step(7); dut.io.done.expect(false.B)
      dut.io.outputStatus.valid.poke(true.B); dut.clock.step()
      dut.io.outputStatus.valid.poke(false.B); dut.clock.step(); dut.io.done.expect(true.B)
      dut.io.stageCycles.get(2).peek().litValue should be > BigInt(0)
      dut.io.stageCycles.get(3).peek().litValue should be > BigInt(0)
      dut.io.stageCycles.get(4).peek().litValue should be > BigInt(0)
    }
  }

  it should "require actual output DMA status rather than a timeout in chained mode" in {
    val scalar = config.copy(blindRotate = config.blindRotate.copy(sampleExtractLanes = 1))
    test(new FptBlindRotateKernelSequencer(scalar, strictCompletion = true)) { dut =>
      initialize(dut)
      dut.io.keyLow1Status.valid.poke(false.B); dut.io.keyLow1Status.bits.poke(128.U)
      dut.io.keyHigh1Status.valid.poke(false.B); dut.io.keyHigh1Status.bits.poke(128.U)
      dut.io.keyLow1Error.poke(false.B); dut.io.keyHigh1Error.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      launch(dut)
      dut.io.coreDone.poke(true.B); dut.clock.step(); dut.io.coreDone.poke(false.B)
      Seq(dut.io.inputStatus, dut.io.keyLowStatus, dut.io.keyHighStatus,
        dut.io.keyLow1Status, dut.io.keyHigh1Status).foreach(_.valid.poke(true.B))
      dut.clock.step()
      Seq(dut.io.inputStatus, dut.io.keyLowStatus, dut.io.keyHighStatus,
        dut.io.keyLow1Status, dut.io.keyHigh1Status).foreach(_.valid.poke(false.B))
      dut.clock.step(400); dut.io.done.expect(false.B); dut.io.idle.expect(false.B)
      dut.io.outputStatus.valid.poke(true.B); dut.clock.step(); dut.io.outputStatus.valid.poke(false.B)
      dut.clock.step(); dut.io.done.expect(true.B)
    }
  }

  it should "use the current 128-by-64 transform and four-stream key profile" in {
    config.blindRotate.cmux.engine.coefficient.forwardLanes should be(128)
    config.blindRotate.cmux.engine.coefficient.inverseLanes should be(64)
    config.blindRotate.cmux.engine.forwardTransform.lanes should be(128)
    config.blindRotate.cmux.engine.inverseTransform.lanes should be(64)
    config.blindRotate.cmux.engine.externalProduct.inputLanes should be(128)
    config.blindRotate.cmux.engine.externalProduct.outputLanes should be(64)
    config.blindRotate.batchContexts should be(16)
  }

  it should "issue exact DataMover addresses and one BTT command per key stream" in {
    test(new FptBlindRotateKernelSequencer(config)) { dut =>
      initialize(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.inputPointer.poke("h100000000".U)
      dut.io.keyLowPointer.poke("h200000000".U)
      dut.io.keyHighPointer.poke("h300000000".U)
      dut.io.keyLow1Pointer.poke("h500000000".U)
      dut.io.keyHigh1Pointer.poke("h600000000".U)
      dut.io.outputPointer.poke("h400000000".U)
      launch(dut)

      def address(command: BigInt): BigInt = (command >> 32) & ((BigInt(1) << 64) - 1)
      def btt(command: BigInt): BigInt = command & ((BigInt(1) << 23) - 1)

      dut.io.inputCommand.valid.expect(true.B)
      address(dut.io.inputCommand.bits.peek().litValue) should be(BigInt("100000000", 16))
      btt(dut.io.inputCommand.bits.peek().litValue) should be(40448)
      dut.io.outputCommand.valid.expect(true.B)
      address(dut.io.outputCommand.bits.peek().litValue) should be(BigInt("400000000", 16))
      btt(dut.io.outputCommand.bits.peek().litValue) should be(65600)
      dut.io.keyLowCommand.valid.expect(true.B)
      address(dut.io.keyLowCommand.bits.peek().litValue) should be(BigInt("200000000", 16))
      btt(dut.io.keyLowCommand.bits.peek().litValue) should be(5160960)
      dut.io.keyHighCommand.valid.expect(true.B)
      address(dut.io.keyHighCommand.bits.peek().litValue) should be(BigInt("300000000", 16))
      btt(dut.io.keyHighCommand.bits.peek().litValue) should be(5160960)
      dut.io.keyLow1Command.valid.expect(true.B); dut.io.keyHigh1Command.valid.expect(true.B)
      address(dut.io.keyLow1Command.bits.peek().litValue) should be(BigInt("500000000", 16))
      address(dut.io.keyHigh1Command.bits.peek().litValue) should be(BigInt("600000000", 16))
      btt(dut.io.keyLow1Command.bits.peek().litValue) should be(5160960)
      btt(dut.io.keyHigh1Command.bits.peek().litValue) should be(5160960)

      dut.clock.step()
      Seq(dut.io.keyLowCommand, dut.io.keyHighCommand, dut.io.keyLow1Command,
        dut.io.keyHigh1Command).foreach(_.valid.expect(false.B))
    }
  }

  it should "serialize across a 632-word context boundary" in {
    test(new FptBlindRotateKernelSequencer(config)) { dut =>
      initialize(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      launch(dut)

      var nextWord = 0
      var starts = Vector.empty[(Int, BigInt)]
      var cycles = 0
      while (starts.size < 2 && cycles < 700) {
        if (dut.io.inputData.ready.peek().litToBoolean) {
          val packed = (0 until 16).foldLeft(BigInt(0)) { (word, lane) =>
            word | (BigInt(nextWord + lane) << (32 * lane))
          }
          dut.io.inputData.valid.poke(true.B)
          dut.io.inputData.bits.poke(packed.U)
          nextWord += 16
        } else {
          dut.io.inputData.valid.poke(false.B)
        }
        if (dut.io.coreInputStart.peek().litToBoolean) {
          starts :+= (
            dut.io.coreInputContext.peek().litValue.toInt,
            dut.io.coreTestVector.peek().litValue
          )
        }
        dut.clock.step()
        cycles += 1
      }
      starts should be(Vector((0, BigInt(0)), (1, BigInt(632))))
    }
  }

  it should "pair independently stalled key halves at one beat per cycle" in {
    test(new FptBlindRotateKernelSequencer(config)) { dut =>
      initialize(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      launch(dut)
      dut.clock.step() // accept key-load start

      def packedLow(sequence: Int): BigInt = {
        val real0 = BigInt(sequence & ((1 << 27) - 1))
        val imag0 = BigInt((sequence + 7) & ((1 << 27) - 1))
        real0 | (imag0 << 27)
      }

      dut.io.keyLowData.valid.poke(true.B)
      dut.io.keyLowData.bits.poke(packedLow(3).U)
      dut.io.keyHighData.valid.poke(false.B)
      dut.io.keyLow1Data.valid.poke(true.B); dut.io.keyHigh1Data.valid.poke(true.B)
      dut.clock.step()
      dut.io.coreKeyLoadValid.expect(false.B)

      var accepted = 0
      while (accepted < 20) {
        dut.io.keyLowData.valid.poke(true.B)
        dut.io.keyHighData.valid.poke(true.B)
        // Beat zero is already resident; refill with the following beat while
        // the current pair is consumed.
        dut.io.keyLowData.bits.poke(packedLow(accepted + 4).U)
        dut.io.keyHighData.bits.poke(0.U)
        if (dut.io.coreKeyLoadValid.peek().litToBoolean) {
          dut.io.coreKeyLoad(0).real.expect((accepted + 3).S)
          dut.io.coreKeyLoad(0).imag.expect((accepted + 10).S)
          accepted += 1
        }
        dut.clock.step()
      }
      accepted should be(20)

      // A paired beat may already be resident when the key memory inserts
      // its gap between coefficient loads.  Keep it buffered, but do not
      // violate the pulse-valid contract while the memory is unavailable.
      dut.io.coreKeyLoadReady.poke(false.B)
      dut.io.coreKeyLoadValid.expect(false.B)
      dut.io.keyLowData.ready.expect(false.B)
      dut.io.keyHighData.ready.expect(false.B)
      dut.clock.step(3)
      dut.io.coreKeyLoadValid.expect(false.B)
      dut.io.coreKeyLoadReady.poke(true.B)
      dut.io.coreKeyLoadValid.expect(true.B)
    }
  }

  it should "wait for the core and every DMA status before normal completion" in {
    test(new FptBlindRotateKernelSequencer(config)) { dut =>
      initialize(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      launch(dut)

      dut.io.inputStatus.valid.poke(true.B)
      dut.io.outputStatus.valid.poke(true.B)
      dut.io.keyLowStatus.valid.poke(true.B)
      dut.io.keyHighStatus.valid.poke(true.B)
      dut.io.coreDone.poke(true.B)
      dut.io.keyLow1Status.valid.poke(true.B); dut.io.keyHigh1Status.valid.poke(true.B)
      dut.clock.step()
      dut.io.done.expect(false.B)

      dut.io.inputStatus.valid.poke(false.B)
      dut.io.outputStatus.valid.poke(false.B)
      dut.io.coreDone.poke(false.B)
      dut.io.keyLowStatus.valid.poke(false.B)
      dut.io.keyHighStatus.valid.poke(false.B)
      dut.io.keyLow1Status.valid.poke(false.B); dut.io.keyHigh1Status.valid.poke(false.B)
      dut.clock.step()
      dut.io.done.expect(true.B)
      dut.io.ready.expect(true.B)
      dut.io.idle.expect(true.B)
      (dut.io.status.peek().litValue & 0x1fff) should be(0)
      dut.clock.step()
      dut.io.done.expect(false.B)
    }
  }

  it should "complete after a bounded output drain when statuses are absent" in {
    test(new FptBlindRotateKernelSequencer(config)) { dut =>
      initialize(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      launch(dut)

      dut.io.coreDone.poke(true.B)
      dut.clock.step()
      dut.io.coreDone.poke(false.B)
      dut.io.done.expect(false.B)
      for (_ <- 0 until 254) {
        dut.clock.step()
        dut.io.done.expect(false.B)
      }
      dut.clock.step()
      dut.io.done.expect(true.B)
      dut.io.idle.expect(true.B)
    }
  }

  it should "complete with a sticky channel and status on a DMA error" in {
    test(new FptBlindRotateKernelSequencer(config)) { dut =>
      initialize(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      launch(dut)
      dut.io.keyLowStatus.valid.poke(true.B)
      dut.io.keyLowStatus.bits.poke("h12".U)
      dut.clock.step()
      dut.io.keyLowStatus.valid.poke(false.B)
      dut.clock.step()
      dut.io.done.expect(true.B)
      dut.io.idle.expect(true.B)
      (dut.io.status.peek().litValue & 0x1fff) should be(0x125)
      dut.clock.step()
      dut.io.done.expect(false.B)
      (dut.io.status.peek().litValue & 0x1fff) should be(0x125)
    }
  }
}

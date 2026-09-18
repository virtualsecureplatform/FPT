package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class WideKernelOutputSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  it should "wait for the last compacted output handshake before starting completion drain" in {
    val base = PaperU280BufferedBarrelConfig("forward.v", "inverse.v", 630)
    val config = base.copy(blindRotate = base.blindRotate.copy(sampleExtractLanes = 4))
    test(new FptBlindRotateKernelSequencer(config))
 { dut =>
        dut.io.start.poke(false.B)
        Seq(dut.io.inputPointer, dut.io.keyLowPointer, dut.io.keyHighPointer,
          dut.io.keyLow1Pointer, dut.io.keyHigh1Pointer, dut.io.outputPointer).foreach(_.poke(0.U))
        Seq(dut.io.inputCommand, dut.io.keyLowCommand, dut.io.keyHighCommand,
          dut.io.keyLow1Command, dut.io.keyHigh1Command, dut.io.outputCommand).foreach(_.ready.poke(true.B))
        Seq(dut.io.inputData, dut.io.keyLowData, dut.io.keyHighData,
          dut.io.keyLow1Data, dut.io.keyHigh1Data).foreach { port => port.valid.poke(false.B); port.bits.poke(0.U) }
        Seq(dut.io.inputStatus, dut.io.keyLowStatus, dut.io.keyHighStatus,
          dut.io.keyLow1Status, dut.io.keyHigh1Status, dut.io.outputStatus).foreach { port =>
          port.valid.poke(false.B); port.bits.poke("h80".U)
        }
        Seq(dut.io.inputError, dut.io.keyLowError, dut.io.keyHighError,
          dut.io.keyLow1Error, dut.io.keyHigh1Error, dut.io.outputError).foreach(_.poke(false.B))
        dut.io.coreInputStartReady.poke(false.B); dut.io.coreInputReady.poke(false.B)
        dut.io.coreInputDone.poke(false.B); dut.io.coreInputDoneContext.poke(0.U)
        dut.io.coreKeyLoadStartReady.poke(false.B); dut.io.coreKeyLoadReady.poke(false.B)
        dut.io.coreKeyLoadDone.poke(false.B); dut.io.coreKeyLoadDoneIndex.poke(0.U)
        dut.io.coreRunReady.poke(false.B); dut.io.coreDone.poke(false.B)
        dut.io.coreResultValid.poke(false.B); dut.io.coreResult.poke(0.U)
        dut.io.coreResultCount.poke(1.U); dut.io.coreResultLast.poke(false.B)
        dut.io.outputData.ready.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
        dut.clock.step(SGenFrameRecovery.configuredCycles + 1)
        for (_ <- 0 until 2) {
          dut.io.start.poke(true.B); dut.io.ready.expect(true.B); dut.clock.step(); dut.io.start.poke(false.B)
          dut.io.outputData.ready.poke(false.B)
          dut.io.coreResultValid.poke(true.B)
          dut.io.coreResultCount.poke(4.U)
          val first = BigInt(1) | (BigInt(2) << 32) | (BigInt(3) << 64) | (BigInt(4) << 96)
          dut.io.coreResult.poke(first.U); dut.io.coreResultLast.poke(false.B)
          dut.io.coreResultReady.expect(true.B); dut.clock.step()
          dut.io.coreResult.poke(5.U); dut.io.coreResultCount.poke(1.U)
          dut.io.coreResultLast.poke(true.B); dut.io.coreDone.poke(true.B)
          dut.io.coreResultReady.expect(true.B); dut.clock.step()
          dut.io.coreResultValid.poke(false.B); dut.io.coreDone.poke(false.B)
          for (_ <- 0 until 300) {
            dut.io.done.expect(false.B); dut.io.idle.expect(false.B)
            dut.io.outputData.valid.expect(true.B); dut.io.outputData.bits.expect(first.U)
            dut.io.outputKeep.expect("hffff".U); dut.io.outputLast.expect(false.B)
            dut.clock.step()
          }
          dut.io.outputData.ready.poke(true.B); dut.clock.step()
          dut.io.outputData.bits.expect(5.U); dut.io.outputKeep.expect(15.U)
          dut.io.outputLast.expect(true.B); dut.io.done.expect(false.B)
          dut.clock.step()
          dut.io.outputData.valid.expect(false.B)
          var wait = 0
          while (!dut.io.done.peek().litToBoolean && wait < 260) { dut.clock.step(); wait += 1 }
          wait should be >= 254
          wait should be <= 256
          dut.io.done.expect(true.B); dut.io.idle.expect(true.B)
          dut.clock.step()
        }
      }
  }
}

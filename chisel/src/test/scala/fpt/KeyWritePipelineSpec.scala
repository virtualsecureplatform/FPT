package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class KeyWritePipelineSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  for (loadLanes <- Seq(2, 4, 32)) {
    it should s"commit safely with $loadLanes lanes, bubbles, overlap, backpressure and reset" in {
      val inputLanes = math.max(8, loadLanes * 4)
      val external = ExternalProductConfig(points = inputLanes * 4,
        inputLanes = inputLanes, outputLanes = inputLanes / 2, rows = 2,
        outputComponents = 2, spectrum = FixedFormat(16, 11),
        bootstrappingKey = FixedFormat(16, 11), accumulator = FixedFormat(20, 11))
      val cfg = BootstrappingKeyBufferConfig(external, 2, 4, loadLanes, writeTileLanes = 4, minimalMetadataReset = sys.env.get("FPT_U280_MINIMAL_METADATA_RESET").contains("1"))
      test(new BootstrappingKeyPingPongBuffer(cfg))
        .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
        dut.io.loadStart.poke(false.B); dut.io.loadIndex.poke(0.U)
        dut.io.loadValid.poke(false.B); dut.io.readRequestValid.poke(false.B)
        dut.io.readIndex.poke(0.U); dut.io.readRow.poke(0.U); dut.io.readBeat.poke(0.U)
        dut.io.readResponseReady.poke(true.B)
        for (lane <- 0 until loadLanes) {
          dut.io.load(lane).real.poke(0.S); dut.io.load(lane).imag.poke(0.S)
        }
        def reset(): Unit = {
          dut.io.loadStart.poke(false.B); dut.io.loadValid.poke(false.B)
          dut.io.readRequestValid.poke(false.B)
          dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
          dut.io.bankValid.foreach(_.expect(false.B)); dut.io.loadDone.expect(false.B)
        }
        def start(index: Int): Unit = {
          dut.io.loadIndex.poke(index.U); dut.io.loadStartReady.expect(true.B)
          dut.io.loadStart.poke(true.B); dut.clock.step(); dut.io.loadStart.poke(false.B)
        }
        def value(index: Int, word: Int, scalar: Int): Int = 1 + index * 10000 + word * 300 + scalar
        def beat(index: Int, n: Int): Unit = {
          for (lane <- 0 until loadLanes) {
            val v = value(index, n / cfg.loadGroupsPerRead, (n % cfg.loadGroupsPerRead) * loadLanes + lane)
            dut.io.load(lane).real.poke(v.S); dut.io.load(lane).imag.poke((-v).S)
          }
          dut.io.loadValid.poke(true.B); dut.io.loadReady.expect(true.B); dut.clock.step()
          dut.io.loadValid.poke(false.B)
        }
        def request(index: Int, word: Int): Unit = {
          dut.io.readIndex.poke(index.U); dut.io.readRow.poke((word / external.inputFrameBeats).U)
          dut.io.readBeat.poke((word % external.inputFrameBeats).U)
          dut.io.readRequestValid.poke(true.B)
        }
        def expectWord(index: Int, word: Int): Unit = {
          dut.io.readResponseValid.expect(true.B); dut.io.readResponseIndex.expect(index.U)
          for (c <- 0 until 2; lane <- 0 until inputLanes) {
            val v = value(index, word, lane * 2 + c)
            dut.io.readResponse(c)(lane).real.expect(v.S)
            dut.io.readResponse(c)(lane).imag.expect((-v).S)
          }
        }
        reset(); start(0)
        for (n <- 0 until cfg.loadBeatsPerCoefficient) {
          if (n % 5 == 2) dut.clock.step(2)
          beat(0, n)
        }
        // The last beat is accepted but not yet committed. Same-word read is blocked.
        dut.io.loadDone.expect(false.B); dut.io.bankValid.foreach(_.expect(false.B))
        request(0, cfg.wordsPerCoefficient - 1); dut.io.readRequestReady.expect(false.B)
        request(0, 0); dut.io.readRequestReady.expect(true.B)
        dut.clock.step(); dut.io.readRequestValid.poke(false.B)
        dut.io.loadDone.expect(true.B); dut.io.loadDoneIndex.expect(0.U); expectWord(0, 0)
        dut.io.readResponseReady.poke(false.B)
        for (_ <- 0 until 3) { expectWord(0, 0); dut.clock.step() }
        dut.io.readResponseReady.poke(true.B); dut.clock.step()
        start(1)
        // Stream the next key while draining the old bank at one read per clock.
        var reads = 1
        for (n <- 0 until cfg.loadBeatsPerCoefficient) {
          val readNow = reads < cfg.readsPerCoefficient
          if (readNow) { request(0, reads % cfg.wordsPerCoefficient); dut.io.readRequestReady.expect(true.B) }
          else dut.io.readRequestValid.poke(false.B)
          beat(1, n)
          if (readNow) { expectWord(0, reads % cfg.wordsPerCoefficient); reads += 1 }
        }
        dut.io.readRequestValid.poke(false.B)
        dut.io.loadDone.expect(false.B)
        // The retired bank can accept a new owner while the other final write commits.
        dut.io.loadIndex.poke(2.U); dut.io.loadStartReady.expect(true.B)
        dut.io.loadStart.poke(true.B); dut.clock.step(); dut.io.loadStart.poke(false.B)
        dut.io.loadDone.expect(true.B); dut.io.loadDoneIndex.expect(1.U)
        for (word <- 0 until cfg.wordsPerCoefficient) {
          request(1, word); dut.io.readRequestReady.expect(true.B)
          dut.clock.step(); expectWord(1, word)
        }
        dut.io.readRequestValid.poke(false.B)
        // Reset with an accepted, uncommitted write must not publish a bank.
        beat(2, 0); reset(); dut.clock.step(2)
        dut.io.bankValid.foreach(_.expect(false.B)); start(3)
        for (n <- 0 until cfg.loadBeatsPerCoefficient - 1) beat(3, n)
        // Accept the next load on the very same edge as the final incoming beat.
        dut.io.loadIndex.poke(2.U); dut.io.loadStart.poke(true.B)
        beat(3, cfg.loadBeatsPerCoefficient - 1); dut.io.loadStart.poke(false.B)
        dut.io.loadReady.expect(true.B)
        dut.io.loadDone.expect(false.B); dut.clock.step()
        dut.io.loadDone.expect(true.B); dut.io.loadDoneIndex.expect(3.U)
        request(3, cfg.wordsPerCoefficient - 1); dut.io.readRequestReady.expect(true.B)
        dut.clock.step(); dut.io.readRequestValid.poke(false.B)
        expectWord(3, cfg.wordsPerCoefficient - 1)
      }
    }
  }
}

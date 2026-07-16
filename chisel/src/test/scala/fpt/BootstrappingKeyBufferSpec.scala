package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer

final class BootstrappingKeyBufferSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val external = ExternalProductConfig(
    points = 8,
    inputLanes = 4,
    outputLanes = 2,
    rows = 2,
    outputComponents = 2,
    spectrum = FixedFormat(8, 8),
    bootstrappingKey = FixedFormat(8, 8),
    accumulator = FixedFormat(12, 8)
  )
  private val config = BootstrappingKeyBufferConfig(
    external,
    batchContexts = 4,
    domainDimension = 3,
    loadLanes = 2
  )

  private def value(index: Int, word: Int, scalar: Int): (Int, Int) = {
    val real = 1000 * index + 100 * word + scalar + 1
    (real, -real)
  }

  behavior of "the bootstrapping-key ping-pong buffer"

  it should "load the next coefficient while serving one full batch wave" in {
    test(new BootstrappingKeyPingPongBuffer(config)) { dut =>
      dut.io.loadStart.poke(false.B)
      dut.io.loadIndex.poke(0.U)
      dut.io.loadValid.poke(false.B)
      dut.io.readRequestValid.poke(false.B)
      dut.io.readIndex.poke(0.U)
      dut.io.readRow.poke(0.U)
      dut.io.readBeat.poke(0.U)
      dut.io.readResponseReady.poke(true.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      def startLoad(index: Int): Unit = {
        dut.io.loadIndex.poke(index.U)
        dut.io.loadStartReady.expect(true.B)
        dut.io.loadStart.poke(true.B)
        dut.clock.step()
        dut.io.loadStart.poke(false.B)
      }

      def pokeLoad(index: Int, operation: Int): Unit = {
        val word = operation / config.loadGroupsPerRead
        val group = operation % config.loadGroupsPerRead
        for (lane <- 0 until config.loadLanes) {
          val scalar = group * config.loadLanes + lane
          val (real, imag) = value(index, word, scalar)
          dut.io.load(lane).real.poke(real.S)
          dut.io.load(lane).imag.poke(imag.S)
        }
      }

      def loadCoefficient(index: Int): Unit = {
        startLoad(index)
        dut.io.loadValid.poke(true.B)
        for (operation <- 0 until config.loadBeatsPerCoefficient) {
          dut.io.loadReady.expect(true.B)
          pokeLoad(index, operation)
          dut.clock.step()
        }
        dut.io.loadValid.poke(false.B)
        dut.io.loadDone.expect(true.B)
        dut.io.loadDoneIndex.expect(index.U)
        dut.clock.step()
      }

      def expectResponse(index: Int, request: Int): Unit = {
        val word = request % config.wordsPerCoefficient
        dut.io.readResponseValid.expect(true.B)
        dut.io.readResponseIndex.expect(index.U)
        dut.io.readResponseRow.expect(
          (word / external.inputFrameBeats).U
        )
        dut.io.readResponseBeat.expect(
          (word % external.inputFrameBeats).U
        )
        for (lane <- 0 until external.inputLanes) {
          for (component <- 0 until external.outputComponents) {
            val scalar = lane * external.outputComponents + component
            val (real, imag) = value(index, word, scalar)
            dut.io.readResponse(component)(lane).real.expect(real.S)
            dut.io.readResponse(component)(lane).imag.expect(imag.S)
          }
        }
      }

      loadCoefficient(0)
      dut.io.bankValid(0).expect(true.B)
      dut.io.bankIndex(0).expect(0.U)

      startLoad(1)
      dut.io.loadValid.poke(true.B)
      dut.io.readRequestValid.poke(true.B)
      dut.io.readIndex.poke(0.U)
      var previousRequest = Option.empty[Int]
      for (operation <- 0 until config.loadBeatsPerCoefficient) {
        previousRequest.foreach(expectResponse(0, _))
        pokeLoad(1, operation)
        val word = operation % config.wordsPerCoefficient
        dut.io.readRow.poke((word / external.inputFrameBeats).U)
        dut.io.readBeat.poke((word % external.inputFrameBeats).U)
        dut.io.loadReady.expect(true.B)
        dut.io.readRequestReady.expect(true.B)
        dut.clock.step()
        previousRequest = Some(operation)
      }
      dut.io.loadValid.poke(false.B)
      dut.io.readRequestValid.poke(false.B)
      expectResponse(0, config.loadBeatsPerCoefficient - 1)
      dut.io.loadDone.expect(true.B)
      dut.io.loadDoneIndex.expect(1.U)

      // Consume the final response, start reusing its retired bank, and issue
      // the first request from the other bank on the same clock edge.
      dut.io.loadIndex.poke(2.U)
      dut.io.loadStartReady.expect(true.B)
      dut.io.loadStart.poke(true.B)
      dut.io.readIndex.poke(1.U)
      dut.io.readRow.poke(0.U)
      dut.io.readBeat.poke(0.U)
      dut.io.readRequestValid.poke(true.B)
      dut.io.readRequestReady.expect(true.B)
      dut.clock.step()
      dut.io.loadStart.poke(false.B)

      // The final key-2 load beat makes key 2 readable on the same edge. The
      // last key-1 response and first key-2 request therefore overlap without
      // inserting a coefficient-boundary bubble.
      dut.io.loadValid.poke(true.B)
      val observed = ArrayBuffer.empty[Int]
      for (operation <- 0 until config.loadBeatsPerCoefficient) {
        expectResponse(1, operation)
        observed += operation
        pokeLoad(2, operation)
        val nextIndex = if (
          operation == config.loadBeatsPerCoefficient - 1
        ) 2 else 1
        val nextRequest = if (nextIndex == 2) 0 else operation + 1
        val nextWord = nextRequest % config.wordsPerCoefficient
        dut.io.readIndex.poke(nextIndex.U)
        dut.io.readRow.poke((nextWord / external.inputFrameBeats).U)
        dut.io.readBeat.poke((nextWord % external.inputFrameBeats).U)
        dut.io.loadReady.expect(true.B)
        dut.io.readRequestReady.expect(true.B)
        dut.clock.step()
      }
      dut.io.loadValid.poke(false.B)
      dut.io.readRequestValid.poke(false.B)
      observed.toSeq should be(0 until config.loadBeatsPerCoefficient)
      dut.io.loadDone.expect(true.B)
      dut.io.loadDoneIndex.expect(2.U)

      dut.io.bankValid(0).expect(true.B)
      dut.io.bankIndex(0).expect(2.U)
      dut.io.bankValid(1).expect(false.B)

      dut.io.readResponseReady.poke(false.B)
      expectResponse(2, 0)
      val heldReal = dut.io.readResponse(0)(0).real.peek().litValue
      dut.clock.step(2)
      dut.io.readResponseValid.expect(true.B)
      dut.io.readResponse(0)(0).real.expect(heldReal.S)
      dut.io.readRequestValid.poke(true.B)
      dut.io.readRequestReady.expect(false.B)
      dut.io.readRequestValid.poke(false.B)
      dut.io.readResponseReady.poke(true.B)
      dut.clock.step()
      dut.io.readResponseValid.expect(false.B)
    }
  }
}

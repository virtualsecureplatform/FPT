package fpt

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private class KeyIngressPipelineHarness extends Module {
  val config = PaperU280BufferedBarrelConfig("forward.v", "inverse.v", PaperSetII.blindRotateDomainDimension)
    .copy(keyWriteTileLanes = 4)
  val sequencer = Module(new FptBlindRotateKernelSequencer(config))
  val key = Module(new BootstrappingKeyPingPongBuffer(config.keyBuffer))
  val io = IO(new Bundle {
    val start = Input(Bool())
    val ready = Output(Bool())
    val streams = Flipped(Vec(4, Decoupled(Vec(16, UInt(32.W)))))
    val accepted = Output(Bool())
    val loadDone = Output(Bool())
    val loadIndex = Output(UInt(config.keyBuffer.dimensionWidth.W))
    val readValid = Input(Bool())
    val readReady = Output(Bool())
    val readIndex = Input(UInt(config.keyBuffer.dimensionWidth.W))
    val readRow = Input(UInt(config.keyBuffer.rowWidth.W))
    val readBeat = Input(UInt(config.keyBuffer.beatWidth.W))
    val responseValid = Output(Bool())
    val response = Output(chiselTypeOf(key.io.readResponse))
  })
  def tieInputs(port: Data): Unit = port match {
    case record: Record => record.elements.values.foreach(tieInputs)
    case vector: Vec[_] => vector.foreach(tieInputs)
    case leaf if chisel3.reflect.DataMirror.directionOf(leaf) == ActualDirection.Input =>
      leaf := 0.U.asTypeOf(leaf)
    case _ =>
  }
  tieInputs(sequencer.io)
  sequencer.io.start := io.start
  io.ready := sequencer.io.ready
  for (command <- Seq(sequencer.io.inputCommand, sequencer.io.outputCommand,
      sequencer.io.keyLowCommand, sequencer.io.keyHighCommand,
      sequencer.io.keyLow1Command, sequencer.io.keyHigh1Command)) command.ready := true.B
  for ((stream, port) <- io.streams.zip(Seq(sequencer.io.keyLowData,
      sequencer.io.keyHighData, sequencer.io.keyLow1Data, sequencer.io.keyHigh1Data))) {
    port.valid := stream.valid; port.bits := stream.bits.asUInt; stream.ready := port.ready
  }
  key.io.loadStart := sequencer.io.coreKeyLoadStart
  key.io.loadIndex := sequencer.io.coreKeyLoadIndex
  key.io.loadValid := sequencer.io.coreKeyLoadValid
  key.io.load := sequencer.io.coreKeyLoad
  sequencer.io.coreKeyLoadStartReady := key.io.loadStartReady
  sequencer.io.coreKeyLoadReady := key.io.loadReady
  sequencer.io.coreKeyLoadDone := key.io.loadDone
  sequencer.io.coreKeyLoadDoneIndex := key.io.loadDoneIndex
  io.accepted := key.io.loadValid && key.io.loadReady
  io.loadDone := key.io.loadDone; io.loadIndex := key.io.loadDoneIndex
  key.io.readRequestValid := io.readValid; io.readReady := key.io.readRequestReady
  key.io.readIndex := io.readIndex; key.io.readRow := io.readRow; key.io.readBeat := io.readBeat
  key.io.readResponseReady := true.B
  io.responseValid := key.io.readResponseValid; io.response := key.io.readResponse
}

final class KeyIngressPipelineSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  it should "pair four independently stalled streams through the pipelined key cache" in {
    test(new KeyIngressPipelineHarness)
      .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
      val cfg = dut.config.keyBuffer
      cfg.loadLanes should be(32)
      val beats = cfg.loadBeatsPerCoefficient
      val width = cfg.externalProduct.bootstrappingKey.width
      width should be(27)
      val mask = (BigInt(1) << width) - 1
      def value(index: Int, beat: Int, lane: Int): Int = 1 + index * 100000 + beat * 32 + lane
      def streamWord(channel: Int, ordinal: Int): BigInt = {
        val index = ordinal / beats; val beat = ordinal % beats
        val packed = (0 until 16).foldLeft(BigInt(0)) { (bits, lane) =>
          val v = BigInt(value(index, beat, lane + (channel / 2) * 16))
          bits | ((v & mask) << (lane * 54)) | (((-v) & mask) << (lane * 54 + 27))
        }
        if (channel % 2 == 0) packed & ((BigInt(1) << 512) - 1) else packed >> 512
      }
      dut.io.start.poke(false.B); dut.io.readValid.poke(false.B)
      dut.io.readIndex.poke(0.U); dut.io.readRow.poke(0.U); dut.io.readBeat.poke(0.U)
      dut.io.streams.foreach { s => s.valid.poke(false.B); s.bits.foreach(_.poke(0.U)) }
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      dut.clock.step(SGenFrameRecovery.configuredCycles + 2)
      dut.io.start.poke(true.B); dut.io.ready.expect(true.B); dut.clock.step()
      dut.io.start.poke(false.B)
      val cursors = Array.fill(4)(0); val held = Array.fill(4)(false)
      var accepted = 0; var completed = 0; var cycle = 0
      val finalAccept = scala.collection.mutable.ArrayBuffer.empty[Int]
      val acceptanceCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
      while (completed < 2 && cycle < 3000) {
        for (channel <- 0 until 4) {
          if (!held(channel) && cursors(channel) < 2 * beats &&
              (cycle < 60 || (cycle + channel * 3) % 7 != 0)) held(channel) = true
          dut.io.streams(channel).valid.poke(held(channel).B)
          val bits = streamWord(channel, cursors(channel))
          for (word <- 0 until 16) dut.io.streams(channel).bits(word).poke(((bits >> (32 * word)) & 0xffffffffL).U)
        }
        if (dut.io.accepted.peek().litToBoolean) {
          accepted += 1; acceptanceCycles += cycle
          if (accepted % beats == 0) finalAccept += cycle
        }
        if (dut.io.loadDone.peek().litToBoolean) {
          dut.io.loadIndex.expect(completed.U)
          cycle should be(finalAccept(completed) + 2)
          completed += 1
        }
        for (channel <- 0 until 4) {
          if (held(channel) && dut.io.streams(channel).ready.peek().litToBoolean) {
            cursors(channel) += 1; held(channel) = false
          }
        }
        dut.clock.step(); cycle += 1
      }
      completed should be(2); accepted should be(2 * beats)
      acceptanceCycles.take(40).sliding(2).foreach(p => p(1) - p(0) should be(1))
      dut.io.streams.foreach(_.valid.poke(false.B))
      for (index <- 0 until 2; word <- 0 until cfg.wordsPerCoefficient) {
        dut.io.readIndex.poke(index.U); dut.io.readRow.poke((word / cfg.externalProduct.inputFrameBeats).U)
        dut.io.readBeat.poke((word % cfg.externalProduct.inputFrameBeats).U)
        dut.io.readValid.poke(true.B); dut.io.readReady.expect(true.B)
        dut.clock.step(); dut.io.responseValid.expect(true.B)
        for (c <- 0 until cfg.externalProduct.outputComponents; lane <- 0 until cfg.externalProduct.inputLanes) {
          val scalar = lane * cfg.externalProduct.outputComponents + c
          val v = value(index, word * cfg.loadGroupsPerRead + scalar / 32, scalar % 32)
          dut.io.response(c)(lane).real.expect(v.S); dut.io.response(c)(lane).imag.expect((-v).S)
        }
      }
      dut.io.readValid.poke(false.B)
    }
  }
}

package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer

/** Throughput regression for the exact CMUX shape emitted by the U280 flow. */
final class PaperU280BatchScheduleSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  behavior of "the U280 windowed CMUX pipeline"

  it should "retain the paper's 16-cycle command interval" in {
    if (!sys.env.get("FPT_U280_BATCH_SCHEDULE").contains("1")) {
      cancel("set FPT_U280_BATCH_SCHEDULE=1 to run the U280-sized RTL")
    }

    val forwardPath = Path.of(sys.env("FPT_SGEN_FORWARD")).toAbsolutePath.normalize
    val inversePath = Path.of(sys.env("FPT_SGEN_INVERSE")).toAbsolutePath.normalize
    require(Files.isRegularFile(forwardPath), s"missing $forwardPath")
    require(Files.isRegularFile(inversePath), s"missing $inversePath")

    val base = PaperSetII.cmuxEngine(
      forwardPath.toString,
      inversePath.toString,
      includeVerilogSource = true
    )
    val engine = base.copy(
      coefficient = base.coefficient.copy(windowedRotator = true),
      externalProduct = base.externalProduct.copy(
        multiplier = ExternalProductMultiplier.ExactPipelinedSchoolbookDsp
      )
    )
    val config = BatchedCmuxEngineConfig(
      engine,
      batchContexts = PaperSetII.bitwiseBatchContexts,
      coefficientStorage =
        BatchedCoefficientStorage.PrecomputedWindowedReplicatedBanks,
      serializeInverseComponents = true,
      useSynchronousExternalProductMemory = true,
      decoupledBootstrappingKey = true,
      pendingKeyRequestEntries = 1,
      registerForwardSlrInput = true
    )
    config.commandInterval should be(16)
    val issuedContexts = (0 until config.batchContexts) :+ 0

    test(new BatchedCmuxEngine(config)).withAnnotations(
      Seq(VerilatorBackendAnnotation, PaperVerilator.flags)
    ) { dut =>
      dut.io.loadStart.poke(false.B)
      dut.io.loadValid.poke(false.B)
      dut.io.commandValid.poke(false.B)
      dut.io.commandContext.poke(0.U)
      dut.io.exponent.poke(1.U)
      dut.io.drainStart.poke(false.B)
      dut.io.drainContext.poke(0.U)
      dut.io.drainReady.poke(false.B)
      dut.io.bootstrappingKeyValid.poke(true.B)
      dut.io.keyReadRequestReady.poke(true.B)
      for (component <- 0 until engine.externalProduct.outputComponents;
           lane <- 0 until engine.externalProduct.inputLanes) {
        dut.io.bootstrappingKey(component)(lane).real.poke(0.S)
        dut.io.bootstrappingKey(component)(lane).imag.poke(0.S)
      }
      for (lane <- 0 until engine.forwardTransform.lanes) {
        dut.io.forwardTwist(lane).c.poke(0.S)
        dut.io.forwardTwist(lane).cMinusD.poke(0.S)
        dut.io.forwardTwist(lane).cPlusD.poke(0.S)
      }
      for (lane <- 0 until engine.inverseTransform.lanes) {
        dut.io.inverseUntwist(lane).c.poke(0.S)
        dut.io.inverseUntwist(lane).cMinusD.poke(0.S)
        dut.io.inverseUntwist(lane).cPlusD.poke(0.S)
      }

      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      for (context <- 0 until config.batchContexts) {
        dut.io.loadContext.poke(context.U)
        dut.io.loadStart.poke(true.B)
        dut.clock.step()
        dut.io.loadStart.poke(false.B)
        dut.io.loadValid.poke(true.B)
        for (_ <- 0 until engine.coefficient.polynomialBeats) {
          dut.io.loadReady.expect(true.B)
          for (component <- 0 until engine.coefficient.components;
               lane <- 0 until engine.coefficient.inverseLanes) {
            dut.io.load(component)(lane).poke(0.U)
          }
          dut.clock.step()
        }
        dut.io.loadValid.poke(false.B)
        dut.io.loadDone.expect(true.B)
        dut.clock.step()
      }

      var cycle = 0
      val accepted = ArrayBuffer.empty[Int]
      val completed = ArrayBuffer.empty[Int]
      def step(): Unit = {
        if (dut.io.doneValid.peek().litToBoolean) completed += cycle
        dut.clock.step()
        cycle += 1
      }

      for (context <- issuedContexts) {
        dut.io.commandContext.poke(context.U)
        while (!dut.io.commandReady.peek().litToBoolean) {
          step()
          cycle should be < 1024
        }
        accepted += cycle
        dut.io.commandValid.poke(true.B)
        step()
        dut.io.commandValid.poke(false.B)
      }
      while (completed.size < issuedContexts.size) {
        step()
        cycle should be < 1024
      }

      info(s"accepted cycles ${accepted.mkString(",")}")
      info(s"completed cycles ${completed.mkString(",")}")
      accepted.take(config.batchContexts).sliding(2).foreach { pair =>
        pair(1) - pair(0) should be(16)
      }
      completed.take(config.batchContexts).sliding(2).foreach { pair =>
        pair(1) - pair(0) should be(16)
      }
      val latency = completed.head - accepted.head
      latency should be <= 274
      accepted.last should be(latency)
      completed.last - completed(config.batchContexts - 1) should be(latency - 240)
      info(s"U280 CMUX latency $latency, II 16")
    }
  }
}

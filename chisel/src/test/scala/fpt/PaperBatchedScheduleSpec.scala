package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer

/** Opt-in Set-II batch-throughput regression using the generated 512-point
  * tangent transforms. Fourteen contexts cover the shared-inverse pipeline
  * while retaining the paper's 16-cycle initiation interval.
  */
final class PaperBatchedScheduleSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  behavior of "the paper-shaped batched CMUX pipeline"

  it should "accept and complete Set-II contexts every 16 cycles" in {
    if (!sys.env.get("FPT_PAPER_BATCH").contains("1")) {
      cancel("set FPT_PAPER_BATCH=1 to run the generated paper-size batch RTL")
    }

    val forwardPath = Path
      .of(sys.env.getOrElse("FPT_SGEN_FORWARD", "../build/sgen-fpt/forward.v"))
      .toAbsolutePath
      .normalize
    val inversePath = Path
      .of(sys.env.getOrElse("FPT_SGEN_INVERSE", "../build/sgen-fpt/inverse.v"))
      .toAbsolutePath
      .normalize
    require(Files.isRegularFile(forwardPath), s"missing $forwardPath")
    require(Files.isRegularFile(inversePath), s"missing $inversePath")

    val engine = PaperSetII.cmuxEngine(
      forwardPath.toString,
      inversePath.toString,
      includeVerilogSource = true
    )
    val config = BatchedCmuxEngineConfig(
      engine,
      batchContexts = 14,
      serializeInverseComponents = true,
      useSynchronousExternalProductMemory = true
    )
    val issuedContexts = (0 until config.batchContexts) :+ 0

    test(new BatchedCmuxEngine(config))
      .withAnnotations(
        Seq(
          VerilatorBackendAnnotation,
          PaperVerilator.flags
        )
      ) { dut =>
        dut.io.loadStart.poke(false.B)
        dut.io.loadValid.poke(false.B)
        dut.io.commandValid.poke(false.B)
        dut.io.commandContext.poke(0.U)
        dut.io.exponent.poke(1.U)
        dut.io.drainStart.poke(false.B)
        dut.io.drainReady.poke(false.B)
        for (component <- 0 until engine.coefficient.components) {
          for (lane <- 0 until engine.coefficient.inverseLanes) {
            dut.io.load(component)(lane).poke(0.U)
          }
        }
        for (component <- 0 until engine.externalProduct.outputComponents) {
          for (lane <- 0 until engine.externalProduct.inputLanes) {
            dut.io.bootstrappingKey(component)(lane).real.poke(0.S)
            dut.io.bootstrappingKey(component)(lane).imag.poke(0.S)
          }
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
            dut.clock.step()
          }
          dut.io.loadValid.poke(false.B)
          dut.io.loadDone.expect(true.B)
          dut.clock.step()
        }

        var cycle = 0
        val acceptCycles = ArrayBuffer.empty[Int]
        val doneCycles = ArrayBuffer.empty[Int]
        val doneContexts = ArrayBuffer.empty[Int]

        def observeDone(): Unit = {
          if (dut.io.doneValid.peek().litToBoolean) {
            doneCycles += cycle
            doneContexts += dut.io.doneContext.peek().litValue.toInt
          }
        }

        def step(): Unit = {
          observeDone()
          dut.clock.step()
          cycle += 1
        }

        for (context <- issuedContexts) {
          dut.io.commandContext.poke(context.U)
          while (!dut.io.commandReady.peek().litToBoolean) {
            step()
          }
          acceptCycles += cycle
          dut.io.commandValid.poke(true.B)
          step()
          dut.io.commandValid.poke(false.B)
        }
        acceptCycles.sliding(2).foreach { pair =>
          pair(1) - pair(0) should be(config.commandInterval)
        }

        while (doneContexts.size < issuedContexts.size) {
          step()
          cycle should be < 512
        }
        doneContexts.toSeq should be(issuedContexts)
        doneCycles.sliding(2).foreach { pair =>
          pair(1) - pair(0) should be(config.commandInterval)
        }
        doneCycles.head - acceptCycles.head should be(211)
        info(
          s"Set-II batch interval ${config.commandInterval}, " +
            s"latency ${doneCycles.head - acceptCycles.head} launch-inclusive cycles"
        )
      }
  }
}

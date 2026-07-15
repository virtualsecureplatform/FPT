package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer

/** Opt-in Set-II throughput regression for the synthesis-oriented accumulator
  * banks. Fourteen barrel contexts cover its 217-cycle pipeline; fifteen
  * bitwise contexts cover its 234-cycle pipeline. Both wrap to context zero
  * to prove sustained 16-cycle reuse.
  */
final class PaperBankedBatchScheduleSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  behavior of "the paper-shaped memory-backed CMUX pipeline"

  it should "reuse throughput-matched Set-II contexts every 16 cycles" in {
    val bitwise = sys.env
      .get("FPT_PAPER_BITWISE_BANKED_BATCH")
      .contains("1")
    if (!sys.env.get("FPT_PAPER_BANKED_BATCH").contains("1") && !bitwise) {
      cancel(
        "set FPT_PAPER_BANKED_BATCH=1 or " +
          "FPT_PAPER_BITWISE_BANKED_BATCH=1 to run the paper-size banked RTL"
      )
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
      includeVerilogSource = true,
      bitwiseBitsPerCycle = if (bitwise) Some(2) else None
    )
    val expectedLatency = if (bitwise) 234 else 217
    val config = BatchedCmuxEngineConfig(
      engine,
      batchContexts = if (bitwise) 15 else 14,
      coefficientStorage =
        if (bitwise) BatchedCoefficientStorage.BitwiseReplicatedBanks
        else BatchedCoefficientStorage.ReplicatedBanks
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
        dut.io.drainContext.poke(0.U)
        dut.io.drainReady.poke(false.B)
        val torusMask = (BigInt(1) << engine.coefficient.torusWidth) - 1
        def initial(context: Int, component: Int, index: Int): BigInt = {
          (BigInt("9e3779b9", 16) * (index + 1) +
            BigInt("7f4a7c15", 16) * component +
            BigInt("6a09e667", 16) * context) & torusMask
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
          for (beat <- 0 until engine.coefficient.polynomialBeats) {
            dut.io.loadReady.expect(true.B)
            for (component <- 0 until engine.coefficient.components) {
              for (lane <- 0 until engine.coefficient.inverseLanes) {
                val index = beat * engine.coefficient.inverseLanes + lane
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
            cycle should be < 640
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
          cycle should be < 640
        }
        info(s"accept cycles: ${acceptCycles.mkString(", ")}")
        info(s"done cycles: ${doneCycles.mkString(", ")}")
        doneContexts.toSeq should be(issuedContexts)
        doneCycles.sliding(2).zipWithIndex.foreach { case (pair, index) =>
          withClue(s"completion interval after command $index: ") {
            pair(1) - pair(0) should be(config.commandInterval)
          }
        }
        doneCycles.head - acceptCycles.head should be(expectedLatency)
        info(
          s"${if (bitwise) "bitwise " else ""}banked Set-II " +
            s"interval ${config.commandInterval}, " +
            s"latency ${doneCycles.head - acceptCycles.head}, " +
            s"contexts ${config.batchContexts}"
        )

        dut.io.drainReady.poke(true.B)
        for (context <- 0 until config.batchContexts) {
          dut.io.drainContext.poke(context.U)
          while (!dut.io.drainStartReady.peek().litToBoolean) {
            step()
            cycle should be < 2048
          }
          dut.io.drainStart.poke(true.B)
          step()
          dut.io.drainStart.poke(false.B)
          while (!dut.io.drainValid.peek().litToBoolean) {
            step()
            cycle should be < 2048
          }
          for (beat <- 0 until engine.coefficient.polynomialBeats) {
            dut.io.drainValid.expect(true.B)
            for (component <- 0 until engine.coefficient.components) {
              for (lane <- 0 until engine.coefficient.inverseLanes) {
                val index = beat * engine.coefficient.inverseLanes + lane
                withClue(
                  s"context=$context component=$component index=$index"
                ) {
                  dut.io.drain(component)(lane).expect(
                    initial(context, component, index).U
                  )
                }
              }
            }
            step()
          }
          dut.io.drainDone.expect(true.B)
          dut.io.drainDoneContext.expect(context.U)
        }
      }
  }
}

package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** An opt-in paper-size scheduling regression. The generated SGen sources are
  * intentionally build artifacts rather than checked-in RTL, so run with
  * `FPT_PAPER_SCHEDULE=1 sbt 'testOnly fpt.PaperCmuxScheduleSpec'` after
  * `tools/generate_sgen_fpt.sh`. Select the bitwise frontend with
  * `FPT_PAPER_BITWISE_SCHEDULE=1`; its Chisel elaboration needs a larger JVM
  * heap and its monolithic Verilator C++ model is substantially heavier.
  */
final class PaperCmuxScheduleSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  behavior of "the paper-shaped CMUX scheduler"

  it should "compose transforms and preserve a zero-product accumulator" in {
    val bitwise = sys.env.get("FPT_PAPER_BITWISE_SCHEDULE").contains("1")
    if (!sys.env.get("FPT_PAPER_SCHEDULE").contains("1") && !bitwise) {
      cancel(
        "set FPT_PAPER_SCHEDULE=1 or FPT_PAPER_BITWISE_SCHEDULE=1 " +
          "to run the generated paper-size RTL"
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

    val config = PaperSetII.cmuxEngine(
      forwardPath.toString,
      inversePath.toString,
      includeVerilogSource = true,
      bitwiseBitsPerCycle = if (bitwise) Some(2) else None
    )
    test(new CmuxEngine(config))
      .withAnnotations(
        Seq(
          VerilatorBackendAnnotation,
          PaperVerilator.flags
        )
      ) { dut =>
        dut.io.loadStart.poke(false.B)
        dut.io.loadValid.poke(false.B)
        dut.io.commandValid.poke(false.B)
        dut.io.exponent.poke(1.U)
        dut.io.drainStart.poke(false.B)
        dut.io.drainReady.poke(false.B)
        val torusMask = (BigInt(1) << config.coefficient.torusWidth) - 1
        val initial = Seq.tabulate(config.coefficient.components) { component =>
          Seq.tabulate(config.coefficient.polynomialSize) { index =>
            (BigInt("9e3779b9", 16) * (index + 1) +
              BigInt("7f4a7c15", 16) * component) & torusMask
          }
        }
        for (component <- 0 until config.externalProduct.outputComponents) {
          for (lane <- 0 until config.externalProduct.inputLanes) {
            dut.io.bootstrappingKey(component)(lane).real.poke(0.S)
            dut.io.bootstrappingKey(component)(lane).imag.poke(0.S)
          }
        }
        for (lane <- 0 until config.forwardTransform.lanes) {
          dut.io.forwardTwist(lane).c.poke(0.S)
          dut.io.forwardTwist(lane).cMinusD.poke(0.S)
          dut.io.forwardTwist(lane).cPlusD.poke(0.S)
          dut.io.forwardFftTwiddle(lane).c.poke(0.S)
          dut.io.forwardFftTwiddle(lane).cMinusD.poke(0.S)
          dut.io.forwardFftTwiddle(lane).cPlusD.poke(0.S)
        }
        for (lane <- 0 until config.inverseTransform.lanes) {
          dut.io.inverseFftTwiddle(lane).c.poke(0.S)
          dut.io.inverseFftTwiddle(lane).cMinusD.poke(0.S)
          dut.io.inverseFftTwiddle(lane).cPlusD.poke(0.S)
          dut.io.inverseUntwist(lane).c.poke(0.S)
          dut.io.inverseUntwist(lane).cMinusD.poke(0.S)
          dut.io.inverseUntwist(lane).cPlusD.poke(0.S)
        }

        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.loadStart.poke(true.B)
        dut.clock.step()
        dut.io.loadStart.poke(false.B)
        dut.io.loadValid.poke(true.B)
        for (beat <- 0 until config.coefficient.polynomialBeats) {
          dut.io.loadReady.expect(true.B)
          for (component <- 0 until config.coefficient.components) {
            for (lane <- 0 until config.coefficient.inverseLanes) {
              val index = beat * config.coefficient.inverseLanes + lane
              dut.io.load(component)(lane).poke(initial(component)(index).U)
            }
          }
          dut.clock.step()
        }
        dut.io.loadValid.poke(false.B)
        dut.io.loadDone.expect(true.B)
        dut.clock.step()

        dut.io.commandReady.expect(true.B)
        dut.io.commandValid.poke(true.B)
        dut.clock.step()
        dut.io.commandValid.poke(false.B)

        var cycles = 0
        while (!dut.io.done.peek().litToBoolean) {
          dut.clock.step()
          cycles += 1
          cycles should be < 256
        }
        info(
          s"paper-shaped ${if (bitwise) "bitwise " else ""}SGen CMUX " +
            s"latency: $cycles cycles after acceptance " +
            s"(${cycles + 1} launch-inclusive cycles)"
        )
        val forwardLatency = "latency of (\\d+) cycles".r
          .findFirstMatchIn(Files.readString(forwardPath)).get.group(1).toInt
        val inverseLatency = "latency of (\\d+) cycles".r
          .findFirstMatchIn(Files.readString(inversePath)).get.group(1).toInt
        cycles should be((if (bitwise) 224 else 207) + forwardLatency - 70 + inverseLatency - 112)

        dut.io.drainStart.poke(true.B)
        dut.clock.step()
        dut.io.drainStart.poke(false.B)
        dut.io.drainReady.poke(true.B)
        for (beat <- 0 until config.coefficient.polynomialBeats) {
          dut.io.drainValid.expect(true.B)
          for (component <- 0 until config.coefficient.components) {
            for (lane <- 0 until config.coefficient.inverseLanes) {
              val index = beat * config.coefficient.inverseLanes + lane
              withClue(s"component=$component index=$index") {
                dut.io.drain(component)(lane).expect(initial(component)(index).U)
              }
            }
          }
          dut.clock.step()
        }
        dut.io.drainDone.expect(true.B)
      }
  }
}

package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** An opt-in paper-size scheduling regression. The generated SGen sources are
  * intentionally build artifacts rather than checked-in RTL, so run with
  * `FPT_PAPER_SCHEDULE=1 sbt 'testOnly fpt.PaperCmuxScheduleSpec'` after
  * `tools/generate_sgen_fpt.sh`.
  */
final class PaperCmuxScheduleSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  behavior of "the paper-shaped CMUX scheduler"

  it should "compose the four forward rows and two inverse streams" in {
    if (!sys.env.get("FPT_PAPER_SCHEDULE").contains("1")) {
      cancel("set FPT_PAPER_SCHEDULE=1 to run the generated paper-size RTL")
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
      includeVerilogSource = true
    )
    test(new CmuxEngine(config))
      .withAnnotations(
        Seq(
          VerilatorBackendAnnotation,
          VerilatorFlags(
            Seq(
              "--output-split",
              "99999999",
              "--output-split-cfuncs",
              "99999999"
            )
          )
        )
      ) { dut =>
        dut.io.loadStart.poke(false.B)
        dut.io.loadValid.poke(false.B)
        dut.io.commandValid.poke(false.B)
        dut.io.exponent.poke(1.U)
        dut.io.drainStart.poke(false.B)
        dut.io.drainReady.poke(false.B)
        for (component <- 0 until config.coefficient.components) {
          for (lane <- 0 until config.coefficient.inverseLanes) {
            dut.io.load(component)(lane).poke(0.U)
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
        for (_ <- 0 until config.coefficient.polynomialBeats) {
          dut.io.loadReady.expect(true.B)
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
          s"paper-shaped SGen CMUX latency: $cycles cycles after acceptance " +
            s"(${cycles + 1} launch-inclusive cycles)"
        )
        cycles should be(191)
      }
  }
}

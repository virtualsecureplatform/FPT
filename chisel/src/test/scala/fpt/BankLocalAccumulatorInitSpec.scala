package fpt

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

private class BankLocalInitMiter(c: CmuxCoefficientConfig) extends Module {
  val cfg = ReplicatedAccumulatorBanksConfig(c, 3)
  val candidate = Module(new ReplicatedAccumulatorBanks(cfg, false, true, true,
    minimalMetadataReset = sys.env.get("FPT_U280_MINIMAL_METADATA_RESET").contains("1")))
  val reference = Module(new ReplicatedAccumulatorBanks(cfg, false, true))
  val io = IO(chiselTypeOf(candidate.io))
  candidate.io <> io
  // Copy all ordinary inputs; the reference retains its general streamed loader.
  for ((name, port) <- reference.io.elements if chisel3.reflect.DataMirror.directionOf(port) == ActualDirection.Input) {
    port := io.elements(name)
  }
  for (name <- Seq("loadReady", "loadDone", "contextLoaded", "prefetchReady", "prefetchValid",
      "prefetchDone", "drainStartReady", "drainValid", "drainDone", "updateReady", "updateDone")) {
    assert(reference.io.elements(name).asUInt === candidate.io.elements(name).asUInt)
  }
  when(io.loadDone) { assert(reference.io.loadDoneContext === io.loadDoneContext) }
  when(io.updateDone) { assert(reference.io.updateDoneContext === io.updateDoneContext) }
  when(io.prefetchValid) {
    assert(reference.io.prefetchLow.asUInt === io.prefetchLow.asUInt)
    assert(reference.io.prefetchHigh.asUInt === io.prefetchHigh.asUInt)
    assert(reference.io.prefetchBeat === io.prefetchBeat)
    assert(reference.io.prefetchOutputContext === io.prefetchOutputContext)
  }
  when(io.drainValid) { assert(reference.io.drain.asUInt === io.drain.asUInt) }
  when(io.drainDone) { assert(reference.io.drainDoneContext === io.drainDoneContext) }
}

final class BankLocalAccumulatorInitSpec extends AnyFlatSpec with ChiselScalatestTester {
  for (lanes <- Seq(2, 8)) {
    it should s"match streamed initialization, updates, prefetch and stalled drain with $lanes lanes" in {
      val c = CmuxCoefficientConfig(polynomialSize = lanes * 16, forwardLanes = lanes * 2,
        inverseLanes = lanes, components = 2, levels = 3, baseBits = 6, torusWidth = 32,
        forwardFormat = FixedFormat(18, 20), inverseFormat = FixedFormat(27, 14))
      val cfg = ReplicatedAccumulatorBanksConfig(c, 3)
      test(new BankLocalInitMiter(c)).withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
        val mask = (BigInt(1) << 32) - 1
        dut.io.loadStart.poke(false.B); dut.io.loadValid.poke(false.B); dut.io.loadContext.poke(0.U)
        dut.io.prefetchStart.poke(false.B); dut.io.prefetchContext.poke(0.U)
        dut.io.drainStart.poke(false.B); dut.io.drainContext.poke(0.U); dut.io.drainReady.poke(false.B)
        dut.io.updateValid.poke(false.B); dut.io.updateFirst.poke(false.B); dut.io.updateContext.poke(0.U)
        dut.io.loadDescriptor.get.positive.poke(0.U); dut.io.loadDescriptor.get.negative.poke(0.U)
        dut.io.loadDescriptor.get.exponent.poke(0.U)
        for (component <- 0 until 2; lane <- 0 until lanes) {
          dut.io.load(component)(lane).poke(0.U)
          dut.io.updateLow(component)(lane).poke(0.S); dut.io.updateHigh(component)(lane).poke(0.S)
        }
        def reset(): Unit = {
          dut.io.loadStart.poke(false.B); dut.io.loadValid.poke(false.B)
          dut.io.updateValid.poke(false.B); dut.io.updateFirst.poke(false.B)
          dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
        }
        reset()
        val exponents = Seq(0, 1, lanes-1, lanes, c.points-1, c.points,
          c.polynomialSize-1, c.polynomialSize, 2*c.polynomialSize-1)
        val values = Seq(BigInt(0), BigInt(1), mask, BigInt(1) << 31, BigInt("12345678", 16))
        for ((exponent, trial) <- exponents.zipWithIndex) {
          val value = values(trial % values.size)
          val context = trial % 3
          def expected(component: Int, index: Int): BigInt = {
            val negative = ((exponent & c.polynomialSize) != 0) ^ (index < (exponent % c.polynomialSize))
            if (component == 0) BigInt(0) else if (negative) (-value) & mask else value
          }
          dut.io.loadContext.poke(context.U)
          dut.io.loadDescriptor.get.positive.poke(value.U)
          dut.io.loadDescriptor.get.negative.poke(((-value) & mask).U)
          dut.io.loadDescriptor.get.exponent.poke(exponent.U)
          dut.io.loadStart.poke(true.B); dut.clock.step(); dut.io.loadStart.poke(false.B)
          dut.io.loadValid.poke(true.B)
          for (beat <- 0 until cfg.loadBeats) {
            dut.io.loadReady.expect(true.B)
            for (component <- 0 until 2; lane <- 0 until lanes)
              dut.io.load(component)(lane).poke(expected(component, beat * lanes + lane).U)
            dut.clock.step()
          }
          dut.io.loadValid.poke(false.B); dut.io.loadDone.expect(true.B)
          dut.clock.step()
          dut.io.updateContext.poke(context.U); dut.io.updateValid.poke(true.B)
          for (beat <- 0 until cfg.halfBeats) {
            dut.io.updateFirst.poke((beat == 0).B)
            for (component <- 0 until 2; lane <- 0 until lanes) {
              dut.io.updateLow(component)(lane).poke((component + lane + 1).S)
              dut.io.updateHigh(component)(lane).poke((-(component + lane + 1)).S)
            }
            dut.io.updateReady.expect(true.B); dut.clock.step()
          }
          dut.io.updateValid.poke(false.B); dut.io.updateFirst.poke(false.B)
          dut.clock.step(); dut.io.updateDone.expect(true.B); dut.clock.step()
          dut.io.prefetchContext.poke(context.U); dut.io.prefetchStart.poke(true.B)
          dut.clock.step(); dut.io.prefetchStart.poke(false.B)
          dut.clock.step(cfg.halfBeats + 4)
          dut.io.drainContext.poke(context.U); dut.io.drainStartReady.expect(true.B)
          dut.io.drainStart.poke(true.B); dut.clock.step(); dut.io.drainStart.poke(false.B)
          var beats = 0; var cycles = 0
          while (beats < cfg.loadBeats && cycles < 100) {
            val ready = cycles % 3 != 1
            dut.io.drainReady.poke(ready.B)
            if (dut.io.drainValid.peek().litToBoolean && ready) beats += 1
            dut.clock.step(); cycles += 1
          }
          assert(beats == cfg.loadBeats)
          dut.io.drainDone.expect(true.B); dut.clock.step(2)
          if (trial == 3) {
            // Abort an initialization burst, then verify the next complete image.
            dut.io.loadStart.poke(true.B); dut.clock.step(); dut.io.loadStart.poke(false.B)
            dut.io.loadValid.poke(true.B); dut.clock.step(2); reset()
          }
        }
      }
    }
  }
}

object EmitBankLocalAccumulatorInit extends App {
  val cfg = ReplicatedAccumulatorBanksConfig(PaperSetII.coefficient, PaperSetII.bitwiseBatchContexts)
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new ReplicatedAccumulatorBanks(cfg, false, true, true,
    minimalMetadataReset = sys.env.get("FPT_U280_MINIMAL_METADATA_RESET").contains("1")),
    Array("--target-dir", args(0)), firtoolOpts = SynthesisEmitter.firtoolOptions)
  val rtl = java.nio.file.Path.of(args(0)).resolve("ReplicatedAccumulatorBanks.sv")
  SynthesisEmitter.removeInlineFileList(rtl)
  SynthesisEmitter.addUltraRamStyleByPrefix(rtl, "forwardMemories_")
}

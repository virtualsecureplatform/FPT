package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

private class ExternalProductLocalBankMiter(lanes: Int, localRows: Boolean) extends Module {
  private val config = ExternalProductConfig(
    points = lanes * 8, inputLanes = lanes * 2, outputLanes = lanes,
    rows = 4, outputComponents = 2, spectrum = FixedFormat(18, 12),
    bootstrappingKey = FixedFormat(8, 19), accumulator = FixedFormat(27, 3),
    multiplier = ExternalProductMultiplier.ExactPipelinedSchoolbookDsp)
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val first = Input(Bool())
    val tag = Input(UInt(6.W))
    val decomposition = Input(Vec(lanes * 2, new ComplexSInt(30)))
    val key = Input(Vec(2, Vec(lanes * 2, new ComplexSInt(27))))
    val done = Output(Bool())
  })
  val reference = Module(new RotatingThreeBankExternalProductAccumulator(config, 6))
  val candidate = Module(new RotatingThreeBankExternalProductAccumulator(config, 6, 6,
    if (localRows) 6 else 0))
  for (dut <- Seq(reference, candidate)) {
    dut.io.inputValid := io.valid
    dut.io.inputFirst := io.first
    dut.io.inputTag := io.tag
    dut.io.decomposition := io.decomposition
    dut.io.bootstrappingKey := io.key
    dut.io.outputReady := true.B
  }
  io.done := candidate.io.done
  assert(reference.io.inputReady === candidate.io.inputReady)
  assert(reference.io.outputValid === candidate.io.outputValid)
  assert(reference.io.outputStart === candidate.io.outputStart)
  assert(reference.io.done === candidate.io.done)
  assert(reference.io.busy === candidate.io.busy)
  when(reference.io.outputValid) {
    assert(reference.io.serializedOutput.asUInt === candidate.io.serializedOutput.asUInt)
    assert(reference.io.output.asUInt === candidate.io.output.asUInt)
    assert(reference.io.outputFirst === candidate.io.outputFirst)
    assert(reference.io.outputLast === candidate.io.outputLast)
    assert(reference.io.outputTag === candidate.io.outputTag)
    assert(reference.io.outputComponent === candidate.io.outputComponent)
  }
}

final class ExternalProductLocalBankSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "bank-local External Product controls"
  for (lanes <- Seq(8, 16); localRows <- Seq(false, true)) {
    it should s"match every cycle with $lanes lanes, localRows=$localRows, overlapping images, gaps and reset" in {
      test(new ExternalProductLocalBankMiter(lanes, localRows)).withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
        val random = new scala.util.Random(0x541L + lanes)
        def signed(width: Int): BigInt = {
          val bits = BigInt(width, random)
          if (bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
        }
        var completed = 0
        def beat(valid: Boolean, first: Boolean = false, tag: Int = 0): Unit = {
          dut.io.valid.poke(valid.B)
          dut.io.first.poke(first.B)
          dut.io.tag.poke(tag.U)
          for (lane <- 0 until lanes * 2) {
            dut.io.decomposition(lane).real.poke(signed(30).S)
            dut.io.decomposition(lane).imag.poke(signed(30).S)
            for (component <- 0 until 2) {
              dut.io.key(component)(lane).real.poke(signed(27).S)
              dut.io.key(component)(lane).imag.poke(signed(27).S)
            }
          }
          if (dut.reset.peek().litValue == 0 && dut.io.done.peek().litToBoolean) completed += 1
          dut.clock.step()
        }
        def reset(): Unit = {
          dut.reset.poke(true.B)
          beat(false); beat(false)
          dut.reset.poke(false.B)
        }
        reset()
        for (transaction <- 0 until 12) {
          for (index <- 0 until 16) beat(true, index == 0, transaction)
          if (transaction == 4) for (_ <- 0 until 3) beat(false)
          if (transaction == 8) for (_ <- 0 until 19) beat(false)
        }
        for (_ <- 0 until 24) beat(false)
        assert(completed == 12)
        // Abort both during input and while one image drains alongside the
        // following transaction; then verify restart with fresh data.
        for (abortAfter <- Seq(7, 20)) {
          for (index <- 0 until abortAfter) beat(true, index % 16 == 0, 20 + index / 16)
          reset()
          completed = 0
          for (transaction <- 21 until 24; index <- 0 until 16) beat(true, index == 0, transaction)
          for (_ <- 0 until 24) beat(false)
          assert(completed == 3)
        }
      }
    }
  }
}

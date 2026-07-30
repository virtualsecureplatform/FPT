package fpt

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

/** Equivalence harness: a pipelined rotator against the combinational
  * reference, compared through a shadow delay that shares the pipeline's
  * advance strobe so stalls cannot desynchronize the comparison.
  */
final class PipelinedRotatorHarness(
    size: Int,
    width: Int,
    every: Int
) extends Module {
  private val indexWidth = log2Ceil(size)
  val reference = Module(new NegacyclicBarrelRotator(size, width))
  val pipelined = Module(new NegacyclicBarrelRotator(size, width, every))
  require(pipelined.latency > 0)

  val io = IO(new Bundle {
    val input = Input(Vec(size, UInt(width.W)))
    val exponent = Input(UInt((indexWidth + 1).W))
    val enable = Input(Bool())
    val checkValid = Output(Bool())
    val mismatch = Output(Bool())
  })

  reference.io.input := io.input
  reference.io.exponent := io.exponent
  pipelined.io.input := io.input
  pipelined.io.exponent := io.exponent
  pipelined.enable.get := io.enable

  private val k = pipelined.latency
  private val delayedReference =
    ShiftRegister(reference.io.output, k, io.enable)
  private val fill = RegInit(0.U(8.W))
  when(io.enable && fill < k.U) { fill := fill + 1.U }
  io.checkValid := fill === k.U
  io.mismatch := io.checkValid &&
    delayedReference.asUInt =/= pipelined.io.output.asUInt
}

final class PipelinedRotatorEquivalenceSpec
    extends AnyFlatSpec
    with ChiselScalatestTester {
  private def run(size: Int, width: Int, every: Int): Unit = {
    test(new PipelinedRotatorHarness(size, width, every)) { dut =>
      val random = new Random(0xf97 + every)
      for (cycle <- 0 until 600) {
        for (index <- 0 until size) {
          dut.io.input(index).poke(BigInt(width, random).U)
        }
        dut.io.exponent.poke(BigInt(random.nextInt(2 * size)).U)
        dut.io.enable.poke((random.nextInt(4) != 0).B)
        dut.io.mismatch.expect(false.B)
        dut.clock.step(1)
      }
    }
  }

  behavior of "NegacyclicBarrelRotator pipelining"

  it should "match the combinational rotator with layers every 2 stages" in {
    run(size = 64, width = 16, every = 2)
  }

  it should "match the combinational rotator with layers every stage" in {
    run(size = 32, width = 12, every = 1)
  }

  it should "match under a dense enable pattern at Set-II width" in {
    run(size = 1024, width = 32, every = 2)
  }
}

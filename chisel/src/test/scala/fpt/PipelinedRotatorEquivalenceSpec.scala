package fpt

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable
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
  // Compare lanes before packing.  Casting each 1024-by-32 Vec to one UInt
  // makes Verilator build a ladder of progressively wider concatenation
  // temporaries; the generated evaluator then exceeds sbt's native worker
  // stack before it can execute the equivalence check.
  private val laneMismatch = VecInit((0 until size).map { lane =>
    delayedReference(lane) =/= pipelined.io.output(lane)
  })
  io.mismatch := io.checkValid && laneMismatch.asUInt.orR
}

final class PipelinedRotatorEquivalenceSpec
    extends AnyFlatSpec
    with ChiselScalatestTester {
  private def run(size: Int, width: Int, every: Int): Unit = {
    test(new PipelinedRotatorHarness(size, width, every))
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

  it should "align bank-width windows across arbitrary stalls" in {
    val size = 64
    val width = 12
    val blockLanes = 8
    val outputLanes = 16
    val ringBlocks = 2 * size / blockLanes
    val mask = (BigInt(1) << width) - 1
    val random = new Random(0x57494e44L)

    test(
      new PipelinedWindowedNegacyclicRotatorSpan(
        size,
        width,
        blockLanes,
        outputLanes
      )
    ) { dut =>
      val expected = mutable.Queue.empty[Seq[BigInt]]
      var lastOutput = Seq.fill(outputLanes)(BigInt(0))
      var haveOutput = false

      def driveAndStep(enable: Boolean, recordInput: Boolean = true): Unit = {
        val polynomial = Seq.fill(size)(BigInt(width, random))
        val firstRingBlock = random.nextInt(ringBlocks)
        val offset = random.nextInt(blockLanes)
        for (block <- 0 until outputLanes / blockLanes + 1) {
          for (lane <- 0 until blockLanes) {
            val ringBlock = (firstRingBlock + block) & (ringBlocks - 1)
            val physical = (ringBlock * blockLanes + lane) & (size - 1)
            dut.io.input(block)(lane).poke(polynomial(physical).U)
          }
        }
        dut.io.firstRingBlock.poke(firstRingBlock.U)
        dut.io.offset.poke(offset.U)
        dut.io.enable.poke(enable.B)
        dut.clock.step()

        if (enable) {
          val result = (0 until outputLanes).map { lane =>
            val ringIndex = (firstRingBlock * blockLanes + offset + lane) &
              (2 * size - 1)
            val value = polynomial(ringIndex & (size - 1))
            if (ringIndex >= size) (-value) & mask else value
          }
          if (recordInput) expected.enqueue(result)
          if (
            (!recordInput && expected.nonEmpty) ||
            expected.size >= PipelinedWindowedNegacyclicRotatorSpan.latency
          ) {
            lastOutput = expected.dequeue()
            haveOutput = true
            for (lane <- 0 until outputLanes) {
              dut.io.output(lane).expect(lastOutput(lane).U)
            }
          }
        } else if (haveOutput) {
          for (lane <- 0 until outputLanes) {
            dut.io.output(lane).expect(lastOutput(lane).U)
          }
        }
      }

      for (_ <- 0 until 300) {
        driveAndStep(random.nextInt(4) != 0)
      }
      while (expected.nonEmpty) {
        driveAndStep(enable = true, recordInput = false)
      }
    }
  }
}

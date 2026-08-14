package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.util.Random

final class RelatedClockSllGearbox2xSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  behavior of "RelatedClockSllGearbox2x"

  it should "preserve a full-rate stream under output backpressure" in {
    test(new RelatedClockSllGearbox2x(width = 17, credits = 4))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) {
      dut =>
        def stepSlow(cycles: Int = 1): Unit = {
          for (_ <- 0 until cycles) {
            val fast = fork { dut.io.fastClock.step(2) }
            dut.clock.step()
            fast.join()
          }
        }

        dut.io.enq.valid.poke(false.B)
        dut.io.enq.bits.poke(0.U)
        dut.io.retire.poke(false.B)
        dut.io.receiveReady.poke(true.B)
        dut.io.fastReset.poke(true.B)
        dut.reset.poke(true.B)
        stepSlow(3)
        dut.reset.poke(false.B)
        dut.io.fastReset.poke(false.B)

        val random = new Random(0x5a11L)
        val expected = mutable.Queue.empty[BigInt]
        val received = mutable.Queue.empty[BigInt]
        var nextWord = 0
        val words = 80

        for (_ <- 0 until 260) {
          val offer = nextWord < words && random.nextInt(5) != 0
          val retire = received.nonEmpty && random.nextInt(4) != 0
          val value = BigInt((nextWord * 7919 + 17) & 0x1ffff)
          dut.io.enq.valid.poke(offer.B)
          dut.io.enq.bits.poke(value.U)
          dut.io.retire.poke(retire.B)

          val inputFire = offer && dut.io.enq.ready.peek().litToBoolean
          if (dut.io.outputValid.peek().litToBoolean) {
            expected.nonEmpty shouldBe true
            dut.io.output.expect(expected.front.U)
            received.enqueue(expected.dequeue())
          }
          if (retire) {
            received.dequeue()
          }
          if (inputFire) {
            expected.enqueue(value)
            nextWord += 1
          }
          stepSlow()
        }

        dut.io.enq.valid.poke(false.B)
        dut.io.retire.poke(false.B)
        var drainCycles = 0
        while (expected.nonEmpty && drainCycles < 40) {
          if (dut.io.outputValid.peek().litToBoolean) {
            expected.nonEmpty shouldBe true
            dut.io.output.expect(expected.front.U)
            received.enqueue(expected.dequeue())
          }
          stepSlow()
          drainCycles += 1
        }
        nextWord shouldBe words
        expected shouldBe empty
        dut.io.outputValid.expect(false.B)
    }
  }
}

package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Random

final class ArithmeticSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private def vectorPath(name: String): Path = {
    val candidates = Seq(
      Path.of("..", "build", name),
      Path.of("..", "build-tfhepp", name),
      Path.of("build", name)
    )
    candidates.find(Files.exists(_)).getOrElse(
      fail(s"Could not find $name; build the C++ RTL vectors first")
    )
  }

  private def vectors(name: String): Seq[Array[BigInt]] =
    Files
      .readAllLines(vectorPath(name))
      .asScala
      .filter(_.trim.nonEmpty)
      .map(_.trim.split("\\s+").map(BigInt(_)))
      .toSeq

  behavior of "FPT Chisel fixed-point arithmetic"

  it should "match every C++ butterfly vector with and without scaling" in {
    val rows = vectors("rtl_vectors.txt")
    test(new FptButterfly(30, 26, 24)) { dut =>
      dut.io.validIn.poke(false.B)
      dut.clock.step()

      for ((row, index) <- rows.zipWithIndex) {
        dut.io.even.real.poke(row(0).S)
        dut.io.even.imag.poke(row(1).S)
        dut.io.odd.real.poke(row(2).S)
        dut.io.odd.imag.poke(row(3).S)
        dut.io.twiddle.c.poke(row(4).S)
        dut.io.twiddle.cMinusD.poke(row(5).S)
        dut.io.twiddle.cPlusD.poke(row(6).S)

        for ((scale, expectedOffset) <- Seq((false, 7), (true, 11))) {
          dut.io.scale.poke(scale.B)
          dut.io.validIn.poke(true.B)
          dut.clock.step()
          withClue(s"vector=$index scale=$scale upper real") {
            dut.io.validOut.expect(true.B)
            dut.io.upper.real.expect(row(expectedOffset).S)
          }
          dut.io.upper.imag.expect(row(expectedOffset + 1).S)
          dut.io.lower.real.expect(row(expectedOffset + 2).S)
          dut.io.lower.imag.expect(row(expectedOffset + 3).S)
        }
      }
      dut.io.validIn.poke(false.B)
      dut.clock.step()
      dut.io.validOut.expect(false.B)
    }
  }

  it should "match every C++ mixed-format complex MAC vector" in {
    val rows = vectors("rtl_mac_vectors.txt")
    test(new ComplexMac(38, 20, 32, 24, 41, 14)) { dut =>
      dut.io.validIn.poke(false.B)
      dut.clock.step()
      for ((row, index) <- rows.zipWithIndex) {
        dut.io.a.real.poke(row(0).S)
        dut.io.a.imag.poke(row(1).S)
        dut.io.b.real.poke(row(2).S)
        dut.io.b.imag.poke(row(3).S)
        dut.io.accumulator.real.poke(row(4).S)
        dut.io.accumulator.imag.poke(row(5).S)
        dut.io.validIn.poke(true.B)
        dut.clock.step()
        withClue(s"MAC vector=$index real") {
          dut.io.validOut.expect(true.B)
          dut.io.result.real.expect(row(6).S)
        }
        dut.io.result.imag.expect(row(7).S)
      }
    }
  }

  it should "keep the DSP-sized Gauss product exact at signed boundaries" in {
    val aWidth = 30
    val bWidth = 27
    val aLimit = BigInt(1) << (aWidth - 1)
    val bLimit = BigInt(1) << (bWidth - 1)
    val aBoundary = Seq(
      -aLimit,
      -aLimit + 1,
      BigInt(-1),
      BigInt(0),
      BigInt(1),
      aLimit - 2,
      aLimit - 1
    )
    val bBoundary = Seq(
      -bLimit,
      -bLimit + 1,
      BigInt(-1),
      BigInt(0),
      BigInt(1),
      bLimit - 2,
      bLimit - 1
    )
    val boundaryVectors = for {
      aReal <- aBoundary
      aImag <- aBoundary
      bReal <- bBoundary
      bImag <- bBoundary
    } yield (aReal, aImag, bReal, bImag)

    val random = new Random(0x465054L)
    def randomSigned(width: Int): BigInt = {
      val bits = BigInt(width, random)
      if (bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
    }
    val randomVectors = Seq.fill(5000)(
      (
        randomSigned(aWidth),
        randomSigned(aWidth),
        randomSigned(bWidth),
        randomSigned(bWidth)
      )
    )

    test(new ExactGaussComplexMultiply(aWidth, bWidth))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      for (((aReal, aImag, bReal, bImag), index) <-
          (boundaryVectors ++ randomVectors).zipWithIndex) {
        dut.io.a.real.poke(aReal.S)
        dut.io.a.imag.poke(aImag.S)
        dut.io.b.real.poke(bReal.S)
        dut.io.b.imag.poke(bImag.S)
        val expectedReal = aReal * bReal - aImag * bImag
        val expectedImag = aReal * bImag + aImag * bReal
        withClue(s"Gauss product vector=$index real") {
          dut.io.productReal.expect(expectedReal.S)
        }
        withClue(s"Gauss product vector=$index imag") {
          dut.io.productImag.expect(expectedImag.S)
        }
      }
    }
  }

  it should "pipeline exact DSP-sized schoolbook products every cycle" in {
    val aWidth = 30
    val bWidth = 27
    val random = new Random(0x55323830L)
    def randomSigned(width: Int): BigInt = {
      val bits = BigInt(width, random)
      if (bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
    }
    val aLimit = BigInt(1) << (aWidth - 1)
    val bLimit = BigInt(1) << (bWidth - 1)
    val vectors = Seq(
      (-aLimit, -aLimit, -bLimit, -bLimit),
      (-aLimit, aLimit - 1, bLimit - 1, -bLimit),
      (aLimit - 1, -aLimit, -bLimit, bLimit - 1),
      (aLimit - 1, aLimit - 1, bLimit - 1, bLimit - 1),
      (BigInt(-1), BigInt(0), BigInt(1), BigInt(-1))
    ) ++ Seq.fill(500)(
      (
        randomSigned(aWidth),
        randomSigned(aWidth),
        randomSigned(bWidth),
        randomSigned(bWidth)
      )
    )

    test(new PipelinedExactSchoolbookComplexMultiply(aWidth, bWidth))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val expected = mutable.Queue.empty[(BigInt, BigInt, Int)]

      def checkReady(): Unit = {
        if (
          expected.size >= PipelinedExactSchoolbookComplexMultiply.latency
        ) {
          val (real, imag, index) = expected.dequeue()
          withClue(s"pipelined schoolbook vector=$index real") {
            dut.io.productReal.expect(real.S)
          }
          withClue(s"pipelined schoolbook vector=$index imag") {
            dut.io.productImag.expect(imag.S)
          }
        }
      }

      for (((aReal, aImag, bReal, bImag), index) <- vectors.zipWithIndex) {
        dut.io.a.real.poke(aReal.S)
        dut.io.a.imag.poke(aImag.S)
        dut.io.b.real.poke(bReal.S)
        dut.io.b.imag.poke(bImag.S)
        dut.clock.step()
        expected.enqueue(
          (aReal * bReal - aImag * bImag, aReal * bImag + aImag * bReal, index)
        )
        checkReady()
      }
      while (expected.nonEmpty) {
        dut.io.a.real.poke(0.S)
        dut.io.a.imag.poke(0.S)
        dut.io.b.real.poke(0.S)
        dut.io.b.imag.poke(0.S)
        dut.clock.step()
        // Keep the queue at the modeled pipeline depth while flushing the
        // final real vectors; the appended zero products are not checked.
        expected.enqueue((BigInt(0), BigInt(0), -1))
        checkReady()
        if (expected.forall(_._3 == -1)) expected.clear()
      }
    }
  }

  it should "keep the wider two-limb Gauss product exact" in {
    val aWidth = 46
    val bWidth = 29
    val aLimit = BigInt(1) << (aWidth - 1)
    val bLimit = BigInt(1) << (bWidth - 1)
    val aBoundary = Seq(
      -aLimit,
      -aLimit + 1,
      BigInt(-1),
      BigInt(0),
      BigInt(1),
      aLimit - 2,
      aLimit - 1
    )
    val bBoundary = Seq(
      -bLimit,
      -bLimit + 1,
      BigInt(-1),
      BigInt(0),
      BigInt(1),
      bLimit - 2,
      bLimit - 1
    )
    val boundaryVectors = for {
      aReal <- aBoundary
      aImag <- aBoundary
      bReal <- bBoundary
      bImag <- bBoundary
    } yield (aReal, aImag, bReal, bImag)

    val random = new Random(0x46505457494445L)
    def randomSigned(width: Int): BigInt = {
      val bits = BigInt(width, random)
      if (bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
    }
    val randomVectors = Seq.fill(5000)(
      (
        randomSigned(aWidth),
        randomSigned(aWidth),
        randomSigned(bWidth),
        randomSigned(bWidth)
      )
    )

    test(new ExactGaussTwoLimbComplexMultiply(aWidth, bWidth))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      for (((aReal, aImag, bReal, bImag), index) <-
          (boundaryVectors ++ randomVectors).zipWithIndex) {
        dut.io.a.real.poke(aReal.S)
        dut.io.a.imag.poke(aImag.S)
        dut.io.b.real.poke(bReal.S)
        dut.io.b.imag.poke(bImag.S)
        val expectedReal = aReal * bReal - aImag * bImag
        val expectedImag = aReal * bImag + aImag * bReal
        withClue(s"two-limb Gauss vector=$index real") {
          dut.io.productReal.expect(expectedReal.S)
        }
        withClue(s"two-limb Gauss vector=$index imag") {
          dut.io.productImag.expect(expectedImag.S)
        }
      }
    }
  }
}

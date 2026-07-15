package fpt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

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
}

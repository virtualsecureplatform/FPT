package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private class RollingHeadTileHarness(lanes: Int) extends Module {
  val tile = Module(new CoefficientRollingHeadTile(lanes, 24, 2))
  val io = IO(new Bundle {
    val command = Input(UInt(4.W))
    val tail = Input(Vec(2, Vec(lanes, UInt(24.W))))
    val deferred = Input(Vec(lanes, UInt(24.W)))
    val heads = Output(Vec(2, Vec(2, Vec(lanes, UInt(24.W)))))
  })
  tile.io.clock := clock; tile.io.reset := reset.asBool
  tile.io.commandInput := io.command; tile.io.normalTail := io.tail.asUInt
  tile.io.deferredInput := io.deferred.asUInt
  io.heads := tile.io.heads.asTypeOf(io.heads)
}

final class RollingHeadTileSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  for (lanes <- Seq(2, 4)) {
    it should s"preserve $lanes-lane rolling state, repair priority and reset command flushing" in {
      test(new RollingHeadTileHarness(lanes))
        .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
        val random = new scala.util.Random(0x524f4c4cL + lanes)
        val heads = Array.fill[Option[Int]](2, 2, lanes)(None)
        val saved = Array.fill[Option[Int]](lanes)(None)
        var pending = 0
        dut.io.command.poke(0.U)
        dut.io.tail.foreach(_.foreach(_.poke(0.U))); dut.io.deferred.foreach(_.poke(0.U))
        dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
        for (cycle <- 0 until 400) {
          val resetNow = Set(19, 20, 101, 203).contains(cycle)
          val command = Seq(1, 2, 4, 10, 0, 0, 1, 2)(cycle % 8)
          val data = Array.fill(2, lanes)(random.nextInt(1 << 24))
          val deferred = Array.fill(lanes)(random.nextInt(1 << 24))
          dut.io.command.poke(command.U); dut.reset.poke(resetNow.B)
          for (c <- 0 until 2; lane <- 0 until lanes) dut.io.tail(c)(lane).poke(data(c)(lane).U)
          for (lane <- 0 until lanes) dut.io.deferred(lane).poke(deferred(lane).U)
          for (half <- 0 until 2; c <- 0 until 2; lane <- 0 until lanes) {
            if ((pending & (1 << half)) != 0) heads(half)(c)(lane) = Some(data(c)(lane))
          }
          if ((pending & 8) != 0) for (lane <- 0 until lanes) heads(0)(1)(lane) = saved(lane)
          if ((pending & 4) != 0) for (lane <- 0 until lanes) saved(lane) = Some(deferred(lane))
          dut.clock.step()
          for (half <- 0 until 2; c <- 0 until 2; lane <- 0 until lanes) {
            heads(half)(c)(lane).foreach(v => dut.io.heads(half)(c)(lane).expect(v.U))
          }
          pending = if (resetNow) 0 else command
        }
      }
    }
  }
}

package fpt
import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random
import _root_.circt.stage.ChiselStage

private class WindowOrderMiter(n: Int, w: Int, b: Int, o: Int) extends Module {
  val io = IO(new Bundle {
    val seed = Input(UInt(32.W))
    val offset = Input(UInt(log2Ceil(b).W))
    val ring = Input(UInt(log2Ceil(2*n/b).W))
    val enable = Input(Bool())
    val output = Output(Vec(o, UInt(w.W)))
  })
  val reference = Module(new ReferenceWindowSpan(n,w,b,o))
  val candidate = Module(new PipelinedWindowedNegacyclicRotatorSpan(n,w,b,o,msbFirst=true))
  for (i <- 0 until o/b+1; j <- 0 until b) {
    val data = (io.seed + ((i*b+j)*65537L).U)(w-1,0)
    reference.io.input(i)(j) := data
    candidate.io.input(i)(j) := data
  }
  reference.io.offset := io.offset; candidate.io.offset := io.offset
  reference.io.firstRingBlock := io.ring; candidate.io.firstRingBlock := io.ring
  reference.io.enable := io.enable; candidate.io.enable := io.enable
  val valid = RegInit(0.U(4.W))
  when(io.enable) { valid := Cat(valid(2,0),true.B) }
  when(valid.andR) { assert(reference.io.output.asUInt === candidate.io.output.asUInt) }
  io.output := candidate.io.output
}
class WindowShiftOrderSpec extends AnyFlatSpec with ChiselScalatestTester {
  for ((n,w,b,o) <- Seq((16,1,2,2),(64,12,8,16),(1024,24,64,64),(1024,24,64,128))) {
    it should s"match original and oracle n=$n w=$w block=$b output=$o across every offset and ring block" in {
      test(new WindowOrderMiter(n,w,b,o)).withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
        val random = new Random(87431)
        val mask = (BigInt(1)<<w)-1
        val queue = mutable.Queue.empty[Seq[BigInt]]
        var last: Option[Seq[BigInt]] = None
        var checked = 0
        def cycle(ring: Int, offset: Int, seed: BigInt, enabled: Boolean): Unit = {
          dut.io.seed.poke(seed.U); dut.io.ring.poke(ring.U)
          dut.io.offset.poke(offset.U); dut.io.enable.poke(enabled.B)
          if (enabled) {
            queue.enqueue((0 until o).map { lane =>
              val index = offset+lane
              val value = (seed + index*65537L)&mask
              val negative = ((ring+index/b)%(2*n/b)) >= n/b
              if (negative) (-value)&mask else value
            })
          }
          dut.clock.step()
          if (enabled && queue.size >= 4) { last = Some(queue.dequeue()); checked += 1 }
          last.foreach(values => values.zipWithIndex.foreach { case (value,lane) => dut.io.output(lane).expect(value.U) })
        }
        for (ring <- 0 until 2*n/b; offset <- 0 until b) {
          if (random.nextInt(4)==0) cycle(random.nextInt(2*n/b),random.nextInt(b),BigInt(32,random),false)
          val seed = offset%4 match { case 0 => BigInt(0); case 1 => mask; case 2 => BigInt(1)<<(w-1); case _ => BigInt(32,random) }
          cycle(ring,offset,seed,true)
        }
        for (_ <- 0 until 3) cycle(0,0,BigInt(0),true)
        assert(checked == 2*n)
        println(s"WINDOW_ORDER_PASS n=$n w=$w block=$b output=$o checked=$checked latency=4")
      }
    }
  }
}
class WindowSynthesisGate(msb: Boolean) extends Module {
  override def desiredName = "WindowSynthesisGate"
  val io = IO(new Bundle {
    val input = Input(Vec(3,Vec(64,UInt(24.W))))
    val offset = Input(UInt(6.W))
    val ring = Input(UInt(5.W))
    val output = Output(Vec(128,UInt(24.W)))
  })
  if(msb) {
    val dut = Module(new PipelinedWindowedNegacyclicRotatorSpan(1024,24,64,128,msbFirst=true))
    dut.io.input := io.input; dut.io.offset := io.offset; dut.io.firstRingBlock := io.ring
    dut.io.enable := true.B; io.output := dut.io.output
  } else {
    val dut = Module(new ReferenceWindowSpan(1024,24,64,128))
    dut.io.input := io.input; dut.io.offset := io.offset; dut.io.firstRingBlock := io.ring
    dut.io.enable := true.B; io.output := dut.io.output
  }
}
object EmitWindowShiftOrder extends App {
  for ((name,msb) <- Seq(("baseline",false),("candidate",true))) {
    ChiselStage.emitSystemVerilogFile(new WindowSynthesisGate(msb),
      args=Array("--target-dir",args(0)+"/"+name), firtoolOpts=SynthesisEmitter.firtoolOptions)
  }
}

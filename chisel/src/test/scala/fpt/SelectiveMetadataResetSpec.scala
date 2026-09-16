package fpt
import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

private class SelectiveResetHarness(width: Int, mask: BigInt) extends Module {
  val io = IO(new Bundle { val data = Input(UInt(width.W)); val output = Output(UInt(width.W)) })
  val reg = Module(new PhysicalControlRegister(width,Some(mask)))
  reg.io.clock := clock; reg.io.reset := reset.asBool; reg.io.inputData := io.data
  io.output := reg.io.outputData
}
class SelectiveMetadataResetSpec extends AnyFlatSpec with ChiselScalatestTester {
  for ((width,mask) <- Seq((2,BigInt(0)),(12,BigInt(15)),(13,BigInt(255)),(12,BigInt(4095)))) {
    it should s"reset only mask $mask at width $width including back-to-back reset and traffic" in {
      test(new SelectiveResetHarness(width,mask)).withAnnotations(Seq(VerilatorBackendAnnotation,PaperVerilator.flags)) { dut =>
        val random = new scala.util.Random(18273)
        val all = (BigInt(1)<<width)-1
        for(i <- 0 until 200) {
          val reset = i%7 < 2
          val data = if(i%3==0) all else BigInt(width,random)
          dut.reset.poke(reset.B); dut.io.data.poke(data.U); dut.clock.step()
          dut.io.output.expect((if(reset) data & (all ^ mask) else data).U)
        }
      }
    }
  }
}

package fpt

import chisel3._
import chisel3.util._

/** One receiver stage shared by the RAMs for four incoming complex lanes.
  * Memory capacity and read latency are unchanged; writes commit one edge later.
  */
private[fpt] final class KeyWriteTile(depth: Int, groups: Int, width: Int, minimalMetadataReset: Boolean = false)
    extends Module {
  override def desiredName = s"FptKeyWriteTile_${depth}_${groups}_${width}"
  private val addressWidth = TransformUtil.counterWidth(depth)
  val io = IO(new Bundle {
    val writeData = Input(UInt(width.W))
    val writeAddress = Input(UInt(addressWidth.W))
    val writeMask = Input(UInt(groups.W))
    val readAddress = Input(UInt(addressWidth.W))
    val readEnable = Input(Bool())
    val readData = Output(Vec(groups, UInt(width.W)))
  })
  val writePayload = Module(new PhysicalCutRegister(width))
  writePayload.io.clock := clock
  writePayload.io.enable := true.B
  writePayload.io.inputData := io.writeData
  val writeControl = Module(new PhysicalControlRegister(addressWidth + groups,
    if (minimalMetadataReset) Some((BigInt(1) << groups) - 1) else None))
  writeControl.io.clock := clock
  writeControl.io.reset := reset.asBool
  writeControl.io.inputData := Cat(io.writeAddress, io.writeMask)
  val address = writeControl.io.outputData(addressWidth + groups - 1, groups)
  val mask = writeControl.io.outputData(groups - 1, 0)
  val memory = SyncReadMem(depth, Vec(groups, UInt(width.W)))
  when(mask.orR) {
    memory.write(address, VecInit(Seq.fill(groups)(writePayload.io.outputData)),
      (0 until groups).map(mask(_)))
  }
  io.readData := memory.read(io.readAddress, io.readEnable)
}

package fpt

import chisel3._
import chisel3.util._

/** Re-pairs two component frames emitted sequentially by one inverse FTT.
  *
  * Component zero is retained for one frame. Component one then passes
  * through while the matching retained beat is replayed, preserving the
  * coefficient stores' existing two-component update interface.
  */
final class InverseComponentJoin(
    val frameBeats: Int,
    val lanes: Int,
    val dataWidth: Int
) extends Module {
  require(frameBeats >= 1 && isPow2(frameBeats))
  require(lanes >= 1)
  require(dataWidth >= 2)

  private val beatWidth = TransformUtil.counterWidth(frameBeats)

  val io = IO(new Bundle {
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val inputLow = Input(Vec(lanes, SInt(dataWidth.W)))
    val inputHigh = Input(Vec(lanes, SInt(dataWidth.W)))

    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val outputFirst = Output(Bool())
    val outputLast = Output(Bool())
    val outputLow = Output(Vec(2, Vec(lanes, SInt(dataWidth.W))))
    val outputHigh = Output(Vec(2, Vec(lanes, SInt(dataWidth.W))))
  })

  val firstLow = Reg(Vec(frameBeats, Vec(lanes, SInt(dataWidth.W))))
  val firstHigh = Reg(Vec(frameBeats, Vec(lanes, SInt(dataWidth.W))))
  val component = RegInit(0.U(1.W))
  val beat = RegInit(0.U(beatWidth.W))
  val secondComponent = component === 1.U

  io.inputReady := Mux(secondComponent, io.outputReady, true.B)
  io.outputValid := io.inputValid && secondComponent
  io.outputFirst := io.outputValid && beat === 0.U
  io.outputLast := io.outputValid && beat === (frameBeats - 1).U
  for (lane <- 0 until lanes) {
    io.outputLow(0)(lane) := firstLow(beat)(lane)
    io.outputHigh(0)(lane) := firstHigh(beat)(lane)
    io.outputLow(1)(lane) := io.inputLow(lane)
    io.outputHigh(1)(lane) := io.inputHigh(lane)
  }

  val inputFire = io.inputValid && io.inputReady
  when(inputFire && !secondComponent) {
    firstLow(beat) := io.inputLow
    firstHigh(beat) := io.inputHigh
  }
  when(inputFire) {
    when(beat === (frameBeats - 1).U) {
      beat := 0.U
      component := ~component
    }.otherwise {
      beat := beat + 1.U
    }
  }
}

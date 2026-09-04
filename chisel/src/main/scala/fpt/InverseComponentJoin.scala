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
  private val wordWidth = 2 * lanes * dataWidth

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

  val firstComponentMemory = Mem(frameBeats, UInt(wordWidth.W))
  val component = RegInit(0.U(1.W))
  val beat = RegInit(0.U(beatWidth.W))
  val secondComponent = component === 1.U
  val inputWord = Cat(io.inputHigh.asUInt, io.inputLow.asUInt)
  io.inputReady := Mux(secondComponent, io.outputReady, true.B)
  val inputFire = io.inputValid && io.inputReady
  when(inputFire && !secondComponent) {
    firstComponentMemory.write(beat, inputWord)
  }
  when(inputFire) {
    when(beat === (frameBeats - 1).U) {
      beat := 0.U
      component := ~component
    }.otherwise {
      beat := beat + 1.U
    }
  }

  io.outputValid := io.inputValid && secondComponent
  io.outputFirst := io.outputValid && beat === 0.U
  io.outputLast := io.outputValid && beat === (frameBeats - 1).U
  val firstUnpacked = firstComponentMemory(beat).asTypeOf(
    Vec(2, Vec(lanes, SInt(dataWidth.W)))
  )
  val secondUnpacked = inputWord.asTypeOf(
    Vec(2, Vec(lanes, SInt(dataWidth.W)))
  )
  io.outputLow(0) := firstUnpacked(0)
  io.outputHigh(0) := firstUnpacked(1)
  io.outputLow(1) := secondUnpacked(0)
  io.outputHigh(1) := secondUnpacked(1)
}

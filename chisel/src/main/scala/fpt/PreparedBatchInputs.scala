package fpt

import chisel3._
import chisel3.util._

private[fpt] object PreparedInputArithmetic {
  def switched(word: UInt, exponentWidth: Int): UInt = {
    val shift = word.getWidth - exponentWidth
    require(shift > 0 && exponentWidth > 0)
    (word(word.getWidth - 1, shift) +& word(shift - 1).asUInt)(exponentWidth - 1, 0)
  }
  def residual(word: UInt, exponentWidth: Int): UInt = {
    val shift = word.getWidth - exponentWidth
    require(shift > 0 && exponentWidth > 0)
    word(shift - 1, 0).asSInt.pad(word.getWidth).asUInt
  }
}

/** Preparation stays live while the accumulator/output engine is busy. */
final class PreparedInputPort extends Bundle {
  val start = Flipped(Decoupled(Bool()))
  val word = Flipped(Decoupled(UInt(32.W)))
  val prepared = Valid(Bool())
  val activate = Flipped(Decoupled(Bool()))
  val initialized = Output(Bool())
  val release = Flipped(Valid(Bool()))
  val preparationCycles = Output(Vec(2, UInt(32.W)))
}

final class PreparedInputDescriptor(val torusWidth: Int, val exponentWidth: Int) extends Bundle {
  val testVector = UInt(torusWidth.W)
  val exponent = UInt(exponentWidth.W)
}

/** Independent scalar preparation into two small banks. Neither the active
  * exponent bank nor its initialization descriptors can be overwritten.
  * DataMover validation is deliberately owned by the chained frontend.
  */
final class PreparedBatchInputs(
    val contexts: Int, val dimension: Int, val torusWidth: Int = 32,
    val exponentWidth: Int = 11
) extends Module {
  require(contexts > 0 && dimension > 0 && torusWidth > exponentWidth)
  private val cw = math.max(1, log2Ceil(contexts))
  private val aw = math.max(1, log2Ceil(contexts * dimension))
  private val shift = torusWidth - exponentWidth
  private val offset = BigInt(1) << (shift - 1)
  val io = IO(new Bundle {
    val start = Flipped(Decoupled(Bool()))
    val word = Flipped(Decoupled(UInt(torusWidth.W)))
    val prepared = Valid(Bool())
    val activate = Flipped(Decoupled(Bool()))
    val release = Flipped(Valid(Bool()))
    val read = Flipped(Valid(UInt(aw.W)))
    val exponent = Output(UInt(exponentWidth.W))
    val descriptorRequest = Flipped(Valid(UInt(cw.W)))
    val descriptor = Valid(new PreparedInputDescriptor(torusWidth, exponentWidth))
    val preparationCycles = Output(Vec(2, UInt(32.W)))
  })
  val exponents = Seq.fill(2)(SyncReadMem(contexts * dimension, UInt(exponentWidth.W)))
  val descriptors = Seq.fill(2)(SyncReadMem(contexts, new PreparedInputDescriptor(torusWidth, exponentWidth)))
  val occupied = RegInit(VecInit(Seq.fill(2)(false.B)))
  val valid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val preparing = RegInit(false.B)
  val writeBank = RegInit(false.B)
  val active = RegInit(false.B)
  val activeBank = RegInit(false.B)
  val context = RegInit(0.U(cw.W))
  val index = RegInit(0.U(math.max(1, log2Ceil(dimension + 2)).W))
  val address = RegInit(0.U(aw.W))
  val testVector = Reg(UInt(torusWidth.W))
  val correction = RegInit(0.U(torusWidth.W))
  val cycles = RegInit(VecInit(Seq.fill(2)(0.U(32.W))))
  io.preparationCycles := cycles
  io.start.ready := !preparing && !occupied(io.start.bits)
  io.word.ready := preparing
  io.prepared.valid := false.B
  io.prepared.bits := writeBank
  io.activate.ready := !active && valid(io.activate.bits)
  when(io.start.fire) {
    occupied(io.start.bits) := true.B
    valid(io.start.bits) := false.B
    writeBank := io.start.bits
    preparing := true.B
    context := 0.U
    index := 0.U
    address := 0.U
    correction := 0.U
    cycles(io.start.bits) := 0.U
  }
  when(preparing) { cycles(writeBank) := cycles(writeBank) + 1.U }
  when(io.activate.fire) { active := true.B; activeBank := io.activate.bits }
  when(io.release.valid) {
    assert(occupied(io.release.bits), "release of free prepared bank")
    assert(!preparing || io.release.bits =/= writeBank, "release during preparation")
    occupied(io.release.bits) := false.B
    valid(io.release.bits) := false.B
    when(active && io.release.bits === activeBank) { active := false.B }
  }
  // Round only the exponent field. The modular rounding residual is exactly
  // the signed low field: upper word bits cancel, including torus wraparound.
  // Keep the correction recurrence independent of the exponent carry chain.
  val switched = PreparedInputArithmetic.switched(io.word.bits, exponentWidth)
  val residual = PreparedInputArithmetic.residual(io.word.bits, exponentWidth)
  // Match the scalar loader's modular sum and signed truncation toward zero.
  val negative = correction(torusWidth - 1)
  val magnitude = Mux(negative, 0.U(torusWidth.W) - correction, correction)
  val half = Mux(negative, 0.U(torusWidth.W) - (magnitude >> 1), magnitude >> 1)
  val adjusted = (io.word.bits - half +& offset.U)(torusWidth - 1, 0)
  val descriptor = Wire(new PreparedInputDescriptor(torusWidth, exponentWidth))
  descriptor.testVector := testVector
  descriptor.exponent := 0.U(exponentWidth.W) - adjusted(torusWidth - 1, shift)
  when(io.word.fire) {
    assert(!active || writeBank =/= activeBank, "preprocessing overwrote active batch")
    when(index === 0.U) {
      testVector := io.word.bits
      correction := 0.U
      index := 1.U
    }.elsewhen(index <= dimension.U) {
      for (bank <- 0 until 2) {
        when(writeBank === (bank == 1).B) { exponents(bank).write(address, switched) }
      }
      address := address + 1.U
      correction := correction + residual
      index := index + 1.U
    }.otherwise {
      for (bank <- 0 until 2) {
        when(writeBank === (bank == 1).B) { descriptors(bank).write(context, descriptor) }
      }
      index := 0.U
      when(context === (contexts - 1).U) {
        preparing := false.B
        valid(writeBank) := true.B
        io.prepared.valid := true.B
      }.otherwise { context := context + 1.U }
    }
  }
  val readBank = RegEnable(activeBank, io.read.valid)
  val reads = exponents.zipWithIndex.map { case (memory, bank) =>
    memory.read(io.read.bits, io.read.valid && active && activeBank === (bank == 1).B)
  }
  io.exponent := Mux(readBank, reads(1), reads(0))
  val descriptorBank = RegEnable(activeBank, io.descriptorRequest.valid)
  val descriptorReads = descriptors.zipWithIndex.map { case (memory, bank) =>
    memory.read(io.descriptorRequest.bits,
      io.descriptorRequest.valid && active && activeBank === (bank == 1).B)
  }
  io.descriptor.valid := RegNext(io.descriptorRequest.valid && active, false.B)
  io.descriptor.bits := Mux(descriptorBank, descriptorReads(1), descriptorReads(0))
  when(io.read.valid || io.descriptorRequest.valid) { assert(active, "read without active prepared bank") }
}

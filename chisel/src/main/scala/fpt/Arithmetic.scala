package fpt

import chisel3._
import chisel3.util._

final class ComplexSInt(val componentWidth: Int) extends Bundle {
  val real = SInt(componentWidth.W)
  val imag = SInt(componentWidth.W)
}

final class GaussTwiddle(val twiddleWidth: Int) extends Bundle {
  val c = SInt(twiddleWidth.W)
  val cMinusD = SInt(twiddleWidth.W)
  val cPlusD = SInt(twiddleWidth.W)
}

private[fpt] object FixedPointBits {
  def lowSigned(value: SInt, width: Int): SInt =
    value.asUInt(width - 1, 0).asSInt

  def shiftedLowSigned(value: SInt, shift: Int, width: Int): SInt = {
    require(shift >= 0)
    value.asUInt(shift + width - 1, shift).asSInt
  }
}

/** Equation (6) from the FPT paper, with wrap and product truncation matching
  * the C++ reference. This is combinational so a caller can place registers at
  * architecture-specific boundaries.
  */
final class GaussMultiply(
    val dataWidth: Int,
    val twiddleWidth: Int,
    val twiddleFractionalBits: Int
) extends Module {
  require(dataWidth >= 2)
  require(twiddleWidth >= 2)
  require(twiddleFractionalBits >= 0)
  require(twiddleFractionalBits + dataWidth <= dataWidth + twiddleWidth)

  val io = IO(new Bundle {
    val value = Input(new ComplexSInt(dataWidth))
    val twiddle = Input(new GaussTwiddle(twiddleWidth))
    val result = Output(new ComplexSInt(dataWidth))
  })

  val aMinusB = Wire(SInt(dataWidth.W))
  aMinusB := io.value.real - io.value.imag

  val productZ = aMinusB * io.twiddle.c
  val productX = io.value.imag * io.twiddle.cMinusD
  val productY = io.value.real * io.twiddle.cPlusD

  val z = FixedPointBits.shiftedLowSigned(
    productZ, twiddleFractionalBits, dataWidth
  )
  val xPart = FixedPointBits.shiftedLowSigned(
    productX, twiddleFractionalBits, dataWidth
  )
  val yPart = FixedPointBits.shiftedLowSigned(
    productY, twiddleFractionalBits, dataWidth
  )

  io.result.real := FixedPointBits.lowSigned(xPart + z, dataWidth)
  io.result.imag := FixedPointBits.lowSigned(yPart - z, dataWidth)
}

/** Registered radix-2 butterfly. The scale control performs an arithmetic
  * divide-by-two after the widened add/subtract and before wrapping.
  */
final class FptButterfly(
    val dataWidth: Int,
    val twiddleWidth: Int,
    val twiddleFractionalBits: Int
) extends Module {
  val io = IO(new Bundle {
    val validIn = Input(Bool())
    val scale = Input(Bool())
    val even = Input(new ComplexSInt(dataWidth))
    val odd = Input(new ComplexSInt(dataWidth))
    val twiddle = Input(new GaussTwiddle(twiddleWidth))
    val validOut = Output(Bool())
    val upper = Output(new ComplexSInt(dataWidth))
    val lower = Output(new ComplexSInt(dataWidth))
  })

  val multiplier = Module(
    new GaussMultiply(dataWidth, twiddleWidth, twiddleFractionalBits)
  )
  multiplier.io.value := io.odd
  multiplier.io.twiddle := io.twiddle

  def combine(lhs: SInt, rhs: SInt, subtract: Boolean): SInt = {
    val wide = if (subtract) lhs -& rhs else lhs +& rhs
    val selected = Mux(io.scale, (wide >> 1).asSInt, wide)
    FixedPointBits.lowSigned(selected, dataWidth)
  }

  val upperReal = combine(io.even.real, multiplier.io.result.real, false)
  val upperImag = combine(io.even.imag, multiplier.io.result.imag, false)
  val lowerReal = combine(io.even.real, multiplier.io.result.real, true)
  val lowerImag = combine(io.even.imag, multiplier.io.result.imag, true)

  io.validOut := RegNext(io.validIn, false.B)
  val upperReg = Reg(new ComplexSInt(dataWidth))
  val lowerReg = Reg(new ComplexSInt(dataWidth))
  when(io.validIn) {
    upperReg.real := upperReal
    upperReg.imag := upperImag
    lowerReg.real := lowerReal
    lowerReg.imag := lowerImag
  }
  io.upper := upperReg
  io.lower := lowerReg
}

/** Mixed-format complex MAC for the frequency-domain External Product. Four
  * real products are combined at full precision, requantized once, and added
  * to a wrapped accumulator.
  */
final class ComplexMac(
    val aWidth: Int,
    val aFractionalBits: Int,
    val bWidth: Int,
    val bFractionalBits: Int,
    val accumulatorWidth: Int,
    val accumulatorFractionalBits: Int
) extends Module {
  private val productFractionalBits = aFractionalBits + bFractionalBits
  private val shift = productFractionalBits - accumulatorFractionalBits
  require(shift >= 0)

  val io = IO(new Bundle {
    val validIn = Input(Bool())
    val a = Input(new ComplexSInt(aWidth))
    val b = Input(new ComplexSInt(bWidth))
    val accumulator = Input(new ComplexSInt(accumulatorWidth))
    val validOut = Output(Bool())
    val result = Output(new ComplexSInt(accumulatorWidth))
  })

  val ac = io.a.real * io.b.real
  val bd = io.a.imag * io.b.imag
  val ad = io.a.real * io.b.imag
  val bc = io.a.imag * io.b.real
  val productReal = ac -& bd
  val productImag = ad +& bc
  val quantizedReal = FixedPointBits.shiftedLowSigned(
    productReal, shift, accumulatorWidth
  )
  val quantizedImag = FixedPointBits.shiftedLowSigned(
    productImag, shift, accumulatorWidth
  )
  val accumulatedReal = FixedPointBits.lowSigned(
    io.accumulator.real + quantizedReal, accumulatorWidth
  )
  val accumulatedImag = FixedPointBits.lowSigned(
    io.accumulator.imag + quantizedImag, accumulatorWidth
  )

  io.validOut := RegNext(io.validIn, false.B)
  val resultReg = Reg(new ComplexSInt(accumulatorWidth))
  when(io.validIn) {
    resultReg.real := accumulatedReal
    resultReg.imag := accumulatedImag
  }
  io.result := resultReg
}

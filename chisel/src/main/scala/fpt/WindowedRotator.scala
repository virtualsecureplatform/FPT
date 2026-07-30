package fpt

import chisel3._
import chisel3.util._

/** One stream-width window of a negacyclic rotation.
  *
  * `X^a * P` restricted to output indices `[base, base + lanes)` only ever
  * reads a contiguous `lanes`-span of the virtual `2N` anti-periodic ring, so
  * the full-width barrel is unnecessary: the span covers at most two aligned
  * `lanes`-blocks of that ring. Callers supply those two blocks (selected
  * upstream from storage at block granularity) together with the intra-block
  * offset and the ring position of the first block; this module aligns the
  * pair and applies the per-lane negacyclic signs.
  *
  * With `N = 1024` and 128 lanes this replaces a ~283K-LUT combinational
  * full-width rotator with a 7-level, 128-lane extractor per window, which is
  * what lets the Set-II accelerator place on a three-SLR U280.
  */
final class WindowedNegacyclicRotatorWindow(
    val polynomialSize: Int,
    val coefficientWidth: Int,
    val lanes: Int
) extends Module {
  require(polynomialSize >= 2 && isPow2(polynomialSize))
  require(lanes >= 1 && isPow2(lanes) && lanes < polynomialSize)
  private val offsetWidth = log2Ceil(lanes)
  private val ringBlocks = 2 * polynomialSize / lanes
  private val ringBlockWidth = log2Ceil(ringBlocks)

  val io = IO(new Bundle {
    /** Ring block containing the first source lane. */
    val firstBlock = Input(Vec(lanes, UInt(coefficientWidth.W)))
    /** Following ring block (wrapping). */
    val secondBlock = Input(Vec(lanes, UInt(coefficientWidth.W)))
    /** Source offset of the window inside `firstBlock`. */
    val offset = Input(UInt(offsetWidth.W))
    /** Ring index of `firstBlock`; the top bit is the negacyclic sign. */
    val firstRingBlock = Input(UInt(ringBlockWidth.W))
    val output = Output(Vec(lanes, UInt(coefficientWidth.W)))
  })

  private def negate(value: UInt): UInt =
    (0.U((coefficientWidth + 1).W) - value)(coefficientWidth - 1, 0)

  private val secondRingBlock = io.firstRingBlock + 1.U
  private val firstSign = io.firstRingBlock(ringBlockWidth - 1)
  private val secondSign = secondRingBlock(ringBlockWidth - 1)
  private val signedFirst = VecInit(io.firstBlock.map { value =>
    Mux(firstSign, negate(value), value)
  })
  private val signedSecond = VecInit(io.secondBlock.map { value =>
    Mux(secondSign, negate(value), value)
  })

  // Rotate the concatenated pair left by `offset` and keep the low window.
  private var window: Seq[UInt] =
    signedFirst.toSeq ++ signedSecond.toSeq
  for (bit <- 0 until offsetWidth) {
    val shift = 1 << bit
    val previous = window
    window = (0 until 2 * lanes).map { index =>
      val source = (index + shift) % (2 * lanes)
      Mux(io.offset(bit), previous(source), previous(index))
    }
  }
  for (lane <- 0 until lanes) {
    io.output(lane) := window(lane)
  }
}

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

/** Bank-width, deeply pipelined window of a negacyclic rotation.
  *
  * The prefetched accumulator is physically organized at `blockLanes`
  * lanes, while the forward transform can consume several such blocks per
  * cycle. A requested `outputLanes` span therefore needs only the selected
  * starting block plus enough following blocks to cover the intra-block
  * offset. Keeping that physical bank width here avoids building a barrel
  * over a pair of full forward-width beats.
  *
  * The alignment network is cut after every two mux layers and padded to
  * three alignment stages before sign correction. Together with the caller's
  * narrow metadata register, registered block selector, and registered
  * decomposition output, this gives the large U280 implementation seven
  * independently placeable stages without changing one-beat-per-cycle
  * throughput.
  */
final class PipelinedWindowedNegacyclicRotatorSpan(
    val polynomialSize: Int,
    val coefficientWidth: Int,
    val blockLanes: Int,
    val outputLanes: Int
) extends Module {
  require(polynomialSize >= 2 && isPow2(polynomialSize))
  require(blockLanes >= 2 && isPow2(blockLanes))
  require(outputLanes >= blockLanes && isPow2(outputLanes))
  require(outputLanes % blockLanes == 0)
  require(polynomialSize % blockLanes == 0)

  private val offsetWidth = log2Ceil(blockLanes)
  private val ringBlocks = 2 * polynomialSize / blockLanes
  private val ringBlockWidth = log2Ceil(ringBlocks)
  private val spanBlocks = outputLanes / blockLanes + 1
  private val alignmentPipelineStages = 3

  /** Registered layers internal to this module. */
  val latency: Int = PipelinedWindowedNegacyclicRotatorSpan.latency

  val io = IO(new Bundle {
    val input = Input(
      Vec(spanBlocks, Vec(blockLanes, UInt(coefficientWidth.W)))
    )
    val offset = Input(UInt(offsetWidth.W))
    val firstRingBlock = Input(UInt(ringBlockWidth.W))
    val enable = Input(Bool())
    val output = Output(Vec(outputLanes, UInt(coefficientWidth.W)))
  })

  private def negate(value: UInt): UInt =
    (0.U((coefficientWidth + 1).W) - value)(coefficientWidth - 1, 0)

  val flattened = io.input.flatMap(_.toSeq)
  val sourceSigns = (0 until spanBlocks).flatMap { block =>
    val ringBlock = (io.firstRingBlock + block.U)(ringBlockWidth - 1, 0)
    Seq.fill(blockLanes)(ringBlock(ringBlockWidth - 1))
  }

  var values: Seq[UInt] = flattened
  var signs: Seq[Bool] = sourceSigns
  var stagedOffset: UInt = io.offset
  var completedAlignmentStages = 0
  for (bit <- 0 until offsetWidth) {
    val shift = 1 << bit
    // After consuming bits [bit:0], only the maximum shift represented by
    // the remaining upper bits has to stay live beyond the output window.
    val nextSize = outputLanes + blockLanes - (1 << (bit + 1))
    val previousValues = values
    val previousSigns = signs
    values = (0 until nextSize).map { index =>
      Mux(
        stagedOffset(bit),
        previousValues(index + shift),
        previousValues(index)
      )
    }
    signs = (0 until nextSize).map { index =>
      Mux(
        stagedOffset(bit),
        previousSigns(index + shift),
        previousSigns(index)
      )
    }
    if ((bit + 1) % 2 == 0 || bit + 1 == offsetWidth) {
      values = values.map(value => RegEnable(value, io.enable))
      signs = signs.map(sign => RegEnable(sign, io.enable))
      if (bit + 1 < offsetWidth) {
        stagedOffset = RegEnable(stagedOffset, io.enable)
      }
      completedAlignmentStages += 1
    }
  }

  require(
    completedAlignmentStages <= alignmentPipelineStages,
    "windowed rotator needs more than three two-mux alignment stages"
  )
  for (_ <- completedAlignmentStages until alignmentPipelineStages) {
    values = values.map(value => RegEnable(value, io.enable))
    signs = signs.map(sign => RegEnable(sign, io.enable))
  }

  // Isolate two's-complement correction from the final alignment register.
  for (lane <- 0 until outputLanes) {
    io.output(lane) := RegEnable(
      Mux(signs(lane), negate(values(lane)), values(lane)),
      io.enable
    )
  }
}

object PipelinedWindowedNegacyclicRotatorSpan {
  val latency: Int = 4
}

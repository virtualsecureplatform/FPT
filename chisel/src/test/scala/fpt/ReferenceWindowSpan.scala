package fpt
import chisel3._
import chisel3.util._
final class ReferenceWindowSpan(
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

package fpt

import chisel3._
import chisel3.util._

/** U280 External Product accumulator with pipeline feedback.
  *
  * Equal beats in adjacent gadget rows are four cycles apart, exactly matching
  * the product and commit pipeline.  A committed partial sum therefore feeds
  * the following row directly; memory is needed only for the final image.  One
  * full-width, four-deep UltraRAM image replaces the rotating three-bank store.
  */
final class RotatingThreeBankExternalProductAccumulator(
    val config: ExternalProductConfig,
    val tagWidth: Int,
    val finalBankTileLanes: Int = 0,
    val rowControlTileLanes: Int = 0
) extends Module {
  import TransformUtil._

  require(tagWidth >= 1)
  require(Set(0, 6).contains(finalBankTileLanes))
  require(Set(0, 6).contains(rowControlTileLanes))
  require(rowControlTileLanes == 0 || finalBankTileLanes == rowControlTileLanes)
  require(finalBankTileLanes == 0 || (config.outputLanes >= 2 && config.outputLanes % 2 == 0))
  require(config.rows == 4)
  require(config.inputFrameBeats == 4)
  require(config.outputGroupsPerInputBeat == 2)
  require(config.outputComponents == 2)
  require(config.outputFrameBeats == 8)
  require(
    config.multiplier == ExternalProductMultiplier.ExactPipelinedSchoolbookDsp ||
      config.multiplier ==
        ExternalProductMultiplier.ExactPipelinedQuantizedGaussDsp
  )

  private val rowWidth = counterWidth(config.rows)
  private val inputBeatWidth = counterWidth(config.inputFrameBeats)
  private val outputBeatWidth = counterWidth(config.outputFrameBeats)
  private val outputComponentWidth = counterWidth(config.outputComponents)
  private val pointWidth = counterWidth(config.points)

  val io = IO(new Bundle {
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val inputFirst = Input(Bool())
    val inputTag = Input(UInt(tagWidth.W))
    val decomposition = Input(
      Vec(config.inputLanes, new ComplexSInt(config.spectrum.width))
    )
    val bootstrappingKey = Input(
      Vec(
        config.outputComponents,
        Vec(config.inputLanes, new ComplexSInt(config.bootstrappingKey.width))
      )
    )
    val keyRow = Output(UInt(rowWidth.W))
    val pointIndex = Output(Vec(config.inputLanes, UInt(pointWidth.W)))

    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val outputStart = Output(Bool())
    val outputFirst = Output(Bool())
    val outputLast = Output(Bool())
    val outputComponent = Output(UInt(outputComponentWidth.W))
    val outputTag = Output(UInt(tagWidth.W))
    val output = Output(
      Vec(
        config.outputComponents,
        Vec(config.outputLanes, new ComplexSInt(config.accumulator.width))
      )
    )
    val serializedOutput = Output(
      Vec(config.outputLanes, new ComplexSInt(config.accumulator.width))
    )
    val done = Output(Bool())
    val doneTag = Output(UInt(tagWidth.W))
    val busy = Output(Bool())
  })

  private def halfWordType =
    Vec(
      config.outputComponents,
      Vec(config.outputLanes, new ComplexSInt(config.accumulator.width))
    )
  private val halfWordWidth = config.outputComponents * config.outputLanes *
    2 * config.accumulator.width
  private val componentWidth = config.outputLanes * 2 * config.accumulator.width

  private val transactionTag = Reg(UInt(tagWidth.W))
  private val inputActive = RegInit(false.B)
  private val row = Reg(UInt(rowWidth.W))
  private val inputBeat = Reg(UInt(inputBeatWidth.W))
  io.inputReady := true.B
  private val inputFire = io.inputValid
  private val activeRow = Mux(inputActive, row, 0.U)
  private val activeInputBeat = Mux(inputActive, inputBeat, 0.U)
  private val activeTag = Mux(io.inputFirst, io.inputTag, transactionTag)

  io.keyRow := activeRow
  for (lane <- 0 until config.inputLanes) {
    io.pointIndex(lane) := indexedAddress(
      activeInputBeat,
      config.inputLanes,
      lane,
      pointWidth
    )
  }
  when(io.inputValid) {
    assert(
      inputActive || io.inputFirst,
      "first feedback External Product beat must carry inputFirst"
    )
  }
  when(io.inputFirst && io.inputValid) {
    assert(!inputActive, "inputFirst asserted inside a transaction")
    transactionTag := io.inputTag
  }
  when(inputActive) {
    assert(
      io.inputValid,
      "feedback External Product requires a bubble-free 16-beat input"
    )
  }
  when(inputFire) {
    when(activeInputBeat === (config.inputFrameBeats - 1).U) {
      inputBeat := 0.U
      when(activeRow === (config.rows - 1).U) {
        row := 0.U
        inputActive := false.B
      }.otherwise {
        row := activeRow + 1.U
        inputActive := true.B
      }
    }.otherwise {
      inputBeat := activeInputBeat + 1.U
      row := activeRow
      inputActive := true.B
    }
  }

  private val productWord = Wire(Vec(2, halfWordType))
  for (group <- 0 until 2) {
    for (component <- 0 until config.outputComponents) {
      for (lane <- 0 until config.outputLanes) {
        val inputLane = group * config.outputLanes + lane
        if (
          config.multiplier ==
            ExternalProductMultiplier.ExactPipelinedQuantizedGaussDsp
        ) {
          val multiply = Module(
            new PipelinedQuantizedGaussComplexMultiply(
              config.spectrum.width,
              config.bootstrappingKey.width,
              config.productShift,
              config.accumulator.width
            )
          )
          multiply.io.a := io.decomposition(inputLane)
          multiply.io.b := io.bootstrappingKey(component)(inputLane)
          productWord(group)(component)(lane).real := multiply.io.productReal
          productWord(group)(component)(lane).imag := multiply.io.productImag
        } else {
          val multiply = Module(
            new PipelinedExactSchoolbookComplexMultiply(
              config.spectrum.width,
              config.bootstrappingKey.width
            )
          )
          multiply.io.a := io.decomposition(inputLane)
          multiply.io.b := io.bootstrappingKey(component)(inputLane)
          productWord(group)(component)(lane).real :=
            FixedPointBits.shiftedLowSigned(
              multiply.io.productReal,
              config.productShift,
              config.accumulator.width
            )
          productWord(group)(component)(lane).imag :=
            FixedPointBits.shiftedLowSigned(
              multiply.io.productImag,
              config.productShift,
              config.accumulator.width
            )
        }
      }
    }
  }

  private val firstPendingValid = RegNext(inputFire, false.B)
  private val firstPendingRow = RegEnable(activeRow, inputFire)
  private val firstPendingBeat = RegEnable(activeInputBeat, inputFire)
  private val firstPendingTag = RegEnable(activeTag, inputFire)
  private val remainingProductCycles =
    PipelinedExactSchoolbookComplexMultiply.latency - 1
  private val pendingValid = ShiftRegister(
    firstPendingValid,
    remainingProductCycles,
    false.B,
    true.B
  )
  private val pendingRow = ShiftRegister(firstPendingRow, remainingProductCycles)
  private val pendingBeat = ShiftRegister(firstPendingBeat, remainingProductCycles)
  private val pendingTag = ShiftRegister(firstPendingTag, remainingProductCycles)

  // Branch at all three existing product-alignment edges. Replicating only
  // the last edge leaves a single predecessor driving the whole accumulator.
  private def rowCut(name: String, in: UInt, enable: Bool = true.B): UInt = {
    val cut = Module(new PhysicalRowControlRegister(in.getWidth))
    cut.suggestName(name)
    cut.io.clock := clock
    cut.io.enable := enable
    cut.io.inputData := in
    cut.io.outputData
  }
  private val rowTiles = if (rowControlTileLanes == 0) 0 else
    (config.outputLanes + rowControlTileLanes - 1) / rowControlTileLanes
  private val localRows = if (rowControlTileLanes == 0) None else {
    require(PipelinedExactSchoolbookComplexMultiply.latency == 3)
    val flags = Cat(activeRow === (config.rows - 1).U, activeRow === 0.U)
    Some(Seq.tabulate(2, config.outputComponents) { (group, component) =>
      val root = rowCut(s"rowRoot_${group}_${component}", flags, inputFire)
      val regions = (0 until (rowTiles + 3) / 4).map { region =>
        rowCut(s"rowRegion_${group}_${component}_$region", root)
      }
      (0 until rowTiles).map { tile =>
        val region = regions(tile / 4)
        // One first-row selector per three lanes; last-row still serves the
        // original six-lane bank. Both copies use the same alignment edge.
        rowCut(s"rowLeaf_${group}_${component}_$tile", Cat(region(0), region))
      }
    })
  }
  private def pendingFirst(group: Int, component: Int, lane: Int): Bool =
    localRows.map(_(group)(component)(lane / rowControlTileLanes)(
      if (lane % rowControlTileLanes < 3) 0 else 2))
      .getOrElse(pendingRow === 0.U)
  private def pendingLast(group: Int, component: Int, tile: Int): Bool =
    localRows.map(_(group)(component)(tile)(1))
      .getOrElse(pendingRow === (config.rows - 1).U)

  private val writeValid = RegNext(pendingValid, false.B)
  private val writeRow = RegEnable(pendingRow, pendingValid)
  private val writeBeat = RegEnable(pendingBeat, pendingValid)
  private val writeTag = RegEnable(pendingTag, pendingValid)
  private val writeLast = RegEnable(pendingLast(0, 0, 0), pendingValid)

  // A committed beat appears in the same cycle that the corresponding beat
  // of the following row enters.  Delay it through the same alignment stages
  // previously used after the synchronous-memory response.
  private val feedbackWord = Wire(Vec(2, halfWordType))
  private val committedWord = Wire(Vec(2, halfWordType))
  private val accumulatedWord = Wire(Vec(2, halfWordType))
  private val pendingPreviousWord = ShiftRegister(
    feedbackWord,
    PipelinedExactSchoolbookComplexMultiply.latency
  )
  for (group <- 0 until 2) {
    for (component <- 0 until config.outputComponents) {
      for (lane <- 0 until config.outputLanes) {
        val previous = pendingPreviousWord(group)(component)(lane)
        val product = productWord(group)(component)(lane)
        accumulatedWord(group)(component)(lane).real :=
          FixedPointBits.lowSigned(
            Mux(
              pendingFirst(group, component, lane),
              0.S(config.accumulator.width.W),
              previous.real
            ) + product.real,
            config.accumulator.width
          )
        accumulatedWord(group)(component)(lane).imag :=
          FixedPointBits.lowSigned(
            Mux(
              pendingFirst(group, component, lane),
              0.S(config.accumulator.width.W),
              previous.imag
            ) + product.imag,
            config.accumulator.width
          )
      }
    }
    val commit = Module(new PhysicalCutRegister(halfWordWidth))
    commit.io.clock := clock
    commit.io.enable := true.B
    commit.io.inputData := accumulatedWord(group).asUInt
    committedWord(group) := commit.io.outputData.asTypeOf(halfWordType)
    feedbackWord(group) := committedWord(group)
  }

  when(inputFire && activeRow =/= 0.U) {
    assert(writeValid, "missing prior-row feedback word")
    assert(writeBeat === activeInputBeat, "feedback beat is misaligned")
    assert(writeRow + 1.U === activeRow, "feedback row is misaligned")
  }

  private val finalWrite = writeValid && (if (rowControlTileLanes == 0)
    writeRow === (config.rows - 1).U else writeLast)

  // The first read starts one cycle after final beat zero is committed.  A
  // new transaction's final beat zero arrives with the preceding transaction's
  // last read, whose address is three, so the single memory remains collision
  // free at II=16.
  private val finalCommitStart = finalWrite && writeBeat === 0.U
  private val drainActive = RegInit(false.B)
  private val drainIssueIndex = RegInit(0.U(4.W))
  private val drainTag = Reg(UInt(tagWidth.W))
  private val drainReadIssue = drainActive
  private val drainReadAddress = drainIssueIndex(2, 1)
  // Four independently placeable component banks replace the monolithic
  // 15,360-bit UltraRAM word. Group zero remains in UltraRAM; group one uses
  // shallow distributed storage, relieving the SLR1 URAM column pressure.
  private val finalBankOutputs = if (finalBankTileLanes == 0) {
    val finalBanks = Seq.tabulate(2, config.outputComponents) {
    (group, component) =>
      val bank = if (group == 0) {
        Module(new FinalAccumulatorUltraBank(componentWidth))
      } else {
        Module(new FinalAccumulatorDistributedBank(componentWidth))
      }
      bank.io.clock := clock
      bank.io.writeEnable := finalWrite
      bank.io.writeAddress := writeBeat
      bank.io.inputData := committedWord(group)(component).asUInt
      bank.io.readEnable := drainReadIssue
      bank.io.readAddress := drainReadAddress
      bank
    }
    finalBanks.map(_.map(_.io.outputData))
  } else {
    // Mirror the next values of the existing drain state, not its current
    // outputs: the bank-local registers replace that edge, not add an edge.
    val nextDrainActive = Mux(drainActive,
      Mux(drainIssueIndex === 15.U, finalCommitStart, true.B), finalCommitStart)
    val nextDrainIndex = Mux(drainActive && drainIssueIndex =/= 15.U,
      drainIssueIndex + 1.U, 0.U(4.W))
    val pendingFinalWrite = pendingValid && pendingRow === (config.rows - 1).U
    val laneStarts = 0 until config.outputLanes by finalBankTileLanes
    val finalBankTiles = Seq.tabulate(2, config.outputComponents) { (group, component) =>
      laneStarts.map { firstLane =>
        val lanes = math.min(finalBankTileLanes, config.outputLanes - firstLane)
        val width = lanes * 2 * config.accumulator.width
        val offset = firstLane * 2 * config.accumulator.width
        val bank = Module(new FinalAccumulatorLocalBank(width, ultra = group == 0))
        bank.suggestName(s"finalBankTiles_${group}_${component}_${firstLane / finalBankTileLanes}")
        bank.io.clock := clock
        bank.io.reset := reset.asBool
        bank.io.pendingWrite := (if (rowControlTileLanes == 0) pendingFinalWrite
          else pendingValid && pendingLast(group, component, firstLane / rowControlTileLanes))
        bank.io.pendingWriteAddress := pendingBeat
        bank.io.inputData := committedWord(group)(component).asUInt(offset + width - 1, offset)
        bank.io.nextReadEnable := nextDrainActive
        bank.io.nextReadAddress := nextDrainIndex(2, 1)
        bank
      }
    }
    finalBankTiles.map(_.map(banks => Cat(banks.reverse.map(_.io.outputData))))
  }

  when(drainActive) {
    when(drainIssueIndex === 15.U) {
      when(finalCommitStart) {
        drainActive := true.B
        drainIssueIndex := 0.U
        drainTag := writeTag
      }.otherwise {
        drainActive := false.B
        drainIssueIndex := 0.U
      }
    }.otherwise {
      assert(
        !finalCommitStart,
        "next final image arrived before the 16-cycle drain boundary"
      )
      drainIssueIndex := drainIssueIndex + 1.U
    }
  }.elsewhen(finalCommitStart) {
    drainActive := true.B
    drainIssueIndex := 0.U
    drainTag := writeTag
  }
  when(finalCommitStart && drainActive) {
    assert(
      drainIssueIndex === 15.U,
      "back-to-back final images are not separated by 16 cycles"
    )
  }
  when(finalWrite && drainReadIssue) {
    assert(
      writeBeat =/= drainReadAddress,
      "final accumulator memory read/write address collision"
    )
  }

  private val drainReadResponse = RegNext(drainReadIssue, false.B)
  private val drainResponseIndex = RegEnable(drainIssueIndex, drainReadIssue)
  private val drainResponseTag = RegEnable(drainTag, drainReadIssue)
  private val tileLanes = math.min(2, config.outputLanes)
  require(config.outputLanes % tileLanes == 0)
  private val tiles = config.outputLanes / tileLanes
  private val selectedTileWidth = tileLanes * 2 * config.accumulator.width
  private val selectedTiles = (0 until tiles).map { tile =>
    val select = Module(new PhysicalFourWaySelect(selectedTileWidth))
    select.io.clock := clock
    select.io.enable := drainReadIssue
    select.io.selectorInput := Cat(drainIssueIndex(3), drainIssueIndex(0))
    val tileOffset = tile * selectedTileWidth
    def slice(group: Int, component: Int): UInt =
      finalBankOutputs(group)(component)(
        tileOffset + selectedTileWidth - 1,
        tileOffset
      )
    select.io.input0 := slice(0, 0)
    select.io.input1 := slice(1, 0)
    select.io.input2 := slice(0, 1)
    select.io.input3 := slice(1, 1)
    select.io.outputData
  }
  private val serializedOutput = Cat(selectedTiles.reverse).asTypeOf(
    Vec(config.outputLanes, new ComplexSInt(config.accumulator.width))
  )
  io.serializedOutput := serializedOutput

  io.outputValid := drainReadResponse
  io.outputStart := drainReadIssue &&
    (drainIssueIndex === 0.U || drainIssueIndex === 8.U)
  io.outputFirst := drainReadResponse && drainResponseIndex === 0.U
  io.outputLast := drainReadResponse && drainResponseIndex === 15.U
  io.outputComponent := drainResponseIndex(3)
  io.outputTag := drainResponseTag
  for (component <- 0 until config.outputComponents) {
    io.output(component) := 0.U.asTypeOf(io.output(component))
    when(drainReadResponse && drainResponseIndex(3) === component.U) {
      io.output(component) := serializedOutput
    }
  }
  when(io.outputValid) {
    assert(io.outputReady, "feedback accumulator output cannot be backpressured")
  }

  io.done := io.outputValid && io.outputReady && io.outputLast
  io.doneTag := drainResponseTag
  io.busy := inputActive || pendingValid || writeValid || drainActive ||
    drainReadResponse
}

package fpt

import chisel3._
import chisel3.util._

/** U280-specific 1.5-wide-bank External Product accumulator.
  *
  * A logical accumulator word is divided at the two 64-lane inverse-output
  * groups.  Three 4-deep half-width memories replace two 4-deep full-width
  * ping-pong memories.  Partial sums migrate on row commits according to a
  * periodic placement that supplies at most one read and one write to every
  * physical memory per cycle.  Both TRLWE components are drained together;
  * component one is retained in a narrow/deep memory while component zero is
  * sent to the serialized inverse transform.
  */
final class RotatingThreeBankExternalProductAccumulator(
    val config: ExternalProductConfig,
    val tagWidth: Int
) extends Module {
  import TransformUtil._

  require(tagWidth >= 1)
  require(config.rows == 4)
  require(config.inputFrameBeats == 4)
  require(config.outputGroupsPerInputBeat == 2)
  require(config.outputComponents == 2)
  require(config.outputFrameBeats == 8)
  require(
    config.multiplier ==
      ExternalProductMultiplier.ExactPipelinedSchoolbookDsp ||
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
        Vec(
          config.inputLanes,
          new ComplexSInt(config.bootstrappingKey.width)
        )
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
  private val componentWordWidth = config.outputLanes * 2 *
    config.accumulator.width
  private val physicalBankCount = 3
  private val physicalSlotWidth = 4

  // A slot is Cat(bank[1:0], address[1:0]).  The placement returns to
  // finalPlacement after every transaction, making the schedule periodic.
  private val finalPlacement = VecInit(
    Seq(8, 4, 0, 5, 9, 1, 6, 2).map(_.U(physicalSlotWidth.W))
  )
  private val rowZeroPlacement = VecInit(
    Seq(0, 4, 5, 8, 1, 9, 6, 10).map(_.U(physicalSlotWidth.W))
  )
  private val rowOnePlacement = VecInit(
    Seq(0, 4, 2, 5, 1, 7, 3, 6).map(_.U(physicalSlotWidth.W))
  )

  private def slotBank(slot: UInt): UInt = slot(3, 2)
  private def slotAddress(slot: UInt): UInt = slot(1, 0)
  private def logicalHalf(beat: UInt, group: Int): UInt =
    Cat(beat, group.U(1.W))

  // Harmless padding gives each physical bank a distinct inferred-memory
  // module.  The U280 emitter maps banks zero and one to URAM and leaves bank
  // two in LUTRAM; using one shared module would force all three to the same
  // primitive and either overflow URAM or retain the original LUT pressure.
  private val accumulatorMemoryWidths =
    Seq(halfWordWidth + 1, halfWordWidth + 2, halfWordWidth)
  private val accumulatorMemories = accumulatorMemoryWidths.map { width =>
    SyncReadMem(4, UInt(width.W), SyncReadMem.WriteFirst)
  }
  private val componentOneMemory = SyncReadMem(
    config.outputFrameBeats,
    UInt(componentWordWidth.W),
    SyncReadMem.WriteFirst
  )

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
      "first rotating External Product beat must carry inputFirst"
    )
  }
  when(io.inputFirst && io.inputValid) {
    assert(!inputActive, "inputFirst asserted inside a transaction")
    transactionTag := io.inputTag
  }
  when(inputActive) {
    assert(
      io.inputValid,
      "rotating External Product requires a bubble-free 16-beat input"
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
  private val firstPendingBeat = RegEnable(
    activeInputBeat,
    inputFire
  )
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

  // Metadata and the physical data cut advance together from the adder output
  // to the memory write ports.
  private val writeValid = RegNext(pendingValid, false.B)
  private val writeRow = RegEnable(pendingRow, pendingValid)
  private val writeBeat = RegEnable(
    pendingBeat,
    pendingValid
  )
  private val writeTag = RegEnable(pendingTag, pendingValid)

  private val priorPlacement = Wire(chiselTypeOf(finalPlacement))
  priorPlacement := MuxLookup(activeRow, finalPlacement)(
    Seq(
      1.U -> rowZeroPlacement,
      2.U -> rowOnePlacement,
      3.U -> finalPlacement
    )
  )
  private val inputReadEnable = inputFire && activeRow =/= 0.U
  private val inputReadSlots = Wire(Vec(2, UInt(physicalSlotWidth.W)))
  for (group <- 0 until 2) {
    inputReadSlots(group) := priorPlacement(
      logicalHalf(activeInputBeat, group)
    )
  }

  private val drainActive = RegInit(false.B)
  private val drainIssueBeat = Reg(UInt(outputBeatWidth.W))
  private val drainTag = Reg(UInt(tagWidth.W))
  private val finalCommitStart = writeValid &&
    writeRow === (config.rows - 1).U && writeBeat === 0.U
  private val drainReadIssue = finalCommitStart || drainActive
  private val activeDrainBeat = Mux(finalCommitStart, 0.U, drainIssueBeat)
  private val drainReadSlot = finalPlacement(activeDrainBeat)
  when(finalCommitStart) {
    assert(!drainActive, "overlapping rotating accumulator drains")
    drainActive := true.B
    drainIssueBeat := 1.U
    drainTag := writeTag
  }.elsewhen(drainActive) {
    when(drainIssueBeat === (config.outputFrameBeats - 1).U) {
      drainActive := false.B
      drainIssueBeat := 0.U
    }.otherwise {
      drainIssueBeat := drainIssueBeat + 1.U
    }
  }

  private val memoryReadEnable = Wire(Vec(physicalBankCount, Bool()))
  private val memoryReadAddress = Wire(
    Vec(physicalBankCount, UInt(inputBeatWidth.W))
  )
  for (bank <- 0 until physicalBankCount) {
    val groupReads = VecInit((0 until 2).map { group =>
      inputReadEnable && slotBank(inputReadSlots(group)) === bank.U
    })
    val drainRead = drainReadIssue && slotBank(drainReadSlot) === bank.U
    assert(
      PopCount(Cat(groupReads.asUInt, drainRead)) <= 1.U,
      "rotating accumulator scheduled two reads on one physical bank"
    )
    memoryReadEnable(bank) := groupReads.asUInt.orR || drainRead
    memoryReadAddress(bank) := Mux(
      drainRead,
      slotAddress(drainReadSlot),
      Mux(
        groupReads(0),
        slotAddress(inputReadSlots(0)),
        slotAddress(inputReadSlots(1))
      )
    )
  }
  private val memoryReadWords = accumulatorMemories.zipWithIndex.map {
    case (memory, bank) =>
      memory.read(memoryReadAddress(bank), memoryReadEnable(bank))(
        halfWordWidth - 1,
        0
      )
  }

  private val responseInputSlots = RegEnable(inputReadSlots, inputReadEnable)
  private val firstPreviousWord = Wire(Vec(2, halfWordType))
  for (group <- 0 until 2) {
    firstPreviousWord(group) := VecInit(memoryReadWords)(
      slotBank(responseInputSlots(group))
    ).asTypeOf(halfWordType)
  }
  private val pendingPreviousWord = ShiftRegister(
    firstPreviousWord,
    remainingProductCycles
  )
  private val accumulatedWord = Wire(Vec(2, halfWordType))
  for (group <- 0 until 2) {
    for (component <- 0 until config.outputComponents) {
      for (lane <- 0 until config.outputLanes) {
        val previous = pendingPreviousWord(group)(component)(lane)
        val product = productWord(group)(component)(lane)
        accumulatedWord(group)(component)(lane).real :=
          FixedPointBits.lowSigned(
            Mux(
              pendingRow === 0.U,
              0.S(config.accumulator.width.W),
              previous.real
            ) + product.real,
            config.accumulator.width
          )
        accumulatedWord(group)(component)(lane).imag :=
          FixedPointBits.lowSigned(
            Mux(
              pendingRow === 0.U,
              0.S(config.accumulator.width.W),
              previous.imag
            ) + product.imag,
            config.accumulator.width
          )
      }
    }
  }

  private val commitRegister = Module(new PhysicalCutRegister(2 * halfWordWidth))
  commitRegister.io.clock := clock
  commitRegister.io.enable := true.B
  commitRegister.io.inputData := accumulatedWord.asUInt
  private val writeWord = commitRegister.io.outputData.asTypeOf(accumulatedWord)

  private val writePlacement = Wire(chiselTypeOf(finalPlacement))
  writePlacement := MuxLookup(writeRow, finalPlacement)(
    Seq(
      0.U -> rowZeroPlacement,
      1.U -> rowOnePlacement,
      2.U -> finalPlacement,
      3.U -> finalPlacement
    )
  )
  private val writeSlots = Wire(Vec(2, UInt(physicalSlotWidth.W)))
  for (group <- 0 until 2) {
    writeSlots(group) := writePlacement(logicalHalf(writeBeat, group))
  }
  for (bank <- 0 until physicalBankCount) {
    val writes = VecInit((0 until 2).map { group =>
      writeValid && slotBank(writeSlots(group)) === bank.U
    })
    assert(
      PopCount(writes) <= 1.U,
      "rotating accumulator scheduled two writes on one physical bank"
    )
    when(writes.asUInt.orR) {
      val selectedGroup = Mux(writes(0), 0.U, 1.U)
      val selectedSlot = Mux(writes(0), writeSlots(0), writeSlots(1))
      val payload =
        if (accumulatorMemoryWidths(bank) == halfWordWidth)
          writeWord(selectedGroup).asUInt
        else
          Cat(
            0.U((accumulatorMemoryWidths(bank) - halfWordWidth).W),
            writeWord(selectedGroup).asUInt
          )
      accumulatorMemories(bank).write(slotAddress(selectedSlot), payload)
    }
  }

  private val drainReadResponse = RegNext(drainReadIssue, false.B)
  private val drainResponseSlot = RegEnable(drainReadSlot, drainReadIssue)
  private val drainResponseBeat = RegEnable(activeDrainBeat, drainReadIssue)
  private val drainedHalf = VecInit(memoryReadWords)(slotBank(drainResponseSlot))
    .asTypeOf(halfWordType)
  when(drainReadResponse) {
    componentOneMemory.write(drainResponseBeat, drainedHalf(1).asUInt)
  }

  private val componentOneReading = RegInit(false.B)
  private val componentOneIssueBeat = Reg(UInt(outputBeatWidth.W))
  private val startComponentOne = drainReadResponse &&
    drainResponseBeat === (config.outputFrameBeats - 1).U
  private val componentOneReadIssue = startComponentOne || componentOneReading
  private val activeComponentOneBeat = Mux(
    startComponentOne,
    0.U,
    componentOneIssueBeat
  )
  private val componentOneReadData = componentOneMemory.read(
    activeComponentOneBeat,
    componentOneReadIssue
  )
  when(startComponentOne) {
    componentOneReading := true.B
    componentOneIssueBeat := 1.U
  }.elsewhen(componentOneReading) {
    when(componentOneIssueBeat === (config.outputFrameBeats - 1).U) {
      componentOneReading := false.B
      componentOneIssueBeat := 0.U
    }.otherwise {
      componentOneIssueBeat := componentOneIssueBeat + 1.U
    }
  }
  private val componentOneResponse = RegNext(componentOneReadIssue, false.B)
  private val componentOneResponseBeat = RegEnable(
    activeComponentOneBeat,
    componentOneReadIssue
  )
  private val componentOneOutput = componentOneReadData.asTypeOf(
    Vec(config.outputLanes, new ComplexSInt(config.accumulator.width))
  )

  io.outputValid := drainReadResponse || componentOneResponse
  io.outputStart := finalCommitStart || startComponentOne
  io.outputFirst := drainReadResponse && drainResponseBeat === 0.U
  io.outputLast := componentOneResponse &&
    componentOneResponseBeat === (config.outputFrameBeats - 1).U
  io.outputComponent := Mux(componentOneResponse, 1.U, 0.U)
  io.outputTag := drainTag
  for (component <- 0 until config.outputComponents) {
    for (lane <- 0 until config.outputLanes) {
      io.output(component)(lane) := 0.U.asTypeOf(io.output(component)(lane))
    }
  }
  when(drainReadResponse) {
    io.output(0) := drainedHalf(0)
  }
  when(componentOneResponse) {
    io.output(1) := componentOneOutput
  }
  when(io.outputValid) {
    assert(io.outputReady, "rotating accumulator output cannot be backpressured")
  }

  io.done := io.outputValid && io.outputReady && io.outputLast
  io.doneTag := drainTag
  io.busy := inputActive || pendingValid || writeValid || drainActive ||
    componentOneReading || drainReadResponse || componentOneResponse
}

package fpt

import chisel3._
import chisel3.util._

final case class ReplicatedAccumulatorBanksConfig(
    coefficient: CmuxCoefficientConfig,
    batchContexts: Int
) {
  require(batchContexts >= 2)
  require(coefficient.points % coefficient.inverseLanes == 0)

  val halfBeats: Int = coefficient.inverseBeats
  require(halfBeats >= 2 && isPow2(halfBeats))

  val contextWidth: Int = TransformUtil.counterWidth(batchContexts)
  val halfBeatWidth: Int = log2Ceil(halfBeats)
  val loadBeats: Int = 2 * halfBeats
  val loadBeatWidth: Int = TransformUtil.counterWidth(loadBeats)
  val addressDepth: Int = batchContexts * halfBeats
  val addressWidth: Int = TransformUtil.counterWidth(addressDepth)
  val wordFields: Int = 2 * coefficient.components
  val wordWidth: Int = wordFields * coefficient.torusWidth

  // Both copies contain the same logical accumulator. Replication supplies
  // one synchronous read port to forward prefetch and another to the inverse
  // read-modify-write path while a common write is mirrored into each copy.
  val logicalBits: BigInt = BigInt(batchContexts) * coefficient.components *
    coefficient.polynomialSize * coefficient.torusWidth
  val replicatedBits: BigInt = 2 * logicalBits
}

/** Synthesis-oriented storage for batch-interleaved TRLWE accumulators.
  *
  * A lane word packs the low and high polynomial halves for every component.
  * The bank index is the inverse-transform lane and the address is
  * `(context, pointBeat)`. Two identical SyncReadMem copies provide the two
  * independent reads required by FPT batching:
  *
  *   - the forward copy prefetches a complete context in `inverseBeats`
  *     consecutive cycles;
  *   - the update copy reads the old coefficient word while inverse results
  *     arrive, then the Torus sum is registered and written back to both
  *     copies two cycles later.
  *
  * Consequently each physical bank has only one read and one write port. This
  * is deliberately compatible with true dual-port FPGA block memories and
  * avoids expanding every batch context into flip-flops.
  */
final class ReplicatedAccumulatorBanks(
    val config: ReplicatedAccumulatorBanksConfig,
    val replicateReads: Boolean = true,
    val localWriteControls: Boolean = false
) extends Module {
  private val coefficient = config.coefficient
  private val contextWidth = config.contextWidth
  private val halfBeatWidth = config.halfBeatWidth
  private val loadBeatWidth = config.loadBeatWidth

  private def wordType: Vec[UInt] =
    Vec(config.wordFields, UInt(coefficient.torusWidth.W))

  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val loadContext = Input(UInt(contextWidth.W))
    val loadValid = Input(Bool())
    val loadReady = Output(Bool())
    val load = Input(
      Vec(
        coefficient.components,
        Vec(coefficient.inverseLanes, UInt(coefficient.torusWidth.W))
      )
    )
    val loadDone = Output(Bool())
    val loadDoneContext = Output(UInt(contextWidth.W))
    val contextLoaded = Output(Vec(config.batchContexts, Bool()))

    val prefetchStart = Input(Bool())
    val prefetchReady = Output(Bool())
    val prefetchContext = Input(UInt(contextWidth.W))
    val prefetchValid = Output(Bool())
    val prefetchBeat = Output(UInt(halfBeatWidth.W))
    val prefetchOutputContext = Output(UInt(contextWidth.W))
    val prefetchLow = Output(
      Vec(
        coefficient.components,
        Vec(coefficient.inverseLanes, UInt(coefficient.torusWidth.W))
      )
    )
    val prefetchHigh = Output(
      Vec(
        coefficient.components,
        Vec(coefficient.inverseLanes, UInt(coefficient.torusWidth.W))
      )
    )
    val prefetchDone = Output(Bool())

    val drainStart = Input(Bool())
    val drainStartReady = Output(Bool())
    val drainContext = Input(UInt(contextWidth.W))
    val drainValid = Output(Bool())
    val drainReady = Input(Bool())
    val drain = Output(
      Vec(
        coefficient.components,
        Vec(coefficient.inverseLanes, UInt(coefficient.torusWidth.W))
      )
    )
    val drainDone = Output(Bool())
    val drainDoneContext = Output(UInt(contextWidth.W))

    val updateValid = Input(Bool())
    val updateReady = Output(Bool())
    val updateFirst = Input(Bool())
    val updateContext = Input(UInt(contextWidth.W))
    val updateLow = Input(
      Vec(
        coefficient.components,
        Vec(
          coefficient.inverseLanes,
          SInt(coefficient.inverseFormat.width.W)
        )
      )
    )
    val updateHigh = Input(
      Vec(
        coefficient.components,
        Vec(
          coefficient.inverseLanes,
          SInt(coefficient.inverseFormat.width.W)
        )
      )
    )
    val updateDone = Output(Bool())
    val updateDoneContext = Output(UInt(contextWidth.W))
  })

  val forwardMemories = Seq.fill(coefficient.inverseLanes) {
    SyncReadMem(config.addressDepth, wordType)
  }
  val updateMemories = if (replicateReads) {
    Seq.fill(coefficient.inverseLanes) {
      SyncReadMem(config.addressDepth, wordType)
    }
  } else Seq.empty

  def packedAddress(context: UInt, beat: UInt): UInt = {
    val address = Cat(context, beat(halfBeatWidth - 1, 0))
    address(config.addressWidth - 1, 0)
  }

  val loaded = RegInit(VecInit(Seq.fill(config.batchContexts)(false.B)))
  io.contextLoaded := loaded

  // Loading accepts one polynomial half per cycle. A full-width commit
  // register sits immediately in front of the mirrored memories so loader
  // control and test-vector arithmetic do not directly drive 128 BRAM write
  // ports across the middle SLR.
  val loadActive = RegInit(false.B)
  val loadContextReg = Reg(UInt(contextWidth.W))
  val loadBeat = Reg(UInt(loadBeatWidth.W))
  io.loadReady := loadActive

  when(io.loadValid) {
    assert(io.loadReady, "accumulator load data presented while idle")
  }
  val loadFire = io.loadValid && io.loadReady
  val loadHighHalf = loadBeat >= config.halfBeats.U
  val loadHalfBeat = Mux(
    loadHighHalf,
    loadBeat - config.halfBeats.U,
    loadBeat
  )
  val loadAddress = packedAddress(loadContextReg, loadHalfBeat)
  val loadFinal = loadFire && loadBeat === (config.loadBeats - 1).U

  val loadCommitValid = RegNext(loadFire, false.B)
  val loadCommitAddress = RegEnable(loadAddress, loadFire)
  val loadCommitContext = RegEnable(loadContextReg, loadFire)
  val loadCommitHighHalf = RegEnable(loadHighHalf, loadFire)
  val loadCommitFinal = RegEnable(loadFinal, loadFire)
  val loadCommitData = Reg(chiselTypeOf(io.load))
  when(loadFire) {
    loadCommitData := io.load
  }

  when(io.loadStart) {
    assert(!loadActive, "accumulator load started while active")
    assert(!loadCommitValid, "accumulator load started during a load commit")
    assert(io.loadContext < config.batchContexts.U, "invalid load context")
    assert(!io.prefetchStart, "load and prefetch started together")
    assert(!io.updateValid, "load started with inverse update data")
    loadActive := true.B
    loadContextReg := io.loadContext
    loadBeat := 0.U
    loaded(io.loadContext) := false.B
  }

  io.loadDone := loadCommitValid && loadCommitFinal
  io.loadDoneContext := loadCommitContext

  when(loadFire) {
    when(loadFinal) {
      loadActive := false.B
      loadBeat := 0.U
      // Reads remain blocked by loadCommitValid until the final write lands.
      // Marking the context here makes contextLoaded align with loadDone in
      // the following cycle without exposing partially committed storage.
      loaded(loadContextReg) := true.B
    }.otherwise {
      loadBeat := loadBeat + 1.U
    }
  }

  // The update transaction state is declared before the shared-read
  // arbitration. In single-image mode an update burst and a prefetch burst
  // are each atomic and cannot own the one physical read port together.
  val updateActive = RegInit(false.B)
  val updateContextReg = Reg(UInt(contextWidth.W))
  val updateBeat = Reg(UInt(halfBeatWidth.W))

  // Register a forward-prefetch request before driving the lane-local BRAM
  // address ports. The request context otherwise crosses command arbitration
  // and the packed-address cone on the same cycle as prefetchStart, producing
  // a route-dominated path into the distributed BRAM columns. The complete
  // packed context still returns at one beat per cycle after this one-cycle
  // request stage.
  val prefetchActive = RegInit(false.B)
  val prefetchContextReg = Reg(UInt(contextWidth.W))
  val prefetchIssueBeat = Reg(UInt(halfBeatWidth.W))
  val drainActive = RegInit(false.B)
  val drainContextReg = Reg(UInt(contextWidth.W))
  val drainIssueBeat = RegInit(0.U(loadBeatWidth.W))
  class DrainResponse extends Bundle {
    val data = chiselTypeOf(io.drain)
    val last = Bool()
  }
  val drainResponses = Module(
    new Queue(new DrainResponse, entries = 2, pipe = true, flow = true)
  )
  val drainDoneReg = RegInit(false.B)
  val drainDoneContextReg = Reg(UInt(contextWidth.W))
  val drainBusy = Wire(Bool())
  io.prefetchReady := !prefetchActive && !loadActive && !loadCommitValid &&
    !io.loadStart && !drainBusy
  val prefetchFire = io.prefetchStart && io.prefetchReady
  val prefetchReadEnable = prefetchActive
  val activePrefetchContext = prefetchContextReg
  val activePrefetchBeat = prefetchIssueBeat
  val prefetchAddress = packedAddress(
    activePrefetchContext,
    activePrefetchBeat
  )
  io.drainStartReady := !drainBusy && !prefetchActive && !updateActive &&
    !loadActive && !loadCommitValid && !io.loadStart &&
    !io.updateValid
  val drainStartFire = io.drainStart && io.drainStartReady
  val drainIssueEnable = Wire(Bool())
  val drainIssueHigh = drainIssueBeat >= config.halfBeats.U
  val drainAddress = packedAddress(
    drainContextReg,
    drainIssueBeat(halfBeatWidth - 1, 0)
  )
  val sharedReadAddress = Wire(UInt(config.addressWidth.W))
  val sharedReadEnable = Wire(Bool())
  val prefetchWords = forwardMemories.map(
    _.read(sharedReadAddress, sharedReadEnable)
  )
  val drainResponseValid = RegNext(drainIssueEnable, false.B)
  val drainResponseHigh = RegEnable(drainIssueHigh, drainIssueEnable)
  val drainResponseLast = RegEnable(
    drainIssueBeat === (config.loadBeats - 1).U,
    drainIssueEnable
  )
  drainResponses.io.enq.valid := drainResponseValid
  drainResponses.io.enq.bits.last := drainResponseLast
  for (component <- 0 until coefficient.components) {
    for (lane <- 0 until coefficient.inverseLanes) {
      drainResponses.io.enq.bits.data(component)(lane) := Mux(
        drainResponseHigh,
        prefetchWords(lane)(2 * component + 1),
        prefetchWords(lane)(2 * component)
      )
    }
  }
  drainResponses.io.deq.ready := io.drainReady
  val drainOutputFire = drainResponses.io.deq.valid && io.drainReady
  val drainOccupancyAfterResponse =
    drainResponses.io.count + drainResponseValid.asUInt -
      drainOutputFire.asUInt
  drainIssueEnable := drainActive && drainOccupancyAfterResponse < 2.U
  drainBusy := drainActive || drainResponseValid ||
    drainResponses.io.deq.valid
  when(drainResponseValid) {
    assert(
      drainResponses.io.enq.ready,
      "accumulator drain response queue overflow"
    )
  }
  val prefetchValid = RegNext(prefetchReadEnable, false.B)
  val prefetchBeatReg = RegEnable(activePrefetchBeat, prefetchReadEnable)
  val prefetchOutputContextReg = RegEnable(
    activePrefetchContext,
    prefetchReadEnable
  )
  // Register the complete BRAM response before it leaves the memory module.
  // Vivado can merge this unconditional stage into the RAMB36 output
  // registers; without it, BRAM clock-to-out directly drives both wide
  // coefficient buffers and leaves a route-dominated path across the middle
  // SLR. The eight-beat prefetch still fits inside the command interval.
  val prefetchOutputWords = RegNext(VecInit(prefetchWords))
  val prefetchOutputValid = RegNext(prefetchValid, false.B)
  val prefetchOutputBeat = RegEnable(prefetchBeatReg, prefetchValid)
  val prefetchResponseContext = RegEnable(
    prefetchOutputContextReg,
    prefetchValid
  )
  io.prefetchValid := prefetchOutputValid
  io.prefetchBeat := prefetchOutputBeat
  io.prefetchOutputContext := prefetchResponseContext
  io.prefetchDone := prefetchOutputValid &&
    prefetchOutputBeat === (config.halfBeats - 1).U
  for (component <- 0 until coefficient.components) {
    for (lane <- 0 until coefficient.inverseLanes) {
      io.prefetchLow(component)(lane) :=
        prefetchOutputWords(lane)(2 * component)
      io.prefetchHigh(component)(lane) :=
        prefetchOutputWords(lane)(2 * component + 1)
      io.drain(component)(lane) :=
        drainResponses.io.deq.bits.data(component)(lane)
    }
  }
  io.drainValid := drainResponses.io.deq.valid
  io.drainDone := drainDoneReg
  io.drainDoneContext := drainDoneContextReg
  drainDoneReg := false.B

  when(drainStartFire) {
    val selectedLoaded = loaded.zipWithIndex
      .map { case (flag, context) =>
        (io.drainContext === context.U) && flag
      }
      .reduce(_ || _)
    assert(io.drainContext < config.batchContexts.U, "invalid drain context")
    assert(selectedLoaded, "drain targets an unloaded context")
    drainActive := true.B
    drainContextReg := io.drainContext
    drainIssueBeat := 0.U
  }
  when(drainIssueEnable) {
    when(drainIssueBeat === (config.loadBeats - 1).U) {
      drainActive := false.B
      drainIssueBeat := 0.U
    }.otherwise {
      drainIssueBeat := drainIssueBeat + 1.U
    }
  }
  when(drainOutputFire && drainResponses.io.deq.bits.last) {
    drainDoneReg := true.B
    drainDoneContextReg := drainContextReg
  }

  when(prefetchFire) {
    // Avoid an unguarded immediate assertion from a dynamic Vec read.
    val selectedLoaded = loaded.zipWithIndex
      .map { case (flag, context) =>
        (io.prefetchContext === context.U) && flag
      }
      .reduce(_ || _)
    assert(
      io.prefetchContext < config.batchContexts.U,
      "invalid prefetch context"
    )
    assert(selectedLoaded, "prefetch targets an unloaded context")
    prefetchContextReg := io.prefetchContext
    prefetchActive := true.B
    prefetchIssueBeat := 0.U
  }.elsewhen(prefetchActive) {
    when(prefetchIssueBeat === (config.halfBeats - 1).U) {
      prefetchActive := false.B
      prefetchIssueBeat := 0.U
    }.otherwise {
      prefetchIssueBeat := prefetchIssueBeat + 1.U
    }
  }

  // The inverse stream is accepted without bubbles when reads are replicated.
  // With one physical image, an atomic coefficient prefetch has priority for
  // eight cycles; the outer update queue retains this transaction's context,
  // beat, and payload until the shared read port becomes available again.
  io.updateReady := !loadActive && !loadCommitValid && !io.loadStart &&
    !drainBusy &&
    (if (replicateReads) true.B
     else !prefetchActive && (updateActive || !io.prefetchStart))
  val updateFire = io.updateValid && io.updateReady
  val activeUpdateContext = Mux(
    updateActive,
    updateContextReg,
    io.updateContext
  )
  val activeUpdateBeat = Mux(updateActive, updateBeat, 0.U)
  val updateFinal = updateFire &&
    activeUpdateBeat === (config.halfBeats - 1).U
  val updateAddress = packedAddress(activeUpdateContext, activeUpdateBeat)
  sharedReadEnable := prefetchReadEnable || drainIssueEnable ||
    (if (replicateReads) false.B else updateFire)
  sharedReadAddress := Mux(
    prefetchReadEnable,
    prefetchAddress,
    Mux(drainIssueEnable, drainAddress, updateAddress)
  )
  val updateWords = if (replicateReads) {
    updateMemories.map(_.read(updateAddress, updateFire))
  } else prefetchWords

  val updateWriteValid = RegNext(updateFire, false.B)
  val updateWriteFinal = RegNext(updateFinal, false.B)
  val updateWriteAddress = RegEnable(updateAddress, updateFire)
  val updateWriteContext = RegEnable(activeUpdateContext, updateFire)
  val updateLowReg = Reg(chiselTypeOf(io.updateLow))
  val updateHighReg = Reg(chiselTypeOf(io.updateHigh))
  // These wide registers are the receiving boundary for inverse data crossing
  // from SLR2. Clock them unconditionally so the narrow update-valid cone
  // does not become a several-thousand-register CE net spanning SLR1; only
  // updateWriteValid qualifies their eventual memory write.
  updateLowReg := io.updateLow
  updateHighReg := io.updateHigh

  when(io.updateValid) {
    assert(
      updateActive || io.updateFirst,
      "first accumulator update beat must carry updateFirst"
    )
  }
  when(io.updateFirst && io.updateValid) {
    // Avoid an unguarded immediate assertion from a dynamic Vec read.
    val selectedLoaded = loaded.zipWithIndex
      .map { case (flag, context) =>
        (io.updateContext === context.U) && flag
      }
      .reduce(_ || _)
    assert(!updateActive, "updateFirst asserted inside an update transaction")
    assert(io.updateContext < config.batchContexts.U, "invalid update context")
    assert(selectedLoaded, "update targets an unloaded context")
  }
  when(updateFire) {
    when(io.updateFirst) {
      updateContextReg := io.updateContext
    }
    when(updateFinal) {
      updateActive := false.B
      updateBeat := 0.U
    }.otherwise {
      updateActive := true.B
      updateBeat := activeUpdateBeat + 1.U
    }
  }

  when(prefetchReadEnable && updateFire) {
    if (replicateReads) {
      assert(
        activePrefetchContext =/= activeUpdateContext,
        "prefetch and update accessed the same accumulator context"
      )
    } else {
      assert(false.B, "prefetch and update shared one accumulator read port")
    }
  }
  when(loadCommitValid) {
    assert(!prefetchReadEnable, "load overlapped a forward prefetch")
    assert(!updateFire, "load commit overlapped an inverse update read")
  }

  val loadWords = Seq.tabulate(coefficient.inverseLanes) { lane =>
    val word = Wire(wordType)
    for (component <- 0 until coefficient.components) {
      word(2 * component) := loadCommitData(component)(lane)
      word(2 * component + 1) := loadCommitData(component)(lane)
    }
    word
  }
  val loadMask = Seq.tabulate(config.wordFields) { field =>
    if ((field & 1) == 0) !loadCommitHighHalf else loadCommitHighHalf
  }
  val updatedWords = Seq.tabulate(coefficient.inverseLanes) { lane =>
    val word = Wire(wordType)
    for (component <- 0 until coefficient.components) {
      val lowTorus = (updateLowReg(component)(lane).asUInt <<
        coefficient.torusShift)(coefficient.torusWidth - 1, 0)
      val highTorus = (updateHighReg(component)(lane).asUInt <<
        coefficient.torusShift)(coefficient.torusWidth - 1, 0)
      word(2 * component) :=
        updateWords(lane)(2 * component) + lowTorus
      word(2 * component + 1) :=
        updateWords(lane)(2 * component + 1) + highTorus
    }
    word
  }

  val updateCommitValid = RegNext(updateWriteValid, false.B)
  val updateCommitFinal = RegEnable(
    updateWriteFinal,
    false.B,
    updateWriteValid
  )
  val updateCommitAddress = RegEnable(
    updateWriteAddress,
    0.U(config.addressWidth.W),
    updateWriteValid
  )
  val updateCommitContext = RegEnable(
    updateWriteContext,
    0.U(contextWidth.W),
    updateWriteValid
  )
  val updateCommitWords = Reg(Vec(coefficient.inverseLanes, wordType))
  // updateCommitValid qualifies the following memory write. Keeping this
  // wide data boundary unconditional prevents updateWriteValid from becoming
  // a clock-enable broadcast over every inverse lane (about 4K loads on U280).
  for (lane <- 0 until coefficient.inverseLanes) {
    updateCommitWords(lane) := updatedWords(lane)
  }
  when(io.loadStart) {
    assert(
      !updateWriteValid && !updateCommitValid,
      "accumulator load started with an inverse update in flight"
    )
    assert(!drainBusy, "accumulator load started while drain is active")
  }
  assert(
    !(loadCommitValid && updateCommitValid),
    "load and inverse update committed on the same cycle"
  )

  val memoryWriteEnable = loadCommitValid || updateCommitValid
  val memoryWriteAddress = Mux(
    loadCommitValid,
    loadCommitAddress,
    updateCommitAddress
  )
  // Move address/mask selection before the existing commit edge. Local
  // copies drive only four lane banks, without changing the write schedule.
  class LocalWriteControl extends Bundle {
    val address = UInt(config.addressWidth.W)
    val isLoad = Bool()
    val mask = Vec(config.wordFields, Bool())
  }
  val localControls = if (localWriteControls) {
    val input = Wire(new LocalWriteControl)
    input.address := Mux(loadFire, loadAddress, updateWriteAddress)
    input.isLoad := loadFire
    for (field <- 0 until config.wordFields) {
      val halfSelected = if ((field & 1) == 0) !loadHighHalf else loadHighHalf
      input.mask(field) := Mux(loadFire, halfSelected, updateWriteValid)
    }
    Seq.tabulate((coefficient.inverseLanes + 3) / 4) { group =>
      val cut = Module(new PhysicalControlRegister(input.getWidth))
      cut.suggestName(s"localWriteControl_$group")
      cut.io.clock := clock
      cut.io.reset := reset.asBool
      cut.io.inputData := input.asUInt
      val output = cut.io.outputData.asTypeOf(new LocalWriteControl)
      assert(output.mask.asUInt.orR === memoryWriteEnable,
        "local write enable changed commit timing")
      when(memoryWriteEnable) {
        assert(output.address === memoryWriteAddress,
          "local write address diverged from the commit stage")
        assert(output.isLoad === loadCommitValid,
          "local write data selection diverged from the commit stage")
        for (field <- 0 until config.wordFields) {
          assert(output.mask(field) === Mux(loadCommitValid, loadMask(field), true.B),
            "local write mask diverged from the commit stage")
        }
      }
      output
    }
  } else Seq.empty
  for (lane <- 0 until coefficient.inverseLanes) {
    val writeIsLoad = if (localWriteControls) localControls(lane / 4).isLoad else loadCommitValid
    val writeAddress = if (localWriteControls) localControls(lane / 4).address else memoryWriteAddress
    val memoryWriteWord = Mux(
      writeIsLoad,
      loadWords(lane),
      updateCommitWords(lane)
    )
    val memoryWriteMask = Seq.tabulate(config.wordFields) { field =>
      if (localWriteControls) localControls(lane / 4).mask(field)
      else Mux(loadCommitValid, loadMask(field), true.B) && memoryWriteEnable
    }
    forwardMemories(lane).write(
      writeAddress,
      memoryWriteWord,
      memoryWriteMask
    )
    if (replicateReads) {
      updateMemories(lane).write(
        writeAddress,
        memoryWriteWord,
        memoryWriteMask
      )
    }
  }

  if (!replicateReads) {
    when(prefetchReadEnable && updateCommitValid) {
      assert(
        prefetchAddress =/= updateCommitAddress,
        "prefetch collided with a delayed accumulator update write"
      )
    }
  }

  io.updateDone := updateCommitValid && updateCommitFinal
  io.updateDoneContext := updateCommitContext
}

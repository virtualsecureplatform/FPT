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
    val config: ReplicatedAccumulatorBanksConfig
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
  val updateMemories = Seq.fill(coefficient.inverseLanes) {
    SyncReadMem(config.addressDepth, wordType)
  }

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
  val loadContextReg = RegInit(0.U(contextWidth.W))
  val loadBeat = RegInit(0.U(loadBeatWidth.W))
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
  val loadCommitAddress = RegEnable(
    loadAddress,
    0.U(config.addressWidth.W),
    loadFire
  )
  val loadCommitContext = RegEnable(
    loadContextReg,
    0.U(contextWidth.W),
    loadFire
  )
  val loadCommitHighHalf = RegEnable(loadHighHalf, false.B, loadFire)
  val loadCommitFinal = RegEnable(loadFinal, false.B, loadFire)
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

  // Register a forward-prefetch request before driving the 128 mirrored BRAM
  // address ports. The request context otherwise crosses command arbitration
  // and the packed-address cone on the same cycle as prefetchStart, producing
  // a route-dominated path into the distributed BRAM columns. The complete
  // packed context still returns at one beat per cycle after this one-cycle
  // request stage.
  val prefetchActive = RegInit(false.B)
  val prefetchContextReg = RegInit(0.U(contextWidth.W))
  val prefetchIssueBeat = RegInit(0.U(halfBeatWidth.W))
  io.prefetchReady := !prefetchActive && !loadActive && !loadCommitValid &&
    !io.loadStart
  val prefetchFire = io.prefetchStart && io.prefetchReady
  val prefetchReadEnable = prefetchActive
  val activePrefetchContext = prefetchContextReg
  val activePrefetchBeat = prefetchIssueBeat
  val prefetchAddress = packedAddress(
    activePrefetchContext,
    activePrefetchBeat
  )
  val prefetchWords = forwardMemories.map(
    _.read(prefetchAddress, prefetchReadEnable)
  )
  val prefetchValid = RegNext(prefetchReadEnable, false.B)
  val prefetchBeatReg = RegEnable(
    activePrefetchBeat,
    0.U(halfBeatWidth.W),
    prefetchReadEnable
  )
  val prefetchOutputContextReg = RegEnable(
    activePrefetchContext,
    0.U(contextWidth.W),
    prefetchReadEnable
  )
  // Register the complete BRAM response before it leaves the memory module.
  // Vivado can merge this unconditional stage into the RAMB36 output
  // registers; without it, BRAM clock-to-out directly drives both wide
  // coefficient buffers and leaves a route-dominated path across the middle
  // SLR. The eight-beat prefetch still fits inside the command interval.
  val prefetchOutputWords = RegNext(VecInit(prefetchWords))
  val prefetchOutputValid = RegNext(prefetchValid, false.B)
  val prefetchOutputBeat = RegEnable(
    prefetchBeatReg,
    0.U(halfBeatWidth.W),
    prefetchValid
  )
  val prefetchResponseContext = RegEnable(
    prefetchOutputContextReg,
    0.U(contextWidth.W),
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
    }
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

  // The inverse stream is accepted without bubbles. Its old packed word is
  // read from the second memory copy, and the Torus-domain sum is registered
  // before it is written to both copies. The extra stage cuts the BRAM-output
  // adder away from the two BRAM write inputs while preserving one beat per
  // cycle.
  val updateActive = RegInit(false.B)
  val updateContextReg = RegInit(0.U(contextWidth.W))
  val updateBeat = RegInit(0.U(halfBeatWidth.W))
  io.updateReady := !loadActive && !loadCommitValid && !io.loadStart
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
  val updateWords = updateMemories.map(_.read(updateAddress, updateFire))

  val updateWriteValid = RegNext(updateFire, false.B)
  val updateWriteFinal = RegNext(updateFinal, false.B)
  val updateWriteAddress = RegEnable(
    updateAddress,
    0.U(config.addressWidth.W),
    updateFire
  )
  val updateWriteContext = RegEnable(
    activeUpdateContext,
    0.U(contextWidth.W),
    updateFire
  )
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
    assert(
      activePrefetchContext =/= activeUpdateContext,
      "prefetch and update accessed the same accumulator context"
    )
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
  for (lane <- 0 until coefficient.inverseLanes) {
    val memoryWriteWord = Mux(
      loadCommitValid,
      loadWords(lane),
      updateCommitWords(lane)
    )
    val memoryWriteMask = Seq.tabulate(config.wordFields) { field =>
      Mux(loadCommitValid, loadMask(field), true.B)
    }
    forwardMemories(lane).write(
      memoryWriteAddress,
      memoryWriteWord,
      memoryWriteMask.map(_ && memoryWriteEnable)
    )
    updateMemories(lane).write(
      memoryWriteAddress,
      memoryWriteWord,
      memoryWriteMask.map(_ && memoryWriteEnable)
    )
  }

  io.updateDone := updateCommitValid && updateCommitFinal
  io.updateDoneContext := updateCommitContext
}

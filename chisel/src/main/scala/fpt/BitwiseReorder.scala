package fpt

import chisel3._
import chisel3.util._

/** Coefficient-wise load to bitwise-streamed negacyclic rotation.
  *
  * Coefficients are transposed into `bitsPerCycle`-wide chunks. Rotation then
  * emits every polynomial position in parallel, least-significant chunk
  * first. Only one small chunk per coefficient passes through the dynamic
  * rotation muxes each cycle. Wrapped coefficients are negated serially;
  * `negateCarry` preserves the carry of two's-complement negation between
  * chunks.
  */
final class BitwiseNegacyclicReorder(
    val polynomialSize: Int,
    val loadLanes: Int,
    val coefficientWidth: Int,
    val bitsPerCycle: Int
) extends Module {
  require(polynomialSize >= 2 && isPow2(polynomialSize))
  require(loadLanes >= 1 && isPow2(loadLanes))
  require(
    loadLanes <= polynomialSize / 2 && polynomialSize % loadLanes == 0
  )
  require(coefficientWidth >= 2)
  require(bitsPerCycle >= 1 && coefficientWidth % bitsPerCycle == 0)

  private val indexWidth = log2Ceil(polynomialSize)
  private val loadBeats = polynomialSize / loadLanes
  private val updateBeats = loadBeats / 2
  private val chunks = coefficientWidth / bitsPerCycle
  private val loadBeatWidth = TransformUtil.counterWidth(loadBeats)
  private val updateBeatWidth = TransformUtil.counterWidth(updateBeats)
  private val chunkWidth = TransformUtil.counterWidth(chunks)

  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val loadValid = Input(Bool())
    val loadReady = Output(Bool())
    val load = Input(Vec(loadLanes, UInt(coefficientWidth.W)))
    val loadDone = Output(Bool())

    val rotateStart = Input(Bool())
    val rotateReady = Output(Bool())
    val exponent = Input(UInt((indexWidth + 1).W))
    val bitValid = Output(Bool())
    val bitReady = Input(Bool())
    val bitIndex = Output(UInt(chunkWidth.W))
    val originalChunk = Output(
      Vec(polynomialSize, UInt(bitsPerCycle.W))
    )
    val bitChunk = Output(Vec(polynomialSize, UInt(bitsPerCycle.W)))
    val rotateDone = Output(Bool())

    val updateStart = Input(Bool())
    val updateValid = Input(Bool())
    val updateReady = Output(Bool())
    val updateLow = Input(Vec(loadLanes, UInt(coefficientWidth.W)))
    val updateHigh = Input(Vec(loadLanes, UInt(coefficientWidth.W)))
    val updateDone = Output(Bool())

    val drainStart = Input(Bool())
    val drainValid = Output(Bool())
    val drainReady = Input(Bool())
    val drain = Output(Vec(loadLanes, UInt(coefficientWidth.W)))
    val drainDone = Output(Bool())

    val loaded = Output(Bool())
    val busy = Output(Bool())
  })

  val idle :: loading :: rotating :: updating :: draining :: Nil = Enum(5)
  val state = RegInit(idle)
  val loadedReg = RegInit(false.B)
  val loadBeat = RegInit(0.U(loadBeatWidth.W))
  val updateBeat = RegInit(0.U(updateBeatWidth.W))
  val chunkIndex = RegInit(0.U(chunkWidth.W))
  val exponentReg = RegInit(0.U((indexWidth + 1).W))
  val negateCarry = RegInit(VecInit(Seq.fill(polynomialSize)(false.B)))
  val loadDoneReg = RegInit(false.B)
  val rotateDoneReg = RegInit(false.B)
  val updateDoneReg = RegInit(false.B)
  val drainDoneReg = RegInit(false.B)

  // Transposition is explicit: a dynamic output selection touches only the
  // current small chunk rather than selecting a full coefficient and slicing
  // it afterwards.
  val chunkBanks = Reg(
    Vec(
      chunks,
      Vec(
        loadLanes,
        Vec(loadBeats, UInt(bitsPerCycle.W))
      )
    )
  )

  io.loadReady := state === loading
  io.loadDone := loadDoneReg
  io.bitValid := state === rotating
  io.bitIndex := chunkIndex
  io.rotateDone := rotateDoneReg
  io.updateReady := state === updating
  io.updateDone := updateDoneReg
  io.drainValid := state === draining
  io.drainDone := drainDoneReg
  io.loaded := loadedReg
  io.busy := state =/= idle
  loadDoneReg := false.B
  rotateDoneReg := false.B
  updateDoneReg := false.B
  drainDoneReg := false.B

  when(
    PopCount(
      Cat(io.loadStart, io.rotateStart, io.updateStart, io.drainStart)
    ) > 1.U
  ) {
    assert(false.B, "bitwise reorder operations must not start together")
  }
  when(io.loadStart) {
    assert(state === idle, "bitwise reorder load started while busy")
    state := loading
    loadBeat := 0.U
    loadedReg := false.B
  }
  when(io.loadValid) {
    assert(io.loadReady, "bitwise reorder load data presented while idle")
  }

  def storedCoefficient(lane: Int, beat: UInt): UInt =
    Cat((chunks - 1 to 0 by -1).map(chunk => chunkBanks(chunk)(lane)(beat)))

  for (lane <- 0 until loadLanes) {
    io.drain(lane) := storedCoefficient(lane, loadBeat)
  }

  val loadFire = io.loadValid && io.loadReady
  when(loadFire) {
    for (lane <- 0 until loadLanes) {
      for (chunk <- 0 until chunks) {
        chunkBanks(chunk)(lane)(loadBeat) := io.load(lane)(
          (chunk + 1) * bitsPerCycle - 1,
          chunk * bitsPerCycle
        )
      }
    }
    when(loadBeat === (loadBeats - 1).U) {
      state := idle
      loadBeat := 0.U
      loadedReg := true.B
      loadDoneReg := true.B
    }.otherwise {
      loadBeat := loadBeat + 1.U
    }
  }

  when(io.updateStart) {
    assert(state === idle && loadedReg, "bitwise update started while unavailable")
    state := updating
    updateBeat := 0.U
  }
  when(io.updateValid) {
    assert(io.updateReady, "bitwise update data presented while idle")
  }
  val updateFire = io.updateValid && io.updateReady
  when(updateFire) {
    val lowAddress = updateBeat.pad(loadBeatWidth)
    val highAddress = lowAddress + updateBeats.U
    for (lane <- 0 until loadLanes) {
      val updatedLow = (storedCoefficient(lane, lowAddress) +
        io.updateLow(lane))(coefficientWidth - 1, 0)
      val updatedHigh = (storedCoefficient(lane, highAddress) +
        io.updateHigh(lane))(coefficientWidth - 1, 0)
      for (chunk <- 0 until chunks) {
        chunkBanks(chunk)(lane)(lowAddress) := updatedLow(
          (chunk + 1) * bitsPerCycle - 1,
          chunk * bitsPerCycle
        )
        chunkBanks(chunk)(lane)(highAddress) := updatedHigh(
          (chunk + 1) * bitsPerCycle - 1,
          chunk * bitsPerCycle
        )
      }
    }
    when(updateBeat === (updateBeats - 1).U) {
      updateBeat := 0.U
      state := idle
      updateDoneReg := true.B
    }.otherwise {
      updateBeat := updateBeat + 1.U
    }
  }

  when(io.drainStart) {
    assert(state === idle && loadedReg, "bitwise drain started while unavailable")
    state := draining
    loadBeat := 0.U
  }
  when(io.drainValid && io.drainReady) {
    when(loadBeat === (loadBeats - 1).U) {
      loadBeat := 0.U
      state := idle
      drainDoneReg := true.B
    }.otherwise {
      loadBeat := loadBeat + 1.U
    }
  }

  val shift = exponentReg(indexWidth - 1, 0)
  val highNegate = exponentReg(indexWidth)
  val selectedChunks: Seq[UInt] = (0 until polynomialSize).map { position =>
    val lane = position % loadLanes
    val beat = position / loadLanes
    VecInit((0 until chunks).map(chunk =>
      chunkBanks(chunk)(lane)(beat)
    ))(chunkIndex)
  }
  // Express the dynamic rotation as a logarithmic barrel network. A direct
  // Vec lookup for every destination would lower to N independent N-to-one
  // muxes; these stages preserve the intended O(N log N) organization.
  var rotatedChunks: Seq[UInt] = selectedChunks
  for (bit <- 0 until indexWidth) {
    val distance = 1 << bit
    val previous = rotatedChunks
    rotatedChunks = (0 until polynomialSize).map { position =>
      val source = (position - distance + polynomialSize) % polynomialSize
      Mux(exponentReg(bit), previous(source), previous(position))
    }
  }
  val nextNegateCarry = Wire(Vec(polynomialSize, Bool()))
  for (position <- 0 until polynomialSize) {
    val source = rotatedChunks(position)
    io.originalChunk(position) := selectedChunks(position)
    val wraps = position.U < shift
    val negate = wraps ^ highNegate
    val selected = Mux(negate, ~source, source)
    val withCarry = selected +& negateCarry(position)
    io.bitChunk(position) := withCarry(bitsPerCycle - 1, 0)
    nextNegateCarry(position) := withCarry(bitsPerCycle)
  }

  val bitFire = io.bitValid && io.bitReady
  val finalChunk = chunkIndex === (chunks - 1).U
  val finishingRotation = bitFire && finalChunk
  io.rotateReady := loadedReg && (state === idle || finishingRotation)
  val rotateFire = io.rotateStart && io.rotateReady
  when(io.rotateStart) {
    assert(io.rotateReady, "bitwise rotation started while unavailable")
  }
  when(bitFire) {
    negateCarry := nextNegateCarry
    when(finalChunk) {
      state := idle
      chunkIndex := 0.U
      rotateDoneReg := true.B
    }.otherwise {
      chunkIndex := chunkIndex + 1.U
    }
  }
  // This block follows completion so a new marker on the final chunk keeps
  // the bit stream continuous and initializes the next negation carry.
  when(rotateFire) {
    state := rotating
    chunkIndex := 0.U
    exponentReg := io.exponent
    for (position <- 0 until polynomialSize) {
      val wraps = position.U < io.exponent(indexWidth - 1, 0)
      negateCarry(position) := wraps ^ io.exponent(indexWidth)
    }
  }
}

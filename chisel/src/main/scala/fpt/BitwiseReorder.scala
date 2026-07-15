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
  require(loadLanes <= polynomialSize && polynomialSize % loadLanes == 0)
  require(coefficientWidth >= 2)
  require(bitsPerCycle >= 1 && coefficientWidth % bitsPerCycle == 0)

  private val indexWidth = log2Ceil(polynomialSize)
  private val loadBeats = polynomialSize / loadLanes
  private val chunks = coefficientWidth / bitsPerCycle
  private val loadBeatWidth = TransformUtil.counterWidth(loadBeats)
  private val chunkWidth = TransformUtil.counterWidth(chunks)

  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val loadValid = Input(Bool())
    val loadReady = Output(Bool())
    val load = Input(Vec(loadLanes, UInt(coefficientWidth.W)))
    val loadDone = Output(Bool())

    val rotateStart = Input(Bool())
    val exponent = Input(UInt((indexWidth + 1).W))
    val bitValid = Output(Bool())
    val bitReady = Input(Bool())
    val bitIndex = Output(UInt(chunkWidth.W))
    val bitChunk = Output(Vec(polynomialSize, UInt(bitsPerCycle.W)))
    val rotateDone = Output(Bool())

    val loaded = Output(Bool())
    val busy = Output(Bool())
  })

  val idle :: loading :: rotating :: Nil = Enum(3)
  val state = RegInit(idle)
  val loadedReg = RegInit(false.B)
  val loadBeat = RegInit(0.U(loadBeatWidth.W))
  val chunkIndex = RegInit(0.U(chunkWidth.W))
  val exponentReg = RegInit(0.U((indexWidth + 1).W))
  val negateCarry = RegInit(VecInit(Seq.fill(polynomialSize)(false.B)))
  val loadDoneReg = RegInit(false.B)
  val rotateDoneReg = RegInit(false.B)

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
  io.loaded := loadedReg
  io.busy := state =/= idle
  loadDoneReg := false.B
  rotateDoneReg := false.B

  when(io.loadStart && io.rotateStart) {
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

  when(io.rotateStart) {
    assert(state === idle, "bitwise rotation started while busy")
    assert(loadedReg, "bitwise rotation started before load")
    state := rotating
    chunkIndex := 0.U
    exponentReg := io.exponent
    for (position <- 0 until polynomialSize) {
      val wraps = position.U < io.exponent(indexWidth - 1, 0)
      negateCarry(position) := wraps ^ io.exponent(indexWidth)
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
    val wraps = position.U < shift
    val negate = wraps ^ highNegate
    val selected = Mux(negate, ~source, source)
    val withCarry = selected +& negateCarry(position)
    io.bitChunk(position) := withCarry(bitsPerCycle - 1, 0)
    nextNegateCarry(position) := withCarry(bitsPerCycle)
  }

  val bitFire = io.bitValid && io.bitReady
  when(bitFire) {
    negateCarry := nextNegateCarry
    when(chunkIndex === (chunks - 1).U) {
      state := idle
      chunkIndex := 0.U
      rotateDoneReg := true.B
    }.otherwise {
      chunkIndex := chunkIndex + 1.U
    }
  }
}

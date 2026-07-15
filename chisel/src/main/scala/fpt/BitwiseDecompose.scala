package fpt

import chisel3._
import chisel3.util._

/** Bit-serial centered gadget decomposition.
  *
  * Input chunks arrive least-significant first. For every coefficient this
  * block computes `rotated - original + decompositionBias` modulo the Torus
  * width, carrying between chunks, then captures each base-B digit and maps
  * it from unsigned to centered two's-complement representation.
  */
final class BitwiseGadgetDecomposer(
    val polynomialSize: Int,
    val coefficientWidth: Int,
    val bitsPerCycle: Int,
    val levels: Int,
    val baseBits: Int,
    val decompositionBias: BigInt
) extends Module {
  require(polynomialSize >= 2 && isPow2(polynomialSize))
  require(coefficientWidth >= 2)
  require(bitsPerCycle >= 1 && coefficientWidth % bitsPerCycle == 0)
  require(levels >= 1 && baseBits >= 2)
  require(levels * baseBits < coefficientWidth)
  require(baseBits % bitsPerCycle == 0)
  require((coefficientWidth - levels * baseBits) % bitsPerCycle == 0)
  require(decompositionBias >= 0)

  private val chunks = coefficientWidth / bitsPerCycle
  private val chunkWidth = TransformUtil.counterWidth(chunks)
  private val coefficientMask = (BigInt(1) << coefficientWidth) - 1
  private val chunkMask = (BigInt(1) << bitsPerCycle) - 1
  private val baseMask = (BigInt(1) << baseBits) - 1
  private val bias = decompositionBias & coefficientMask

  val io = IO(new Bundle {
    val start = Input(Bool())
    val startReady = Output(Bool())
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val rotated = Input(Vec(polynomialSize, UInt(bitsPerCycle.W)))
    val original = Input(Vec(polynomialSize, UInt(bitsPerCycle.W)))
    val chunkIndex = Output(UInt(chunkWidth.W))
    val centeredDigit = Output(
      Vec(levels, Vec(polynomialSize, SInt(baseBits.W)))
    )
    val busy = Output(Bool())
    val done = Output(Bool())
  })

  val active = RegInit(false.B)
  val chunkIndex = RegInit(0.U(chunkWidth.W))
  // Adding source, one's-complement destination, bias, and the incoming carry
  // can produce a carry of at most two.
  val carry = RegInit(VecInit(Seq.fill(polynomialSize)(0.U(2.W))))
  val digits = Reg(
    Vec(levels, Vec(polynomialSize, UInt(baseBits.W)))
  )
  val doneReg = RegInit(false.B)

  io.inputReady := active
  io.chunkIndex := chunkIndex
  io.busy := active
  io.done := doneReg
  doneReg := false.B

  val biasChunks = VecInit((0 until chunks).map { chunk =>
    ((bias >> (chunk * bitsPerCycle)) & chunkMask).U(bitsPerCycle.W)
  })
  val nextCarry = Wire(Vec(polynomialSize, UInt(2.W)))
  val resultChunk = Wire(Vec(polynomialSize, UInt(bitsPerCycle.W)))
  for (position <- 0 until polynomialSize) {
    val sum = io.rotated(position) +& (~io.original(position)) +&
      biasChunks(chunkIndex) +& carry(position)
    resultChunk(position) := sum(bitsPerCycle - 1, 0)
    nextCarry(position) := sum(bitsPerCycle + 1, bitsPerCycle)
  }

  val inputFire = io.inputValid && io.inputReady
  val finalChunk = chunkIndex === (chunks - 1).U
  val finishing = inputFire && finalChunk
  io.startReady := !active || finishing
  val startFire = io.start && io.startReady
  when(io.start) {
    assert(io.startReady, "bitwise decomposition started while unavailable")
  }
  when(io.inputValid) {
    assert(io.inputReady, "bitwise decomposition input presented while idle")
  }
  when(inputFire) {
    carry := nextCarry
    for (streamChunk <- 0 until chunks) {
      for (level <- 0 until levels) {
        val digitShift = coefficientWidth - (level + 1) * baseBits
        val streamBit = streamChunk * bitsPerCycle
        if (streamBit >= digitShift && streamBit < digitShift + baseBits) {
          val digitBit = streamBit - digitShift
          when(chunkIndex === streamChunk.U) {
            for (position <- 0 until polynomialSize) {
              val fieldMask = chunkMask << digitBit
              val preserved = digits(level)(position) &
                (baseMask ^ fieldMask).U(baseBits.W)
              val inserted = (resultChunk(position).pad(baseBits) << digitBit)(
                baseBits - 1,
                0
              )
              digits(level)(position) := preserved | inserted
            }
          }
        }
      }
    }
    when(finalChunk) {
      active := false.B
      chunkIndex := 0.U
      doneReg := true.B
    }.otherwise {
      chunkIndex := chunkIndex + 1.U
    }
  }
  // A marker coincident with the previous final chunk starts the next
  // subtraction without a bubble. It must win over the completion updates.
  when(startFire) {
    active := true.B
    chunkIndex := 0.U
    for (position <- 0 until polynomialSize) {
      carry(position) := 1.U
    }
  }

  for (level <- 0 until levels) {
    for (position <- 0 until polynomialSize) {
      io.centeredDigit(level)(position) :=
        (digits(level)(position) -
          (BigInt(1) << (baseBits - 1)).U)(baseBits - 1, 0).asSInt
    }
  }
}

/** Exact bitwise monomial-rotation and gadget-decomposition frontend. */
final class BitwiseCmuxDecompositionFrontend(
    val config: CmuxCoefficientConfig,
    val bitsPerCycle: Int
) extends Module {
  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val loadValid = Input(Bool())
    val loadReady = Output(Bool())
    val load = Input(
      Vec(config.inverseLanes, UInt(config.torusWidth.W))
    )
    val loadDone = Output(Bool())

    val rotateStart = Input(Bool())
    val rotateReady = Output(Bool())
    val exponent = Input(UInt(config.exponentWidth.W))
    val centeredDigit = Output(
      Vec(
        config.levels,
        Vec(config.polynomialSize, SInt(config.baseBits.W))
      )
    )
    val busy = Output(Bool())
    val done = Output(Bool())

    val updateStart = Input(Bool())
    val updateValid = Input(Bool())
    val updateReady = Output(Bool())
    val updateLow = Input(Vec(config.inverseLanes, UInt(config.torusWidth.W)))
    val updateHigh = Input(Vec(config.inverseLanes, UInt(config.torusWidth.W)))
    val updateDone = Output(Bool())

    val drainStart = Input(Bool())
    val drainValid = Output(Bool())
    val drainReady = Input(Bool())
    val drain = Output(Vec(config.inverseLanes, UInt(config.torusWidth.W)))
    val drainDone = Output(Bool())
    val loaded = Output(Bool())
  })

  val reorder = Module(
    new BitwiseNegacyclicReorder(
      config.polynomialSize,
      config.inverseLanes,
      config.torusWidth,
      bitsPerCycle
    )
  )
  val decomposer = Module(
    new BitwiseGadgetDecomposer(
      config.polynomialSize,
      config.torusWidth,
      bitsPerCycle,
      config.levels,
      config.baseBits,
      config.decompositionBias
    )
  )

  reorder.io.loadStart := io.loadStart
  reorder.io.loadValid := io.loadValid
  reorder.io.load := io.load
  io.loadReady := reorder.io.loadReady
  io.loadDone := reorder.io.loadDone
  val rotateReady = reorder.io.rotateReady && decomposer.io.startReady
  val rotateFire = io.rotateStart && rotateReady
  io.rotateReady := rotateReady
  reorder.io.rotateStart := rotateFire
  reorder.io.exponent := io.exponent
  reorder.io.updateStart := io.updateStart
  reorder.io.updateValid := io.updateValid
  reorder.io.updateLow := io.updateLow
  reorder.io.updateHigh := io.updateHigh
  io.updateReady := reorder.io.updateReady
  io.updateDone := reorder.io.updateDone
  reorder.io.drainStart := io.drainStart
  reorder.io.drainReady := io.drainReady
  io.drainValid := reorder.io.drainValid
  io.drain := reorder.io.drain
  io.drainDone := reorder.io.drainDone
  io.loaded := reorder.io.loaded

  decomposer.io.start := rotateFire
  decomposer.io.inputValid := reorder.io.bitValid
  decomposer.io.rotated := reorder.io.bitChunk
  decomposer.io.original := reorder.io.originalChunk
  reorder.io.bitReady := decomposer.io.inputReady
  when(io.rotateStart) {
    assert(io.rotateReady, "bitwise decomposition frontend is unavailable")
  }

  io.centeredDigit := decomposer.io.centeredDigit
  io.busy := reorder.io.busy || decomposer.io.busy
  io.done := decomposer.io.done
}

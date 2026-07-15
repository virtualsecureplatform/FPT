package fpt

import chisel3._
import chisel3.util._

sealed trait BatchedCoefficientStorage

object BatchedCoefficientStorage {
  case object RegisterArray extends BatchedCoefficientStorage
  case object ReplicatedBanks extends BatchedCoefficientStorage
  case object BitwiseReplicatedBanks extends BatchedCoefficientStorage
}

final case class BatchedCmuxEngineConfig(
    engine: CmuxEngineConfig,
    batchContexts: Int,
    coefficientStorage: BatchedCoefficientStorage =
      BatchedCoefficientStorage.RegisterArray
) {
  require(batchContexts >= 2)
  require(
    engine.forwardSGen.isDefined && engine.inverseSGen.isDefined,
    "the batched engine requires continuous-flow SGen transforms"
  )
  val rows: Int = engine.externalProduct.rows
  val commandInterval: Int = rows * engine.forwardTransform.frameBeats
  val contextWidth: Int = TransformUtil.counterWidth(batchContexts)
}

/** Tagged, multi-context CMUX pipeline.
  *
  * Commands occupy only the forward row-stream interval. Command tags pass
  * through the continuous forward transform, double-buffered External Product
  * PISO, and parallel inverse transforms. The delayed inverse result then
  * updates the matching coefficient context. This is the batch-interleaved
  * structure needed for FPT's latency/throughput separation.
  */
final class BatchedCmuxEngine(val config: BatchedCmuxEngineConfig)
    extends Module {
  private val base = config.engine
  private val coefficientConfig = base.coefficient
  private val externalConfig = base.externalProduct
  private val contextWidth = config.contextWidth
  private val rowWidth = TransformUtil.counterWidth(externalConfig.rows)
  private val forwardTransactionBeats = externalConfig.rows *
    externalConfig.inputFrameBeats
  private val forwardTransactionBeatWidth = TransformUtil.counterWidth(
    forwardTransactionBeats
  )
  private val inverseBeatWidth = TransformUtil.counterWidth(
    base.inverseTransform.frameBeats
  )

  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val loadContext = Input(UInt(contextWidth.W))
    val loadValid = Input(Bool())
    val loadReady = Output(Bool())
    val load = Input(
      Vec(
        coefficientConfig.components,
        Vec(
          coefficientConfig.inverseLanes,
          UInt(coefficientConfig.torusWidth.W)
        )
      )
    )
    val loadDone = Output(Bool())
    val loadDoneContext = Output(UInt(contextWidth.W))

    val commandValid = Input(Bool())
    val commandReady = Output(Bool())
    val commandContext = Input(UInt(contextWidth.W))
    val exponent = Input(UInt(coefficientConfig.exponentWidth.W))

    val bootstrappingKey = Input(
      Vec(
        externalConfig.outputComponents,
        Vec(
          externalConfig.inputLanes,
          new ComplexSInt(externalConfig.bootstrappingKey.width)
        )
      )
    )
    val keyRow = Output(UInt(rowWidth.W))
    val keyPoint = Output(
      Vec(
        externalConfig.inputLanes,
        UInt(log2Ceil(externalConfig.points).W)
      )
    )
    val keyValid = Output(Bool())
    val keyFirst = Output(Bool())
    val keyContext = Output(UInt(contextWidth.W))

    val forwardTwistIndex = Output(
      Vec(base.forwardTransform.lanes, UInt(base.forwardTransform.logPoints.W))
    )
    val forwardTwist = Input(
      Vec(
        base.forwardTransform.lanes,
        new GaussTwiddle(base.forwardTransform.twiddleWidth)
      )
    )
    val inverseUntwistIndex = Output(
      Vec(base.inverseTransform.lanes, UInt(base.inverseTransform.logPoints.W))
    )
    val inverseUntwist = Input(
      Vec(
        base.inverseTransform.lanes,
        new GaussTwiddle(base.inverseTransform.twiddleWidth)
      )
    )

    val doneValid = Output(Bool())
    val doneContext = Output(UInt(contextWidth.W))

    val drainStart = Input(Bool())
    val drainStartReady = Output(Bool())
    val drainContext = Input(UInt(contextWidth.W))
    val drainValid = Output(Bool())
    val drainReady = Input(Bool())
    val drain = Output(
      Vec(
        coefficientConfig.components,
        Vec(
          coefficientConfig.inverseLanes,
          UInt(coefficientConfig.torusWidth.W)
        )
      )
    )
    val drainDone = Output(Bool())
    val drainDoneContext = Output(UInt(contextWidth.W))
    val contextBusy = Output(Vec(config.batchContexts, Bool()))
  })

  val coefficients: BatchedCmuxCoefficientStoreBase =
    config.coefficientStorage match {
      case BatchedCoefficientStorage.RegisterArray =>
        Module(
          new BatchedCmuxCoefficientStore(
            coefficientConfig,
            config.batchContexts
          )
        )
      case BatchedCoefficientStorage.ReplicatedBanks =>
        Module(
          new PrefetchedBatchedCmuxCoefficientStore(
            coefficientConfig,
            config.batchContexts
          )
        )
      case BatchedCoefficientStorage.BitwiseReplicatedBanks =>
        Module(
          new BitwisePrefetchedBatchedCmuxCoefficientStore(
            coefficientConfig,
            config.batchContexts,
            base.bitwiseBitsPerCycle.getOrElse(
              throw new IllegalArgumentException(
                "bitwise batched storage requires bitwiseBitsPerCycle"
              )
            )
          )
        )
    }
  val forward = Module(
    new SGenForwardTangentBackend(
      base.forwardTransform,
      base.forwardSGen.get
    )
  )
  val external = Module(
    new DoubleBufferedExternalProductAccumulator(
      externalConfig,
      contextWidth
    )
  )
  val inverses = Seq.fill(coefficientConfig.components) {
    Module(
      new SGenInverseTangentBackend(
        base.inverseTransform,
        base.inverseNormalizeShift,
        base.inverseSGen.get
      )
    )
  }

  coefficients.io.loadStart := io.loadStart
  coefficients.io.loadContext := io.loadContext
  coefficients.io.loadValid := io.loadValid
  coefficients.io.load := io.load
  io.loadReady := coefficients.io.loadReady
  io.loadDone := coefficients.io.loadDone
  io.loadDoneContext := coefficients.io.loadDoneContext

  val forwardTags = Module(
    new Queue(UInt(contextWidth.W), config.batchContexts + 2)
  )
  coefficients.io.commandValid := io.commandValid && forwardTags.io.enq.ready
  coefficients.io.commandContext := io.commandContext
  coefficients.io.exponent := io.exponent
  io.commandReady := coefficients.io.commandReady && forwardTags.io.enq.ready
  val commandFire = io.commandValid && io.commandReady
  forwardTags.io.enq.valid := commandFire
  forwardTags.io.enq.bits := io.commandContext

  forward.io.start := coefficients.io.transformStart
  forward.io.pairValid := coefficients.io.pairValid
  forward.io.coefficientLow := coefficients.io.coefficientLow
  forward.io.coefficientHigh := coefficients.io.coefficientHigh
  forward.io.twist := io.forwardTwist
  forward.io.fftTwiddle := 0.U.asTypeOf(forward.io.fftTwiddle)
  coefficients.io.pairReady := forward.io.pairReady
  io.forwardTwistIndex := forward.io.twistIndex

  val forwardTransactionBeat = RegInit(
    0.U(forwardTransactionBeatWidth.W)
  )
  val forwardFirst = forwardTransactionBeat === 0.U
  val forwardLast = forwardTransactionBeat ===
    (forwardTransactionBeats - 1).U
  external.io.inputValid := forward.io.outputValid && forwardTags.io.deq.valid
  external.io.inputFirst := external.io.inputValid && forwardFirst
  external.io.inputTag := forwardTags.io.deq.bits
  external.io.decomposition := forward.io.output
  external.io.bootstrappingKey := io.bootstrappingKey
  forward.io.outputReady := external.io.inputReady && forwardTags.io.deq.valid
  val forwardOutputFire = external.io.inputValid && external.io.inputReady
  forwardTags.io.deq.ready := forwardOutputFire && forwardLast
  when(forwardOutputFire) {
    when(forwardLast) {
      forwardTransactionBeat := 0.U
    }.otherwise {
      forwardTransactionBeat := forwardTransactionBeat + 1.U
    }
  }
  io.keyRow := external.io.keyRow
  io.keyPoint := external.io.pointIndex
  io.keyValid := external.io.inputValid
  io.keyFirst := external.io.inputFirst
  io.keyContext := Mux(
    forwardTags.io.deq.valid,
    forwardTags.io.deq.bits,
    0.U
  )

  // Pulse inverse start once when a PISO transaction reaches its first beat;
  // the beat itself remains held until SGen's input lead has elapsed.
  val inverseStartIssued = RegInit(false.B)
  val inverseStart = external.io.outputValid && external.io.outputFirst &&
    !inverseStartIssued
  when(inverseStart) { inverseStartIssued := true.B }

  val inverseTags = Module(
    new Queue(UInt(contextWidth.W), config.batchContexts + 2)
  )
  inverseTags.io.enq.valid := inverseStart
  inverseTags.io.enq.bits := external.io.outputTag
  when(inverseStart) {
    assert(inverseTags.io.enq.ready, "inverse tag queue overflow")
  }

  val allInverseInputReady = inverses.map(_.io.inputReady).reduce(_ && _)
  external.io.outputReady := allInverseInputReady
  val externalOutputFire = external.io.outputValid && external.io.outputReady
  val externalOutputBeat = RegInit(0.U(inverseBeatWidth.W))
  val externalOutputLast = externalOutputBeat ===
    (base.inverseTransform.frameBeats - 1).U
  when(externalOutputFire) {
    when(externalOutputLast) {
      externalOutputBeat := 0.U
      inverseStartIssued := false.B
    }.otherwise {
      externalOutputBeat := externalOutputBeat + 1.U
    }
  }
  for ((inverse, component) <- inverses.zipWithIndex) {
    inverse.io.start := inverseStart
    inverse.io.inputValid := external.io.outputValid && allInverseInputReady
    inverse.io.input := external.io.output(component)
    inverse.io.fftTwiddle := 0.U.asTypeOf(inverse.io.fftTwiddle)
    inverse.io.untwist := io.inverseUntwist
  }
  io.inverseUntwistIndex := inverses.head.io.untwistIndex

  val inverseOutputBeat = RegInit(0.U(inverseBeatWidth.W))
  val inverseOutputFirst = inverseOutputBeat === 0.U
  val inverseOutputLast = inverseOutputBeat ===
    (base.inverseTransform.frameBeats - 1).U
  val allInverseOutputValid = inverses.map(_.io.outputValid).reduce(_ && _)
  coefficients.io.updateValid := allInverseOutputValid &&
    inverseTags.io.deq.valid
  coefficients.io.updateFirst := coefficients.io.updateValid &&
    inverseOutputFirst
  coefficients.io.updateContext := inverseTags.io.deq.bits
  for ((inverse, component) <- inverses.zipWithIndex) {
    coefficients.io.updateLow(component) := inverse.io.coefficientLow
    coefficients.io.updateHigh(component) := inverse.io.coefficientHigh
    inverse.io.outputReady := coefficients.io.updateReady &&
      allInverseOutputValid && inverseTags.io.deq.valid
  }
  val inverseOutputFire = coefficients.io.updateValid &&
    coefficients.io.updateReady
  inverseTags.io.deq.ready := inverseOutputFire && inverseOutputLast
  when(inverseOutputFire) {
    when(inverseOutputLast) {
      inverseOutputBeat := 0.U
    }.otherwise {
      inverseOutputBeat := inverseOutputBeat + 1.U
    }
  }

  io.doneValid := coefficients.io.updateDone
  io.doneContext := coefficients.io.updateDoneContext
  io.contextBusy := coefficients.io.contextBusy

  coefficients.io.drainStart := io.drainStart
  io.drainStartReady := coefficients.io.drainStartReady
  coefficients.io.drainContext := io.drainContext
  coefficients.io.drainReady := io.drainReady
  io.drainValid := coefficients.io.drainValid
  io.drain := coefficients.io.drain
  io.drainDone := coefficients.io.drainDone
  io.drainDoneContext := coefficients.io.drainDoneContext
}

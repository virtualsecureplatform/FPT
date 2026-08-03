package fpt

import chisel3._
import chisel3.util._

final case class BufferedBlindRotateConfig(
    blindRotate: BatchedBlindRotateEngineConfig,
    keyLoadLanes: Int
) {
  require(
    blindRotate.cmux.decoupledBootstrappingKey,
    "the buffered Blind Rotate requires a decoupled key interface"
  )

  val keyBuffer: BootstrappingKeyBufferConfig =
    BootstrappingKeyBufferConfig(
      blindRotate.cmux.engine.externalProduct,
      blindRotate.batchContexts,
      blindRotate.domainDimension,
      keyLoadLanes
    )
  val keyMemoryDepth: Int = 2 * keyBuffer.wordsPerCoefficient
  val keyMemoryWordBits: Int =
    keyBuffer.complexValuesPerRead *
      2 * keyBuffer.externalProduct.bootstrappingKey.width
  val keyMemoryModuleName: String =
    s"memory_${keyMemoryDepth}x${keyMemoryWordBits}"
}

private final class BufferedKeyReadRequest(
    dimensionWidth: Int,
    rowWidth: Int,
    beatWidth: Int
) extends Bundle {
  val index = UInt(dimensionWidth.W)
  val row = UInt(rowWidth.W)
  val beat = UInt(beatWidth.W)
}

/** Sample-extracted Blind Rotate with the paper's two-coefficient key cache.
  *
  * The host fills spectral key coefficients through a narrow ordered stream.
  * The ping-pong cache expands that stream to the full External Product width
  * and overlaps coefficient i + 1 loading with coefficient i consumption by
  * the complete ciphertext batch.
  */
final class BufferedBatchedBlindRotateSampleExtractEngine(
    val config: BufferedBlindRotateConfig
) extends Module {
  private val blindConfig = config.blindRotate
  private val base = blindConfig.cmux.engine
  private val coefficient = base.coefficient
  private val external = base.externalProduct
  private val contextWidth = blindConfig.contextWidth
  private val rowWidth = TransformUtil.counterWidth(external.rows)

  val io = IO(new Bundle {
    val inputStart = Input(Bool())
    val inputStartReady = Output(Bool())
    val inputContext = Input(UInt(contextWidth.W))
    val testVector = Input(UInt(coefficient.torusWidth.W))
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val inputCoefficient = Input(UInt(blindConfig.inputTorusWidth.W))
    val inputDone = Output(Bool())
    val inputDoneContext = Output(UInt(contextWidth.W))
    val contextInitialized = Output(
      Vec(blindConfig.batchContexts, Bool())
    )

    val keyLoadStart = Input(Bool())
    val keyLoadStartReady = Output(Bool())
    val keyLoadIndex = Input(UInt(blindConfig.dimensionWidth.W))
    val keyLoadValid = Input(Bool())
    val keyLoadReady = Output(Bool())
    val keyLoad = Input(
      Vec(
        config.keyBuffer.loadLanes,
        new ComplexSInt(external.bootstrappingKey.width)
      )
    )
    val keyLoadDone = Output(Bool())
    val keyLoadDoneIndex = Output(UInt(blindConfig.dimensionWidth.W))
    val keyBankValid = Output(Vec(2, Bool()))
    val keyBankIndex = Output(
      Vec(2, UInt(blindConfig.dimensionWidth.W))
    )

    val runStart = Input(Bool())
    val runReady = Output(Bool())
    val active = Output(Bool())
    val computeDone = Output(Bool())
    val contextDoneValid = Output(Bool())
    val contextDone = Output(UInt(contextWidth.W))
    val contextComplete = Output(
      Vec(blindConfig.batchContexts, Bool())
    )

    val keyValid = Output(Bool())
    val keyFirst = Output(Bool())
    val keyIndex = Output(UInt(blindConfig.dimensionWidth.W))
    val keyContext = Output(UInt(contextWidth.W))
    val keyExponent = Output(UInt(blindConfig.exponentWidth.W))
    val keyRow = Output(UInt(rowWidth.W))
    val keyPoint = Output(
      Vec(external.inputLanes, UInt(log2Ceil(external.points).W))
    )

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

    val resultValid = Output(Bool())
    val resultReady = Input(Bool())
    val result = Output(UInt(coefficient.torusWidth.W))
    val resultContext = Output(UInt(contextWidth.W))
    val resultLast = Output(Bool())
    val done = Output(Bool())
  })

  val blindRotate = Module(
    new BatchedBlindRotateSampleExtractEngine(blindConfig)
  )
  val keyBuffer = Module(
    new BootstrappingKeyPingPongBuffer(config.keyBuffer)
  )

  keyBuffer.io.loadStart := io.keyLoadStart
  io.keyLoadStartReady := keyBuffer.io.loadStartReady
  keyBuffer.io.loadIndex := io.keyLoadIndex
  keyBuffer.io.loadValid := io.keyLoadValid
  io.keyLoadReady := keyBuffer.io.loadReady
  keyBuffer.io.load := io.keyLoad
  io.keyLoadDone := keyBuffer.io.loadDone
  io.keyLoadDoneIndex := keyBuffer.io.loadDoneIndex
  io.keyBankValid := keyBuffer.io.bankValid
  io.keyBankIndex := keyBuffer.io.bankIndex

  // Cut the forward-tag/transaction counter from the key BRAM address
  // decoder. Two entries preserve one request per cycle without a
  // combinational ready path through the key cache.
  private val keyReadRequests = Module(
    new Queue(
      new BufferedKeyReadRequest(
        blindConfig.dimensionWidth,
        config.keyBuffer.rowWidth,
        config.keyBuffer.beatWidth
      ),
      entries = 2
    )
  )
  keyReadRequests.io.enq.valid := blindRotate.io.keyReadRequestValid
  blindRotate.io.keyReadRequestReady := keyReadRequests.io.enq.ready
  keyReadRequests.io.enq.bits.index := blindRotate.io.keyReadRequestIndex
  keyReadRequests.io.enq.bits.row := blindRotate.io.keyReadRequestRow
  keyReadRequests.io.enq.bits.beat := blindRotate.io.keyReadRequestBeat

  keyBuffer.io.readRequestValid := keyReadRequests.io.deq.valid
  keyReadRequests.io.deq.ready := keyBuffer.io.readRequestReady
  keyBuffer.io.readIndex := keyReadRequests.io.deq.bits.index
  keyBuffer.io.readRow := keyReadRequests.io.deq.bits.row
  keyBuffer.io.readBeat := keyReadRequests.io.deq.bits.beat
  blindRotate.io.bootstrappingKeyValid := keyBuffer.io.readResponseValid
  keyBuffer.io.readResponseReady := blindRotate.io.bootstrappingKeyReady
  blindRotate.io.bootstrappingKey := keyBuffer.io.readResponse

  when(keyBuffer.io.readResponseValid) {
    assert(
      keyBuffer.io.readResponseIndex === blindRotate.io.keyIndex,
      "buffered bootstrapping-key index does not match Blind Rotate"
    )
    assert(
      keyBuffer.io.readResponseRow === blindRotate.io.keyRow,
      "buffered bootstrapping-key row does not match Blind Rotate"
    )
    assert(
      blindRotate.io.keyPoint(0) ===
        keyBuffer.io.readResponseBeat * external.inputLanes.U,
      "buffered bootstrapping-key beat does not match Blind Rotate"
    )
  }

  blindRotate.io.inputStart := io.inputStart
  io.inputStartReady := blindRotate.io.inputStartReady
  blindRotate.io.inputContext := io.inputContext
  blindRotate.io.testVector := io.testVector
  blindRotate.io.inputValid := io.inputValid
  io.inputReady := blindRotate.io.inputReady
  blindRotate.io.inputCoefficient := io.inputCoefficient
  io.inputDone := blindRotate.io.inputDone
  io.inputDoneContext := blindRotate.io.inputDoneContext
  io.contextInitialized := blindRotate.io.contextInitialized

  val firstKeyReady = keyBuffer.io.bankValid.zip(keyBuffer.io.bankIndex)
    .map { case (valid, index) => valid && index === 0.U }
    .reduce(_ || _)
  io.runReady := blindRotate.io.runReady && firstKeyReady
  blindRotate.io.runStart := io.runStart && io.runReady
  when(io.runStart) {
    assert(io.runReady, "buffered Blind Rotate started before key zero loaded")
  }
  io.active := blindRotate.io.active
  io.computeDone := blindRotate.io.computeDone
  io.contextDoneValid := blindRotate.io.contextDoneValid
  io.contextDone := blindRotate.io.contextDone
  io.contextComplete := blindRotate.io.contextComplete

  io.keyValid := blindRotate.io.keyValid
  io.keyFirst := blindRotate.io.keyFirst
  io.keyIndex := blindRotate.io.keyIndex
  io.keyContext := blindRotate.io.keyContext
  io.keyExponent := blindRotate.io.keyExponent
  io.keyRow := blindRotate.io.keyRow
  io.keyPoint := blindRotate.io.keyPoint

  blindRotate.io.forwardTwist := io.forwardTwist
  io.forwardTwistIndex := blindRotate.io.forwardTwistIndex
  blindRotate.io.inverseUntwist := io.inverseUntwist
  io.inverseUntwistIndex := blindRotate.io.inverseUntwistIndex

  io.resultValid := blindRotate.io.resultValid
  blindRotate.io.resultReady := io.resultReady
  io.result := blindRotate.io.result
  io.resultContext := blindRotate.io.resultContext
  io.resultLast := blindRotate.io.resultLast
  io.done := blindRotate.io.done
}

package fpt

import chisel3._
import chisel3.util._

/** Batched Blind Rotate with automatic index-zero sample extraction.
  *
  * The compute engine retains all completed TRLWE accumulators. After the
  * final CMUX completes, this wrapper drains contexts in ascending order and
  * returns one sample-extracted TLWE stream. `resultLast` marks only the last
  * coefficient of the complete batch, matching HOGE's batched output
  * boundary; `resultContext` identifies every coefficient's source context.
  */
final class BatchedBlindRotateSampleExtractEngine(
    val config: BatchedBlindRotateEngineConfig
) extends Module {
  private val base = config.cmux.engine
  private val coefficient = base.coefficient
  private val external = base.externalProduct
  private val contextWidth = config.contextWidth
  private val rowWidth = TransformUtil.counterWidth(external.rows)

  val io = IO(new Bundle {
    val inputStart = Input(Bool())
    val inputStartReady = Output(Bool())
    val inputContext = Input(UInt(contextWidth.W))
    val testVector = Input(UInt(coefficient.torusWidth.W))
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val inputCoefficient = Input(UInt(config.inputTorusWidth.W))
    val inputDone = Output(Bool())
    val inputDoneContext = Output(UInt(contextWidth.W))
    val contextInitialized = Output(Vec(config.batchContexts, Bool()))

    val runStart = Input(Bool())
    val runReady = Output(Bool())
    val active = Output(Bool())
    val computeDone = Output(Bool())
    val contextDoneValid = Output(Bool())
    val contextDone = Output(UInt(contextWidth.W))
    val contextComplete = Output(Vec(config.batchContexts, Bool()))

    val bootstrappingKey = Input(
      Vec(
        external.outputComponents,
        Vec(
          external.inputLanes,
          new ComplexSInt(external.bootstrappingKey.width)
        )
      )
    )
    val keyValid = Output(Bool())
    val keyFirst = Output(Bool())
    val keyIndex = Output(UInt(config.dimensionWidth.W))
    val keyContext = Output(UInt(contextWidth.W))
    val keyExponent = Output(UInt(config.exponentWidth.W))
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

  private val blindRotate = Module(new BatchedBlindRotateEngine(config))
  private val sampleExtract = Module(
    new SampleExtractIndexZero(
      SampleExtractConfig(
        coefficient.polynomialSize,
        coefficient.inverseLanes,
        coefficient.torusWidth
      )
    )
  )

  val extracting = RegInit(false.B)
  val extractionContext = RegInit(0.U(contextWidth.W))
  val drainInFlight = RegInit(false.B)

  io.inputStartReady := blindRotate.io.inputStartReady && !extracting
  blindRotate.io.inputStart := io.inputStart && io.inputStartReady
  blindRotate.io.inputContext := io.inputContext
  blindRotate.io.testVector := io.testVector
  io.inputReady := blindRotate.io.inputReady && !extracting
  blindRotate.io.inputValid := io.inputValid && io.inputReady
  blindRotate.io.inputCoefficient := io.inputCoefficient
  io.inputDone := blindRotate.io.inputDone
  io.inputDoneContext := blindRotate.io.inputDoneContext
  io.contextInitialized := blindRotate.io.contextInitialized
  when(io.inputStart) {
    assert(io.inputStartReady, "Blind Rotate input started during extraction")
  }
  when(io.inputValid) {
    assert(io.inputReady, "Blind Rotate input presented while unavailable")
  }

  io.runReady := blindRotate.io.runReady && !extracting
  blindRotate.io.runStart := io.runStart && io.runReady
  when(io.runStart) {
    assert(io.runReady, "Blind Rotate run started while unavailable")
  }
  io.computeDone := blindRotate.io.done
  io.contextDoneValid := blindRotate.io.contextDoneValid
  io.contextDone := blindRotate.io.contextDone
  io.contextComplete := blindRotate.io.contextComplete

  blindRotate.io.bootstrappingKey := io.bootstrappingKey
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

  when(blindRotate.io.done) {
    extracting := true.B
    extractionContext := 0.U
    drainInFlight := false.B
  }

  val startExtraction = extracting && !drainInFlight &&
    sampleExtract.io.inputStartReady && blindRotate.io.drainStartReady
  sampleExtract.io.inputStart := startExtraction
  blindRotate.io.drainStart := startExtraction
  blindRotate.io.drainContext := extractionContext
  when(startExtraction) {
    drainInFlight := true.B
  }

  sampleExtract.io.inputValid := blindRotate.io.drainValid
  blindRotate.io.drainReady := sampleExtract.io.inputReady
  for (lane <- 0 until coefficient.inverseLanes) {
    sampleExtract.io.inputA(lane) := blindRotate.io.drain(0)(lane)
    sampleExtract.io.inputB(lane) := blindRotate.io.drain(1)(lane)
  }
  when(blindRotate.io.drainDone) {
    assert(drainInFlight, "Blind Rotate drain completed without a request")
    assert(
      blindRotate.io.drainDoneContext === extractionContext,
      "Blind Rotate drained the wrong extraction context"
    )
    drainInFlight := false.B
  }

  val finalContext = extractionContext === (config.batchContexts - 1).U
  io.resultValid := sampleExtract.io.outputValid
  sampleExtract.io.outputReady := io.resultReady
  io.result := sampleExtract.io.output
  io.resultContext := extractionContext
  io.resultLast := sampleExtract.io.outputLast && finalContext
  io.done := sampleExtract.io.done && finalContext
  io.active := blindRotate.io.active || extracting

  when(sampleExtract.io.done) {
    // Avoid an unguarded immediate assertion from a dynamic Vec read.
    val selectedComplete = blindRotate.io.contextComplete.zipWithIndex
      .map { case (flag, context) =>
        (extractionContext === context.U) && flag
      }
      .reduce(_ || _)
    assert(
      selectedComplete,
      "sample extraction completed for an incomplete context"
    )
    when(finalContext) {
      extracting := false.B
    }.otherwise {
      extractionContext := extractionContext + 1.U
    }
  }
}

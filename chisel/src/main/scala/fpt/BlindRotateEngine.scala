package fpt

import chisel3._
import chisel3.util._

final case class BatchedBlindRotateEngineConfig(
    cmux: BatchedCmuxEngineConfig,
    domainDimension: Int,
    inputTorusWidth: Int = 32
) {
  require(domainDimension >= 1)
  require(inputTorusWidth >= 2)
  require(cmux.engine.coefficient.components == 2)
  require(
    inputTorusWidth > cmux.engine.coefficient.exponentWidth,
    "the input Torus must retain bits below the Blind Rotate exponent"
  )

  val batchContexts: Int = cmux.batchContexts
  val contextWidth: Int = cmux.contextWidth
  val exponentWidth: Int = cmux.engine.coefficient.exponentWidth
  val dimensionWidth: Int = TransformUtil.counterWidth(domainDimension)
  val inputBeats: Int = domainDimension + 1
  val modulusShift: Int = inputTorusWidth - exponentWidth
  val roundOffset: BigInt = BigInt(1) << (modulusShift - 1)
}

/** Batched fixed-point Blind Rotate controller around the continuous CMUX.
  *
  * Each input transaction contains all raw TLWE mask coefficients followed by
  * its body. The loader implements TFHEpp's corrected BR modulus switch,
  * stores one exponent per domain-key coefficient, and initializes the target
  * accumulator as `X^bbar` times a constant test-vector polynomial. During a
  * run, idle contexts are selected round-robin and advance one key index only
  * after their prior CMUX completes.
  *
  * The bootstrapping key remains external because a real key is much larger
  * than the datapath. `keyIndex`, `keyRow`, and `keyPoint` are aligned with the
  * exact cycle on which the CMUX consumes each spectrum word.
  */
final class BatchedBlindRotateEngine(
    val config: BatchedBlindRotateEngineConfig
) extends Module {
  import TransformUtil._

  private val cmuxConfig = config.cmux
  private val base = cmuxConfig.engine
  private val coefficient = base.coefficient
  private val external = base.externalProduct
  private val contextWidth = config.contextWidth
  private val inputIndexWidth = counterWidth(config.inputBeats)
  private val polynomialBeatWidth = counterWidth(coefficient.polynomialBeats)
  private val exponentAddressWidth = counterWidth(
    config.batchContexts * config.domainDimension
  )
  private val rowWidth = counterWidth(external.rows)

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
    val done = Output(Bool())
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
    val bootstrappingKeyValid = Input(Bool())
    val bootstrappingKeyReady = Output(Bool())
    val keyReadRequestValid = Output(Bool())
    val keyReadRequestReady = Input(Bool())
    val keyReadRequestIndex = Output(UInt(config.dimensionWidth.W))
    val keyReadRequestContext = Output(UInt(contextWidth.W))
    val keyReadRequestRow = Output(UInt(rowWidth.W))
    val keyReadRequestBeat = Output(
      UInt(counterWidth(external.inputFrameBeats).W)
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
  })

  val cmux = Module(new BatchedCmuxEngine(cmuxConfig))
  val exponentMemory = SyncReadMem(
    config.batchContexts * config.domainDimension,
    UInt(config.exponentWidth.W)
  )

  def nextContext(context: UInt): UInt =
    Mux(
      context === (config.batchContexts - 1).U,
      0.U,
      context + 1.U
    )

  def exponentAddress(context: UInt, dimension: UInt): UInt =
    (context * config.domainDimension.U + dimension)(
      exponentAddressWidth - 1,
      0
    )

  def modulusSwitch(value: UInt): UInt =
    ((value +& config.roundOffset.U)(config.inputTorusWidth - 1, 0) >>
      config.modulusShift)(config.exponentWidth - 1, 0)

  val initialized = RegInit(VecInit(Seq.fill(config.batchContexts)(false.B)))
  val complete = RegInit(VecInit(Seq.fill(config.batchContexts)(false.B)))
  io.contextInitialized := initialized
  io.contextComplete := complete

  val loadStates = Enum(5)
  val loadIdle = loadStates(0)
  val collectInput = loadStates(1)
  val startAccumulator = loadStates(2)
  val streamAccumulator = loadStates(3)
  val waitAccumulator = loadStates(4)
  val loadState = RegInit(loadIdle)
  val loaderContext = RegInit(0.U(contextWidth.W))
  val loaderTestVector = RegInit(0.U(coefficient.torusWidth.W))
  val loaderExponent = RegInit(0.U(config.exponentWidth.W))
  val inputIndex = RegInit(0.U(inputIndexWidth.W))
  val correction = RegInit(0.U(config.inputTorusWidth.W))
  val accumulatorBeat = RegInit(0.U(polynomialBeatWidth.W))

  val running = RegInit(false.B)
  io.active := running
  val validInputContext = io.inputContext < config.batchContexts.U
  val selectedInputBusy = Mux(
    validInputContext,
    cmux.io.contextBusy(io.inputContext),
    true.B
  )
  io.inputStartReady := loadState === loadIdle && !running &&
    validInputContext && !selectedInputBusy
  io.inputReady := loadState === collectInput
  io.inputDone := loadState === waitAccumulator && cmux.io.loadDone
  io.inputDoneContext := loaderContext

  cmux.io.loadStart := loadState === startAccumulator
  cmux.io.loadContext := loaderContext
  cmux.io.loadValid := loadState === streamAccumulator

  val negativeTestVector =
    (0.U((coefficient.torusWidth + 1).W) - loaderTestVector)(
      coefficient.torusWidth - 1,
      0
    )
  val exponentLow = loaderExponent(log2Ceil(coefficient.polynomialSize) - 1, 0)
  val exponentHigh = loaderExponent(config.exponentWidth - 1)
  for (component <- 0 until coefficient.components) {
    for (lane <- 0 until coefficient.inverseLanes) {
      val index = accumulatorBeat * coefficient.inverseLanes.U + lane.U
      val negate = exponentHigh ^ (index < exponentLow)
      cmux.io.load(component)(lane) :=
        (if (component == 0) 0.U
         else Mux(negate, negativeTestVector, loaderTestVector))
    }
  }

  when(io.inputStart) {
    assert(io.inputStartReady, "Blind Rotate input started while unavailable")
    loaderContext := io.inputContext
    loaderTestVector := io.testVector
    inputIndex := 0.U
    correction := 0.U
    initialized(io.inputContext) := false.B
    complete(io.inputContext) := false.B
    loadState := collectInput
  }
  when(io.inputValid) {
    assert(io.inputReady, "Blind Rotate input data presented while unavailable")
  }

  val inputFire = io.inputValid && io.inputReady
  val maskInput = inputIndex < config.domainDimension.U
  val switchedMask = modulusSwitch(io.inputCoefficient)
  val reconstructedMask =
    (switchedMask.pad(config.inputTorusWidth) << config.modulusShift)(
      config.inputTorusWidth - 1,
      0
    )
  val maskError = io.inputCoefficient - reconstructedMask

  val correctionNegative = correction(config.inputTorusWidth - 1)
  val negativeCorrection =
    (0.U((config.inputTorusWidth + 1).W) - correction)(
      config.inputTorusWidth - 1,
      0
    )
  val correctionMagnitude = Mux(
    correctionNegative,
    negativeCorrection,
    correction
  )
  val correctionHalfMagnitude = correctionMagnitude >> 1
  val negativeCorrectionHalf =
    (0.U((config.inputTorusWidth + 1).W) - correctionHalfMagnitude)(
      config.inputTorusWidth - 1,
      0
    )
  val correctionHalf = Mux(
    correctionNegative,
    negativeCorrectionHalf,
    correctionHalfMagnitude
  )
  val adjustedBody =
    (io.inputCoefficient - correctionHalf +& config.roundOffset.U)(
      config.inputTorusWidth - 1,
      0
    )
  val switchedBody =
    (adjustedBody >> config.modulusShift)(config.exponentWidth - 1, 0)
  val initialExponent =
    (0.U((config.exponentWidth + 1).W) - switchedBody)(
      config.exponentWidth - 1,
      0
    )

  when(inputFire) {
    when(maskInput) {
      exponentMemory.write(
        exponentAddress(loaderContext, inputIndex),
        switchedMask
      )
      correction := correction + maskError
      inputIndex := inputIndex + 1.U
    }.otherwise {
      loaderExponent := initialExponent
      accumulatorBeat := 0.U
      loadState := startAccumulator
    }
  }
  when(loadState === startAccumulator) {
    loadState := streamAccumulator
  }
  when(loadState === streamAccumulator && cmux.io.loadReady) {
    when(accumulatorBeat === (coefficient.polynomialBeats - 1).U) {
      accumulatorBeat := 0.U
      loadState := waitAccumulator
    }.otherwise {
      accumulatorBeat := accumulatorBeat + 1.U
    }
  }
  when(loadState === waitAccumulator && cmux.io.loadDone) {
    initialized(loaderContext) := true.B
    loadState := loadIdle
  }

  val allInitialized = initialized.asUInt.andR
  io.runReady := !running && loadState === loadIdle && allInitialized
  when(io.runStart) {
    assert(io.runReady, "Blind Rotate run started before all contexts loaded")
  }

  val progress = RegInit(
    VecInit(Seq.fill(config.batchContexts)(0.U(config.dimensionWidth.W)))
  )
  val allIssued = RegInit(VecInit(Seq.fill(config.batchContexts)(false.B)))
  val finalInFlight = RegInit(
    VecInit(Seq.fill(config.batchContexts)(false.B))
  )
  val inFlightKeyIndex = RegInit(
    VecInit(Seq.fill(config.batchContexts)(0.U(config.dimensionWidth.W)))
  )
  val inFlightExponent = RegInit(
    VecInit(Seq.fill(config.batchContexts)(0.U(config.exponentWidth.W)))
  )
  val scanContext = RegInit(0.U(contextWidth.W))
  val readRequestValid = RegInit(false.B)
  val readRequestAddressInput = WireDefault(0.U(exponentAddressWidth.W))
  val readRequestAddressEnable = WireDefault(false.B)
  val readRequestAddress =
    if (cmuxConfig.useSynchronousExternalProductMemory) {
      val addressRegister = Module(
        new PhysicalCutRegister(exponentAddressWidth)
      )
      addressRegister.io.clock := clock
      addressRegister.io.enable := readRequestAddressEnable
      addressRegister.io.inputData := readRequestAddressInput
      addressRegister.io.outputData
    } else {
      0.U(exponentAddressWidth.W)
    }
  val readRequestContext = RegInit(0.U(contextWidth.W))
  val readRequestDimension = RegInit(0.U(config.dimensionWidth.W))
  val readPending = RegInit(false.B)
  val pendingContext = RegInit(0.U(contextWidth.W))
  val pendingDimension = RegInit(0.U(config.dimensionWidth.W))
  val candidateValid = RegInit(false.B)
  val candidateContext = RegInit(0.U(contextWidth.W))
  val candidateDimension = RegInit(0.U(config.dimensionWidth.W))
  val candidateExponent = RegInit(0.U(config.exponentWidth.W))

  val everyContextIssued = allIssued.asUInt.andR
  val readExponent = Wire(UInt(config.exponentWidth.W))
  if (cmuxConfig.useSynchronousExternalProductMemory) {
    when(
      running && !candidateValid && !readPending && !readRequestValid &&
        !everyContextIssued
    ) {
      when(!allIssued(scanContext)) {
        // Register the complete exponent-memory request in the physical
        // implementation. In particular, this keeps the dynamic
        // progress-vector select and context-by-dimension address arithmetic
        // off the BRAM address pins.
        readRequestAddressInput := exponentAddress(
          scanContext,
          progress(scanContext)
        )
        readRequestAddressEnable := true.B
        readRequestContext := scanContext
        readRequestDimension := progress(scanContext)
        readRequestValid := true.B
        scanContext := nextContext(scanContext)
      }.otherwise {
        scanContext := nextContext(scanContext)
      }
    }
    readExponent := exponentMemory.read(
      readRequestAddress,
      readRequestValid
    )
    when(readRequestValid) {
      pendingContext := readRequestContext
      pendingDimension := readRequestDimension
      readPending := true.B
      readRequestValid := false.B
    }
  } else {
    val readEnable = WireDefault(false.B)
    val readAddress = WireDefault(0.U(exponentAddressWidth.W))
    when(running && !candidateValid && !readPending && !everyContextIssued) {
      when(!allIssued(scanContext) && !cmux.io.contextBusy(scanContext)) {
        readEnable := true.B
        readAddress := exponentAddress(scanContext, progress(scanContext))
      }.otherwise {
        scanContext := nextContext(scanContext)
      }
    }
    readExponent := exponentMemory.read(readAddress, readEnable)
    when(readEnable) {
      pendingContext := scanContext
      pendingDimension := progress(scanContext)
      readPending := true.B
      scanContext := nextContext(scanContext)
    }
  }
  when(readPending) {
    candidateContext := pendingContext
    candidateDimension := pendingDimension
    candidateExponent := readExponent
    candidateValid := true.B
    readPending := false.B
  }

  // The physical address stage may prefetch the next exponent while that
  // context's previous CMUX is still retiring. Hold the candidate locally
  // until the context becomes available; this hides the extra BRAM-address
  // register without changing command order.
  cmux.io.commandValid := running && candidateValid &&
    !cmux.io.contextBusy(candidateContext)
  cmux.io.commandContext := candidateContext
  cmux.io.exponent := candidateExponent
  val commandFire = cmux.io.commandValid && cmux.io.commandReady
  val candidateFinal =
    candidateDimension === (config.domainDimension - 1).U
  when(commandFire) {
    inFlightKeyIndex(candidateContext) := candidateDimension
    inFlightExponent(candidateContext) := candidateExponent
    candidateValid := false.B
    when(candidateFinal) {
      allIssued(candidateContext) := true.B
      finalInFlight(candidateContext) := true.B
    }.otherwise {
      progress(candidateContext) := candidateDimension + 1.U
    }
  }

  val completingFinal = cmux.io.doneValid &&
    finalInFlight(cmux.io.doneContext)
  io.contextDoneValid := completingFinal
  io.contextDone := cmux.io.doneContext
  val completionCount = PopCount(complete)
  io.done := completingFinal &&
    completionCount === (config.batchContexts - 1).U
  when(cmux.io.doneValid) {
    finalInFlight(cmux.io.doneContext) := false.B
    when(finalInFlight(cmux.io.doneContext)) {
      complete(cmux.io.doneContext) := true.B
    }
  }
  when(io.done) {
    running := false.B
  }

  when(io.runStart && io.runReady) {
    running := true.B
    scanContext := 0.U
    readRequestValid := false.B
    readPending := false.B
    candidateValid := false.B
    for (context <- 0 until config.batchContexts) {
      progress(context) := 0.U
      allIssued(context) := false.B
      finalInFlight(context) := false.B
      complete(context) := false.B
    }
  }

  cmux.io.bootstrappingKey := io.bootstrappingKey
  cmux.io.bootstrappingKeyValid := io.bootstrappingKeyValid
  io.bootstrappingKeyReady := cmux.io.bootstrappingKeyReady
  cmux.io.keyReadRequestReady := io.keyReadRequestReady
  io.keyReadRequestValid := cmux.io.keyReadRequestValid
  io.keyReadRequestContext := cmux.io.keyReadRequestContext
  io.keyReadRequestIndex := inFlightKeyIndex(
    cmux.io.keyReadRequestContext
  )
  io.keyReadRequestRow := cmux.io.keyReadRequestRow
  io.keyReadRequestBeat := cmux.io.keyReadRequestBeat
  cmux.io.forwardTwist := io.forwardTwist
  cmux.io.inverseUntwist := io.inverseUntwist
  io.forwardTwistIndex := cmux.io.forwardTwistIndex
  io.inverseUntwistIndex := cmux.io.inverseUntwistIndex
  io.keyValid := cmux.io.keyValid
  io.keyFirst := cmux.io.keyFirst
  io.keyContext := cmux.io.keyContext
  io.keyIndex := inFlightKeyIndex(cmux.io.keyContext)
  io.keyExponent := inFlightExponent(cmux.io.keyContext)
  io.keyRow := cmux.io.keyRow
  io.keyPoint := cmux.io.keyPoint

  val validDrainContext = io.drainContext < config.batchContexts.U
  val selectedComplete = Mux(
    validDrainContext,
    complete(io.drainContext),
    false.B
  )
  io.drainStartReady := !running && selectedComplete &&
    cmux.io.drainStartReady
  cmux.io.drainStart := io.drainStart && io.drainStartReady
  cmux.io.drainContext := io.drainContext
  cmux.io.drainReady := io.drainReady
  when(io.drainStart) {
    assert(io.drainStartReady, "Blind Rotate drain started while unavailable")
  }
  io.drainValid := cmux.io.drainValid
  io.drain := cmux.io.drain
  io.drainDone := cmux.io.drainDone
  io.drainDoneContext := cmux.io.drainDoneContext
}

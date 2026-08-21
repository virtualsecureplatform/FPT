package fpt

import chisel3._
import chisel3.util._

/** Fixed-size DataMover and accelerator sequencer for one Set-II batch. */
final class FptBlindRotateKernelSequencer(
    val config: BufferedBlindRotateConfig
) extends Module {
  private val blind = config.blindRotate
  private val coefficient = blind.cmux.engine.coefficient
  private val external = blind.cmux.engine.externalProduct
  private val contexts = blind.batchContexts
  private val dimension = blind.domainDimension
  private val contextWidth = blind.contextWidth
  private val dimensionWidth = blind.dimensionWidth
  private val keyWidth = external.bootstrappingKey.width

  require(contexts == 16, "the Vitis kernel has a fixed 16-context batch")
  require(dimension == 630, "the Vitis kernel implements Paper Set-II")
  require(coefficient.torusWidth == 32)
  require(blind.inputTorusWidth == 32)
  require(config.keyBuffer.loadLanes == 16)
  require(keyWidth == 27)
  require(config.keyBuffer.loadBeatsPerCoefficient == 256)

  // One test-vector word, `dimension` TLWE mask words, and one TLWE body.
  private val inputWordsPerContext = dimension + 2
  private val inputWords = contexts * inputWordsPerContext
  private val inputBytes = inputWords * 4
  private val outputWords = contexts * (coefficient.polynomialSize + 1)
  private val outputBytes = outputWords * 4
  private val keyWords = dimension * config.keyBuffer.loadBeatsPerCoefficient
  private val keyFirstCoefficients = 511
  private val keyFirstWords =
    keyFirstCoefficients * config.keyBuffer.loadBeatsPerCoefficient
  private val keyFirstBytes = keyFirstWords * 64
  private val keySecondBytes = (keyWords - keyFirstWords) * 64

  val io = IO(new Bundle {
    val start = Input(Bool())
    val idle = Output(Bool())
    val done = Output(Bool())
    val ready = Output(Bool())
    val status = Output(UInt(32.W))
    val inputPointer = Input(UInt(64.W))
    val keyLowPointer = Input(UInt(64.W))
    val keyHighPointer = Input(UInt(64.W))
    val outputPointer = Input(UInt(64.W))

    val inputCommand = Decoupled(UInt(104.W))
    val keyLowCommand = Decoupled(UInt(104.W))
    val keyHighCommand = Decoupled(UInt(104.W))
    val outputCommand = Decoupled(UInt(104.W))
    val inputData = Flipped(Decoupled(UInt(512.W)))
    val keyLowData = Flipped(Decoupled(UInt(512.W)))
    val keyHighData = Flipped(Decoupled(UInt(512.W)))
    val outputData = Decoupled(UInt(32.W))
    val outputLast = Output(Bool())

    val inputStatus = Flipped(Valid(UInt(8.W)))
    val keyLowStatus = Flipped(Valid(UInt(8.W)))
    val keyHighStatus = Flipped(Valid(UInt(8.W)))
    val outputStatus = Flipped(Valid(UInt(8.W)))
    val inputError = Input(Bool())
    val keyLowError = Input(Bool())
    val keyHighError = Input(Bool())
    val outputError = Input(Bool())

    val coreInputStart = Output(Bool())
    val coreInputStartReady = Input(Bool())
    val coreInputContext = Output(UInt(contextWidth.W))
    val coreTestVector = Output(UInt(32.W))
    val coreInputValid = Output(Bool())
    val coreInputReady = Input(Bool())
    val coreInputCoefficient = Output(UInt(32.W))
    val coreInputDone = Input(Bool())
    val coreInputDoneContext = Input(UInt(contextWidth.W))

    val coreKeyLoadStart = Output(Bool())
    val coreKeyLoadStartReady = Input(Bool())
    val coreKeyLoadIndex = Output(UInt(dimensionWidth.W))
    val coreKeyLoadValid = Output(Bool())
    val coreKeyLoadReady = Input(Bool())
    val coreKeyLoad = Output(Vec(16, new ComplexSInt(keyWidth)))
    val coreKeyLoadDone = Input(Bool())
    val coreKeyLoadDoneIndex = Input(UInt(dimensionWidth.W))

    val coreRunStart = Output(Bool())
    val coreRunReady = Input(Bool())
    val coreDone = Input(Bool())
    val coreResultValid = Input(Bool())
    val coreResultReady = Output(Bool())
    val coreResult = Input(UInt(32.W))
    val coreResultLast = Input(Bool())
  })

  private def dataMoverCommand(address: UInt, bytes: Int): UInt =
    Cat(0.U(8.W), address, false.B, true.B, 0.U(6.W), true.B, bytes.U(23.W))

  val busy = RegInit(false.B)
  val donePulse = RegInit(false.B)
  val errorSticky = RegInit(false.B)
  val errorChannel = RegInit(0.U(3.W))
  val errorCode = RegInit(0.U(8.W))
  val launch = io.start && !busy
  donePulse := false.B
  io.idle := !busy
  io.done := donePulse
  // Acknowledge ap_start when the invocation is accepted.  The AXI-Lite
  // control block otherwise keeps ap_start asserted until completion, and the
  // newly-idle sequencer would accept the same invocation a second time.
  io.ready := launch || donePulse
  val progressStatus = WireDefault(0.U(19.W))
  io.status := Cat(progressStatus, busy, errorCode, errorChannel, errorSticky)

  val inputPointer = Reg(UInt(64.W))
  val keyLowPointer = Reg(UInt(64.W))
  val keyHighPointer = Reg(UInt(64.W))
  val outputPointer = Reg(UInt(64.W))
  when(launch) {
    busy := true.B
    errorSticky := false.B
    errorChannel := 0.U
    errorCode := 0.U
    inputPointer := io.inputPointer
    keyLowPointer := io.keyLowPointer
    keyHighPointer := io.keyHighPointer
    outputPointer := io.outputPointer
  }

  val inputCommandPending = RegInit(false.B)
  val outputCommandPending = RegInit(false.B)
  val keyLowCommandsSent = RegInit(0.U(2.W))
  val keyHighCommandsSent = RegInit(0.U(2.W))
  when(launch) {
    inputCommandPending := true.B
    outputCommandPending := true.B
    keyLowCommandsSent := 0.U
    keyHighCommandsSent := 0.U
  }

  io.inputCommand.valid := inputCommandPending
  io.inputCommand.bits := dataMoverCommand(inputPointer, inputBytes)
  when(io.inputCommand.fire) { inputCommandPending := false.B }
  io.outputCommand.valid := outputCommandPending
  io.outputCommand.bits := dataMoverCommand(outputPointer, outputBytes)
  when(io.outputCommand.fire) { outputCommandPending := false.B }

  io.keyLowCommand.valid := busy && keyLowCommandsSent =/= 2.U
  io.keyLowCommand.bits := dataMoverCommand(
    Mux(
      keyLowCommandsSent === 0.U,
      keyLowPointer,
      keyLowPointer + keyFirstBytes.U
    ),
    keySecondBytes
  )
  when(keyLowCommandsSent === 0.U) {
    io.keyLowCommand.bits := dataMoverCommand(keyLowPointer, keyFirstBytes)
  }
  when(io.keyLowCommand.fire) { keyLowCommandsSent := keyLowCommandsSent + 1.U }

  io.keyHighCommand.valid := busy && keyHighCommandsSent =/= 2.U
  io.keyHighCommand.bits := dataMoverCommand(
    Mux(
      keyHighCommandsSent === 0.U,
      keyHighPointer,
      keyHighPointer + keyFirstBytes.U
    ),
    keySecondBytes
  )
  when(keyHighCommandsSent === 0.U) {
    io.keyHighCommand.bits := dataMoverCommand(keyHighPointer, keyFirstBytes)
  }
  when(io.keyHighCommand.fire) {
    keyHighCommandsSent := keyHighCommandsSent + 1.U
  }

  // Serialize the aggregate 512-bit input stream into its linear 32-bit words.
  // Context boundaries deliberately need not align to a 512-bit memory beat.
  val inputBuffer = Reg(UInt(512.W))
  val inputBufferValid = RegInit(false.B)
  val inputLane = RegInit(0.U(4.W))
  val inputContext = RegInit(0.U(contextWidth.W))
  val inputContextWord = RegInit(0.U(10.W))
  val inputsLoaded = RegInit(false.B)
  val selectedInputWord = inputBuffer.asTypeOf(Vec(16, UInt(32.W)))(inputLane)
  val selectedIsTestVector = inputContextWord === 0.U
  val coreInputCanAccept = Mux(
    selectedIsTestVector,
    io.coreInputStartReady,
    io.coreInputReady
  )
  val consumeInputWord = busy && inputBufferValid && coreInputCanAccept
  io.inputData.ready := busy &&
    (!inputBufferValid || (consumeInputWord && inputLane === 15.U))
  io.coreInputStart := consumeInputWord && selectedIsTestVector
  io.coreInputContext := inputContext
  io.coreTestVector := selectedInputWord
  io.coreInputValid := consumeInputWord && !selectedIsTestVector
  io.coreInputCoefficient := selectedInputWord
  when(launch) {
    inputBufferValid := false.B
    inputLane := 0.U
    inputContext := 0.U
    inputContextWord := 0.U
    inputsLoaded := false.B
  }
  when(consumeInputWord) {
    when(inputLane === 15.U) {
      inputLane := 0.U
      inputBufferValid := false.B
    }.otherwise {
      inputLane := inputLane + 1.U
    }
    when(inputContextWord === (dimension + 1).U) {
      inputContextWord := 0.U
      when(inputContext =/= (contexts - 1).U) {
        inputContext := inputContext + 1.U
      }
    }.otherwise {
      inputContextWord := inputContextWord + 1.U
    }
  }
  when(io.inputData.fire) {
    inputBuffer := io.inputData.bits
    inputBufferValid := true.B
  }
  when(io.coreInputDone && io.coreInputDoneContext === (contexts - 1).U) {
    inputsLoaded := true.B
  }

  // Pair independent key streams through one-word skid registers.
  val keyLowBuffer = Reg(UInt(512.W))
  val keyHighBuffer = Reg(UInt(512.W))
  val keyLowValid = RegInit(false.B)
  val keyHighValid = RegInit(false.B)
  val keyIndex = RegInit(0.U(dimensionWidth.W))
  val keyBeat = RegInit(0.U(8.W))
  val keyLoadActive = RegInit(false.B)
  val keyZeroLoaded = RegInit(false.B)
  io.coreKeyLoadStart := busy && !keyLoadActive &&
    keyIndex < dimension.U && io.coreKeyLoadStartReady
  io.coreKeyLoadIndex := keyIndex
  when(launch) {
    keyLowValid := false.B
    keyHighValid := false.B
    keyIndex := 0.U
    keyBeat := 0.U
    keyLoadActive := false.B
    keyZeroLoaded := false.B
  }
  when(io.coreKeyLoadStart) {
    keyLoadActive := true.B
    keyBeat := 0.U
  }
  val packedKey = Cat(keyHighBuffer(351, 0), keyLowBuffer)
  // The buffered accelerator's key-load interface uses a pulse-valid
  // contract: valid may only be asserted while the active bank load is ready.
  // The DataMovers can replace the final beat in the skid registers before
  // loadDone returns, so retain that next beat without presenting it during
  // the one-cycle gap between coefficient loads.
  io.coreKeyLoadValid := busy && keyLoadActive && keyLowValid && keyHighValid &&
    io.coreKeyLoadReady
  val consumeKeyBeat = io.coreKeyLoadValid && io.coreKeyLoadReady
  io.keyLowData.ready := busy && (!keyLowValid || consumeKeyBeat)
  io.keyHighData.ready := busy && (!keyHighValid || consumeKeyBeat)
  for (lane <- 0 until 16) {
    io.coreKeyLoad(lane).real := packedKey(lane * 54 + 26, lane * 54).asSInt
    io.coreKeyLoad(lane).imag :=
      packedKey(lane * 54 + 53, lane * 54 + 27).asSInt
  }
  when(consumeKeyBeat) {
    keyLowValid := false.B
    keyHighValid := false.B
    when(keyBeat =/= 255.U) { keyBeat := keyBeat + 1.U }
  }
  // These assignments follow the consume case so a same-cycle replacement
  // remains resident and the paired stream sustains one beat per cycle.
  when(io.keyLowData.fire) {
    keyLowBuffer := io.keyLowData.bits
    keyLowValid := true.B
  }
  when(io.keyHighData.fire) {
    keyHighBuffer := io.keyHighData.bits
    keyHighValid := true.B
  }
  when(io.coreKeyLoadDone) {
    assert(io.coreKeyLoadDoneIndex === keyIndex, "key-load index mismatch")
    keyLoadActive := false.B
    when(keyIndex === 0.U) { keyZeroLoaded := true.B }
    keyIndex := keyIndex + 1.U
  }

  val runStarted = RegInit(false.B)
  io.coreRunStart := busy && inputsLoaded && keyZeroLoaded &&
    !runStarted && io.coreRunReady
  when(launch) { runStarted := false.B }
  when(io.coreRunStart) { runStarted := true.B }

  io.outputData.valid := busy && io.coreResultValid
  io.outputData.bits := io.coreResult
  io.outputLast := io.coreResultLast
  io.coreResultReady := busy && io.outputData.ready

  val inputStatusDone = RegInit(false.B)
  val keyLowStatusCount = RegInit(0.U(2.W))
  val keyHighStatusCount = RegInit(0.U(2.W))
  val outputStatusDone = RegInit(false.B)
  val coreDoneSeen = RegInit(false.B)
  val outputDrainCount = RegInit(0.U(8.W))
  when(launch) {
    inputStatusDone := false.B
    keyLowStatusCount := 0.U
    keyHighStatusCount := 0.U
    outputStatusDone := false.B
    coreDoneSeen := false.B
    outputDrainCount := 0.U
  }
  when(io.inputStatus.valid) { inputStatusDone := true.B }
  when(io.keyLowStatus.valid && keyLowStatusCount =/= 2.U) {
    keyLowStatusCount := keyLowStatusCount + 1.U
  }
  when(io.keyHighStatus.valid && keyHighStatusCount =/= 2.U) {
    keyHighStatusCount := keyHighStatusCount + 1.U
  }
  when(io.outputStatus.valid) { outputStatusDone := true.B }
  when(io.coreDone) { coreDoneSeen := true.B }

  val channelErrors = VecInit(Seq(
    io.inputError || (io.inputStatus.valid && !io.inputStatus.bits(7)),
    io.keyLowError || (io.keyLowStatus.valid && !io.keyLowStatus.bits(7)),
    io.keyHighError || (io.keyHighStatus.valid && !io.keyHighStatus.bits(7)),
    io.outputError || (io.outputStatus.valid && !io.outputStatus.bits(7))
  ))
  val channelStatus = VecInit(Seq(
    Mux(io.inputStatus.valid, io.inputStatus.bits, "hff".U),
    Mux(io.keyLowStatus.valid, io.keyLowStatus.bits, "hff".U),
    Mux(io.keyHighStatus.valid, io.keyHighStatus.bits, "hff".U),
    Mux(io.outputStatus.valid, io.outputStatus.bits, "hff".U)
  ))
  when(busy && channelErrors.asUInt.orR && !errorSticky) {
    val selectedError = PriorityEncoder(channelErrors)
    errorSticky := true.B
    errorChannel := selectedError + 1.U
    errorCode := channelStatus(selectedError)
  }

  val allStatusesDone = inputStatusDone && keyLowStatusCount === 2.U &&
    keyHighStatusCount === 2.U && outputStatusDone
  val coreComplete = coreDoneSeen || io.coreDone
  // Some DataMover configurations do not emit status-stream beats when the
  // optional status FIFOs are disabled.  Core completion means the final
  // output word and TLAST have already handshaken into S2MM.  Prefer explicit
  // statuses, but otherwise leave a bounded drain interval for outstanding
  // packed writes and AXI responses before acknowledging the invocation.
  when(
    busy && coreComplete && !allStatusesDone && !outputDrainCount.andR
  ) {
    outputDrainCount := outputDrainCount + 1.U
  }
  val outputDrained = allStatusesDone || outputDrainCount.andR
  progressStatus := Cat(
    keyIndex,
    inputsLoaded,
    keyZeroLoaded,
    runStarted,
    coreDoneSeen,
    inputStatusDone,
    keyLowStatusCount === 2.U,
    keyHighStatusCount === 2.U,
    outputStatusDone,
    keyLoadActive
  )
  when(
    busy && (errorSticky || (coreComplete && outputDrained))
  ) {
    busy := false.B
    donePulse := true.B
  }
}

/** Chisel kernel body instantiated below the hand-written AXI/DataMover shell. */
final class FptBlindRotateKernelController(
    val config: BufferedBlindRotateConfig
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val idle = Output(Bool())
    val done = Output(Bool())
    val ready = Output(Bool())
    val status = Output(UInt(32.W))
    val inputPointer = Input(UInt(64.W))
    val keyLowPointer = Input(UInt(64.W))
    val keyHighPointer = Input(UInt(64.W))
    val outputPointer = Input(UInt(64.W))
    val inputCommand = Decoupled(UInt(104.W))
    val keyLowCommand = Decoupled(UInt(104.W))
    val keyHighCommand = Decoupled(UInt(104.W))
    val outputCommand = Decoupled(UInt(104.W))
    val inputData = Flipped(Decoupled(UInt(512.W)))
    val keyLowData = Flipped(Decoupled(UInt(512.W)))
    val keyHighData = Flipped(Decoupled(UInt(512.W)))
    val outputData = Decoupled(UInt(32.W))
    val outputLast = Output(Bool())
    val inputStatus = Flipped(Valid(UInt(8.W)))
    val keyLowStatus = Flipped(Valid(UInt(8.W)))
    val keyHighStatus = Flipped(Valid(UInt(8.W)))
    val outputStatus = Flipped(Valid(UInt(8.W)))
    val inputError = Input(Bool())
    val keyLowError = Input(Bool())
    val keyHighError = Input(Bool())
    val outputError = Input(Bool())
  })

  val sequencer = Module(new FptBlindRotateKernelSequencer(config))
  val core = Module(new BufferedBlindRotateAccelerator(config))

  sequencer.io.start := io.start
  io.idle := sequencer.io.idle
  io.done := sequencer.io.done
  io.ready := sequencer.io.ready
  io.status := sequencer.io.status
  sequencer.io.inputPointer := io.inputPointer
  sequencer.io.keyLowPointer := io.keyLowPointer
  sequencer.io.keyHighPointer := io.keyHighPointer
  sequencer.io.outputPointer := io.outputPointer

  io.inputCommand.valid := sequencer.io.inputCommand.valid
  io.inputCommand.bits := sequencer.io.inputCommand.bits
  sequencer.io.inputCommand.ready := io.inputCommand.ready
  io.keyLowCommand.valid := sequencer.io.keyLowCommand.valid
  io.keyLowCommand.bits := sequencer.io.keyLowCommand.bits
  sequencer.io.keyLowCommand.ready := io.keyLowCommand.ready
  io.keyHighCommand.valid := sequencer.io.keyHighCommand.valid
  io.keyHighCommand.bits := sequencer.io.keyHighCommand.bits
  sequencer.io.keyHighCommand.ready := io.keyHighCommand.ready
  io.outputCommand.valid := sequencer.io.outputCommand.valid
  io.outputCommand.bits := sequencer.io.outputCommand.bits
  sequencer.io.outputCommand.ready := io.outputCommand.ready

  sequencer.io.inputData.valid := io.inputData.valid
  sequencer.io.inputData.bits := io.inputData.bits
  io.inputData.ready := sequencer.io.inputData.ready
  sequencer.io.keyLowData.valid := io.keyLowData.valid
  sequencer.io.keyLowData.bits := io.keyLowData.bits
  io.keyLowData.ready := sequencer.io.keyLowData.ready
  sequencer.io.keyHighData.valid := io.keyHighData.valid
  sequencer.io.keyHighData.bits := io.keyHighData.bits
  io.keyHighData.ready := sequencer.io.keyHighData.ready
  io.outputData.valid := sequencer.io.outputData.valid
  io.outputData.bits := sequencer.io.outputData.bits
  sequencer.io.outputData.ready := io.outputData.ready
  io.outputLast := sequencer.io.outputLast

  sequencer.io.inputStatus.valid := io.inputStatus.valid
  sequencer.io.inputStatus.bits := io.inputStatus.bits
  sequencer.io.keyLowStatus.valid := io.keyLowStatus.valid
  sequencer.io.keyLowStatus.bits := io.keyLowStatus.bits
  sequencer.io.keyHighStatus.valid := io.keyHighStatus.valid
  sequencer.io.keyHighStatus.bits := io.keyHighStatus.bits
  sequencer.io.outputStatus.valid := io.outputStatus.valid
  sequencer.io.outputStatus.bits := io.outputStatus.bits
  sequencer.io.inputError := io.inputError
  sequencer.io.keyLowError := io.keyLowError
  sequencer.io.keyHighError := io.keyHighError
  sequencer.io.outputError := io.outputError
  sequencer.io.coreInputStartReady := core.io.inputStartReady
  sequencer.io.coreInputReady := core.io.inputReady
  sequencer.io.coreInputDone := core.io.inputDone
  sequencer.io.coreInputDoneContext := core.io.inputDoneContext
  core.io.inputStart := sequencer.io.coreInputStart
  core.io.inputContext := sequencer.io.coreInputContext
  core.io.testVector := sequencer.io.coreTestVector
  core.io.inputValid := sequencer.io.coreInputValid
  core.io.inputCoefficient := sequencer.io.coreInputCoefficient

  sequencer.io.coreKeyLoadStartReady := core.io.keyLoadStartReady
  sequencer.io.coreKeyLoadReady := core.io.keyLoadReady
  sequencer.io.coreKeyLoadDone := core.io.keyLoadDone
  sequencer.io.coreKeyLoadDoneIndex := core.io.keyLoadDoneIndex
  core.io.keyLoadStart := sequencer.io.coreKeyLoadStart
  core.io.keyLoadIndex := sequencer.io.coreKeyLoadIndex
  core.io.keyLoadValid := sequencer.io.coreKeyLoadValid
  core.io.keyLoad := sequencer.io.coreKeyLoad

  sequencer.io.coreRunReady := core.io.runReady
  sequencer.io.coreDone := core.io.done
  core.io.runStart := sequencer.io.coreRunStart
  sequencer.io.coreResultValid := core.io.resultValid
  sequencer.io.coreResult := core.io.result
  sequencer.io.coreResultLast := core.io.resultLast
  core.io.resultReady := sequencer.io.coreResultReady
}

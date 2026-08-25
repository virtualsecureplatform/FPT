package fpt

import chisel3._
import chisel3.util._

final case class BootstrappingKeyBufferConfig(
    externalProduct: ExternalProductConfig,
    batchContexts: Int,
    domainDimension: Int,
    loadLanes: Int,
    bankCount: Int = 2
) {
  require(batchContexts >= 2)
  require(domainDimension >= 1)
  require(loadLanes >= 1)
  require(bankCount >= 2)

  val complexValuesPerRead: Int =
    externalProduct.outputComponents * externalProduct.inputLanes
  require(complexValuesPerRead % loadLanes == 0)

  val loadGroupsPerRead: Int = complexValuesPerRead / loadLanes
  val wordsPerCoefficient: Int =
    externalProduct.rows * externalProduct.inputFrameBeats
  val loadBeatsPerCoefficient: Int =
    wordsPerCoefficient * loadGroupsPerRead
  val readsPerCoefficient: Int = batchContexts * wordsPerCoefficient
  val dimensionWidth: Int = TransformUtil.counterWidth(domainDimension)
  val rowWidth: Int = TransformUtil.counterWidth(externalProduct.rows)
  val beatWidth: Int =
    TransformUtil.counterWidth(externalProduct.inputFrameBeats)
}

/** Two-coefficient bootstrapping-key cache for batch bootstrapping.
  *
  * The narrow load stream is ordered by row, forward-transform beat, lane,
  * then output component. One synchronous wide read supplies every complex
  * key operand consumed by a forward-transform beat. Both coefficients share
  * a single 1R1W memory address space, allowing one bank to be loaded while
  * the other serves the CMUX datapath.
  *
  * A bank retires automatically after `batchContexts` complete coefficient
  * reads. This matches Blind Rotate's dimension-major command order: every
  * context consumes key coefficient i before any context consumes i + 1.
  */
final class BootstrappingKeyPingPongBuffer(
    val config: BootstrappingKeyBufferConfig
) extends Module {
  private val external = config.externalProduct
  private val keyWidth = external.bootstrappingKey.width
  private val complexWidth = 2 * keyWidth
  private val loadWordWidth = config.loadLanes * complexWidth
  private val memoryDepth = config.bankCount * config.wordsPerCoefficient
  private val memoryAddressWidth = TransformUtil.counterWidth(memoryDepth)
  private val bankWidth = TransformUtil.counterWidth(config.bankCount)
  private val wordWidth = TransformUtil.counterWidth(
    config.wordsPerCoefficient
  )
  private val loadGroupWidth = TransformUtil.counterWidth(
    config.loadGroupsPerRead
  )
  private val readCountWidth = TransformUtil.counterWidth(
    config.readsPerCoefficient
  )

  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val loadStartReady = Output(Bool())
    val loadIndex = Input(UInt(config.dimensionWidth.W))
    val loadValid = Input(Bool())
    val loadReady = Output(Bool())
    val load = Input(
      Vec(config.loadLanes, new ComplexSInt(keyWidth))
    )
    val loadDone = Output(Bool())
    val loadDoneIndex = Output(UInt(config.dimensionWidth.W))

    val readRequestValid = Input(Bool())
    val readRequestReady = Output(Bool())
    val readIndex = Input(UInt(config.dimensionWidth.W))
    val readRow = Input(UInt(config.rowWidth.W))
    val readBeat = Input(UInt(config.beatWidth.W))

    val readResponseValid = Output(Bool())
    val readResponseReady = Input(Bool())
    val readResponseIndex = Output(UInt(config.dimensionWidth.W))
    val readResponseRow = Output(UInt(config.rowWidth.W))
    val readResponseBeat = Output(UInt(config.beatWidth.W))
    val readResponse = Output(
      Vec(
        external.outputComponents,
        Vec(
          external.inputLanes,
          new ComplexSInt(keyWidth)
        )
      )
    )

    val bankValid = Output(Vec(config.bankCount, Bool()))
    val bankIndex = Output(
      Vec(config.bankCount, UInt(config.dimensionWidth.W))
    )
  })

  val memory = SyncReadMem(
    memoryDepth,
    Vec(config.loadGroupsPerRead, UInt(loadWordWidth.W))
  )

  val bankValid = RegInit(VecInit(Seq.fill(config.bankCount)(false.B)))
  val bankIndex = Reg(Vec(config.bankCount, UInt(config.dimensionWidth.W)))
  val bankReadCount = RegInit(
    VecInit(Seq.fill(config.bankCount)(0.U(readCountWidth.W)))
  )
  io.bankValid := bankValid
  io.bankIndex := bankIndex

  val loading = RegInit(false.B)
  val loadBank = RegInit(0.U(bankWidth.W))
  val loadIndex = RegInit(0.U(config.dimensionWidth.W))
  val loadWord = RegInit(0.U(wordWidth.W))
  val loadGroup = RegInit(0.U(loadGroupWidth.W))
  val loadDone = RegInit(false.B)
  val loadDoneIndex = RegInit(0.U(config.dimensionWidth.W))
  io.loadDone := loadDone
  io.loadDoneIndex := loadDoneIndex
  loadDone := false.B
  io.loadReady := loading
  val loadFire = io.loadValid && io.loadReady
  val completingLoad = loadFire &&
    loadGroup === (config.loadGroupsPerRead - 1).U &&
    loadWord === (config.wordsPerCoefficient - 1).U

  val responseValid = RegInit(false.B)
  val responseBank = RegInit(0.U(bankWidth.W))
  val responseIndex = RegInit(0.U(config.dimensionWidth.W))
  val responseRow = RegInit(0.U(config.rowWidth.W))
  val responseBeat = RegInit(0.U(config.beatWidth.W))
  io.readResponseValid := responseValid
  io.readResponseIndex := responseIndex
  io.readResponseRow := responseRow
  io.readResponseBeat := responseBeat

  val responseFire = responseValid && io.readResponseReady
  val bankFree = Wire(Vec(config.bankCount, Bool()))
  for (bank <- 0 until config.bankCount) {
    bankFree(bank) := !bankValid(bank) &&
      !(loading && loadBank === bank.U) &&
      !(responseValid && responseBank === bank.U && !responseFire)
  }
  val residentDuplicate = bankValid.zip(bankIndex).map {
    case (valid, index) => valid && index === io.loadIndex
  }.reduce(_ || _)
  val duplicateLoad = residentDuplicate ||
    (loading && loadIndex === io.loadIndex)
  val selectedLoadBank = PriorityEncoder(bankFree)
  io.loadStartReady := (!loading || completingLoad) &&
    bankFree.asUInt.orR &&
    !duplicateLoad && io.loadIndex < config.domainDimension.U
  val loadStartFire = io.loadStart && io.loadStartReady

  when(io.loadStart) {
    assert(io.loadStartReady, "bootstrapping-key load started while unavailable")
  }
  when(io.loadValid) {
    assert(io.loadReady, "bootstrapping-key load data presented while idle")
  }
  when(loadStartFire) {
    loading := true.B
    loadBank := selectedLoadBank
    loadIndex := io.loadIndex
    loadWord := 0.U
    loadGroup := 0.U
    bankIndex(selectedLoadBank) := io.loadIndex
    bankReadCount(selectedLoadBank) := 0.U
  }

  val packedLoad = VecInit(
    (0 until config.loadLanes).map { lane =>
      Cat(io.load(lane).imag.asUInt, io.load(lane).real.asUInt)
    }
  ).asUInt
  val loadWriteData = Wire(
    Vec(config.loadGroupsPerRead, UInt(loadWordWidth.W))
  )
  val loadWriteMask = Wire(Vec(config.loadGroupsPerRead, Bool()))
  for (group <- 0 until config.loadGroupsPerRead) {
    loadWriteData(group) := packedLoad
    loadWriteMask(group) := loadGroup === group.U
  }
  val loadAddress = (
    loadBank * config.wordsPerCoefficient.U + loadWord
  )(memoryAddressWidth - 1, 0)
  when(loadFire) {
    memory.write(loadAddress, loadWriteData, loadWriteMask)
    when(loadGroup === (config.loadGroupsPerRead - 1).U) {
      loadGroup := 0.U
      when(loadWord === (config.wordsPerCoefficient - 1).U) {
        loading := loadStartFire
        loadWord := 0.U
        bankValid(loadBank) := true.B
        bankReadCount(loadBank) := 0.U
        loadDone := true.B
        loadDoneIndex := loadIndex
      }.otherwise {
        loadWord := loadWord + 1.U
      }
    }.otherwise {
      loadGroup := loadGroup + 1.U
    }
  }

  val inputReadWord = (
    io.readRow * external.inputFrameBeats.U + io.readBeat
  )(wordWidth - 1, 0)
  val inputMatchingBank = Wire(Vec(config.bankCount, Bool()))
  for (bank <- 0 until config.bankCount) {
    val resident = bankValid(bank) && bankIndex(bank) === io.readIndex
    val completing = completingLoad && loadBank === bank.U &&
      loadIndex === io.readIndex && loadWord =/= inputReadWord
    inputMatchingBank(bank) := resident || completing
  }

  // Register the complete request next to the key memory. This removes the
  // outer pending queue's distributed-RAM read and tag/address decode from
  // the BRAM address path while retaining one request per cycle.
  val requestValid = RegInit(false.B)
  val requestBank = RegInit(0.U(bankWidth.W))
  val requestAddress = RegInit(0.U(memoryAddressWidth.W))
  val requestIndex = RegInit(0.U(config.dimensionWidth.W))
  val requestRow = RegInit(0.U(config.rowWidth.W))
  val requestBeat = RegInit(0.U(config.beatWidth.W))
  val responseSlotReady = !responseValid || io.readResponseReady
  val requestIssue = requestValid && responseSlotReady
  val requestStageReady = !requestValid || requestIssue
  io.readRequestReady := inputMatchingBank.asUInt.orR && requestStageReady &&
    io.readIndex < config.domainDimension.U &&
    io.readRow < external.rows.U &&
    io.readBeat < external.inputFrameBeats.U
  val inputRequestFire = io.readRequestValid && io.readRequestReady
  val selectedInputBank = PriorityEncoder(inputMatchingBank)
  val inputReadAddress = (
    selectedInputBank * config.wordsPerCoefficient.U + inputReadWord
  )(memoryAddressWidth - 1, 0)
  val readData = memory.read(requestAddress, requestIssue)

  when(io.readRequestValid) {
    assert(
      PopCount(inputMatchingBank) <= 1.U,
      "bootstrapping-key index is present in both ping-pong banks"
    )
  }
  when(inputRequestFire) {
    requestBank := selectedInputBank
    requestAddress := inputReadAddress
    requestIndex := io.readIndex
    requestRow := io.readRow
    requestBeat := io.readBeat
  }
  when(inputRequestFire =/= requestIssue) {
    requestValid := inputRequestFire
  }
  when(requestIssue) {
    assert(
      bankValid(requestBank) && bankIndex(requestBank) === requestIndex,
      "staged bootstrapping-key request lost its resident bank"
    )
    responseBank := requestBank
    responseIndex := requestIndex
    responseRow := requestRow
    responseBeat := requestBeat
    when(
      bankReadCount(requestBank) ===
        (config.readsPerCoefficient - 1).U
    ) {
      bankReadCount(requestBank) := 0.U
      bankValid(requestBank) := false.B
    }.otherwise {
      bankReadCount(requestBank) := bankReadCount(requestBank) + 1.U
    }
  }
  when(responseFire =/= requestIssue) {
    responseValid := requestIssue
  }

  for (lane <- 0 until external.inputLanes) {
    for (component <- 0 until external.outputComponents) {
      val scalar = lane * external.outputComponents + component
      val group = scalar / config.loadLanes
      val offset = scalar % config.loadLanes
      val value = readData(group)(
        (offset + 1) * complexWidth - 1,
        offset * complexWidth
      )
      io.readResponse(component)(lane).real :=
        value(keyWidth - 1, 0).asSInt
      io.readResponse(component)(lane).imag :=
        value(complexWidth - 1, keyWidth).asSInt
    }
  }
}

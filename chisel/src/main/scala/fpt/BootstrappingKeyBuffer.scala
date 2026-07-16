package fpt

import chisel3._
import chisel3.util._

final case class BootstrappingKeyBufferConfig(
    externalProduct: ExternalProductConfig,
    batchContexts: Int,
    domainDimension: Int,
    loadLanes: Int
) {
  require(batchContexts >= 2)
  require(domainDimension >= 1)
  require(loadLanes >= 1)

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
  private val memoryDepth = 2 * config.wordsPerCoefficient
  private val memoryAddressWidth = TransformUtil.counterWidth(memoryDepth)
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

    val bankValid = Output(Vec(2, Bool()))
    val bankIndex = Output(Vec(2, UInt(config.dimensionWidth.W)))
  })

  val memory = SyncReadMem(
    memoryDepth,
    Vec(config.loadGroupsPerRead, UInt(loadWordWidth.W))
  )

  val bankValid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val bankIndex = Reg(Vec(2, UInt(config.dimensionWidth.W)))
  val bankReadCount = RegInit(
    VecInit(Seq.fill(2)(0.U(readCountWidth.W)))
  )
  io.bankValid := bankValid
  io.bankIndex := bankIndex

  val loading = RegInit(false.B)
  val loadBank = RegInit(0.U(1.W))
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
  val responseBank = RegInit(0.U(1.W))
  val responseIndex = RegInit(0.U(config.dimensionWidth.W))
  val responseRow = RegInit(0.U(config.rowWidth.W))
  val responseBeat = RegInit(0.U(config.beatWidth.W))
  io.readResponseValid := responseValid
  io.readResponseIndex := responseIndex
  io.readResponseRow := responseRow
  io.readResponseBeat := responseBeat

  val responseFire = responseValid && io.readResponseReady
  val bankFree = Wire(Vec(2, Bool()))
  for (bank <- 0 until 2) {
    bankFree(bank) := !bankValid(bank) &&
      !(loading && loadBank === bank.U) &&
      !(responseValid && responseBank === bank.U && !responseFire)
  }
  val residentDuplicate = bankValid.zip(bankIndex).map {
    case (valid, index) => valid && index === io.loadIndex
  }.reduce(_ || _)
  val duplicateLoad = residentDuplicate ||
    (loading && loadIndex === io.loadIndex)
  val selectedLoadBank = Mux(bankFree(0), 0.U, 1.U)
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

  val readWord = (
    io.readRow * external.inputFrameBeats.U + io.readBeat
  )(wordWidth - 1, 0)
  val matchingBank = Wire(Vec(2, Bool()))
  for (bank <- 0 until 2) {
    val resident = bankValid(bank) && bankIndex(bank) === io.readIndex
    val completing = completingLoad && loadBank === bank.U &&
      loadIndex === io.readIndex && loadWord =/= readWord
    matchingBank(bank) := resident || completing
  }
  val responseSlotReady = !responseValid || io.readResponseReady
  io.readRequestReady := matchingBank.asUInt.orR && responseSlotReady &&
    io.readIndex < config.domainDimension.U &&
    io.readRow < external.rows.U &&
    io.readBeat < external.inputFrameBeats.U
  val readFire = io.readRequestValid && io.readRequestReady
  val selectedReadBank = Mux(matchingBank(0), 0.U, 1.U)
  val readAddress = (
    selectedReadBank * config.wordsPerCoefficient.U + readWord
  )(memoryAddressWidth - 1, 0)
  val readData = memory.read(readAddress, readFire)

  when(io.readRequestValid) {
    assert(
      PopCount(matchingBank) <= 1.U,
      "bootstrapping-key index is present in both ping-pong banks"
    )
  }
  when(readFire) {
    responseBank := selectedReadBank
    responseIndex := io.readIndex
    responseRow := io.readRow
    responseBeat := io.readBeat
    when(
      bankReadCount(selectedReadBank) ===
        (config.readsPerCoefficient - 1).U
    ) {
      bankReadCount(selectedReadBank) := 0.U
      bankValid(selectedReadBank) := false.B
    }.otherwise {
      bankReadCount(selectedReadBank) :=
        bankReadCount(selectedReadBank) + 1.U
    }
  }
  when(responseFire =/= readFire) {
    responseValid := readFire
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

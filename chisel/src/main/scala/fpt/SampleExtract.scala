package fpt

import chisel3._
import chisel3.util._

final case class SampleExtractConfig(
    polynomialSize: Int,
    lanes: Int,
    torusWidth: Int
) {
  require(polynomialSize >= 2)
  require((polynomialSize & (polynomialSize - 1)) == 0)
  require(lanes >= 1)
  require(polynomialSize % lanes == 0)
  require(torusWidth >= 2)

  val inputBeats: Int = polynomialSize / lanes
  val beatWidth: Int = TransformUtil.counterWidth(inputBeats)
  val laneWidth: Int = TransformUtil.counterWidth(lanes)
  val outputIndexWidth: Int = TransformUtil.counterWidth(polynomialSize + 1)
}

private final class SampleExtractWord(width: Int) extends Bundle {
  val data = UInt(width.W)
  val last = Bool()
}

/** Stream sample extraction at polynomial index zero.
  *
  * The input is a natural-order, one-polynomial TRLWE drain with both
  * components presented in parallel. The output is the corresponding TLWE:
  * `a(0), -a(N-1), ..., -a(1), b(0)`. The mask is stored a lane-word at a
  * time in synchronous memory so the reverse traversal does not require a
  * full polynomial of registers. Once filled, the output sustains one Torus
  * coefficient per cycle while `outputReady` remains asserted.
  */
final class SampleExtractIndexZero(val config: SampleExtractConfig)
    extends Module {
  val io = IO(new Bundle {
    val inputStart = Input(Bool())
    val inputStartReady = Output(Bool())
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val inputA = Input(Vec(config.lanes, UInt(config.torusWidth.W)))
    val inputB = Input(Vec(config.lanes, UInt(config.torusWidth.W)))
    val inputDone = Output(Bool())

    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val output = Output(UInt(config.torusWidth.W))
    val outputLast = Output(Bool())
    val done = Output(Bool())
    val busy = Output(Bool())
  })

  val states = Enum(3)
  val idle = states(0)
  val collect = states(1)
  val emit = states(2)
  val state = RegInit(idle)

  val maskMemory = SyncReadMem(
    config.inputBeats,
    Vec(config.lanes, UInt(config.torusWidth.W))
  )
  val inputBeat = RegInit(0.U(config.beatWidth.W))
  val body = RegInit(0.U(config.torusWidth.W))

  io.inputStartReady := state === idle
  io.inputReady := state === collect
  io.busy := state =/= idle

  when(io.inputStart) {
    assert(io.inputStartReady, "sample extraction started while busy")
  }
  when(io.inputStart && io.inputStartReady) {
    inputBeat := 0.U
    state := collect
  }

  val inputFire = io.inputValid && io.inputReady
  val finalInputBeat = inputBeat === (config.inputBeats - 1).U
  io.inputDone := inputFire && finalInputBeat
  when(inputFire) {
    maskMemory.write(inputBeat, io.inputA)
    when(inputBeat === 0.U) {
      body := io.inputB(0)
    }
    when(finalInputBeat) {
      inputBeat := 0.U
      state := emit
    }.otherwise {
      inputBeat := inputBeat + 1.U
    }
  }

  private val resultQueue = Module(
    new Queue(new SampleExtractWord(config.torusWidth), entries = 2, pipe = true)
  )
  io.outputValid := resultQueue.io.deq.valid
  io.output := resultQueue.io.deq.bits.data
  io.outputLast := resultQueue.io.deq.bits.last
  resultQueue.io.deq.ready := io.outputReady

  val outputFire = resultQueue.io.deq.valid && resultQueue.io.deq.ready
  io.done := outputFire && resultQueue.io.deq.bits.last

  val nextOutputIndex = RegInit(0.U(config.outputIndexWidth.W))
  val responsePending = RegInit(false.B)
  val pendingLane = RegInit(0.U(config.laneWidth.W))
  val pendingNegate = RegInit(false.B)
  val bodyQueued = RegInit(false.B)

  val occupiedAfterTransfers = resultQueue.io.count +&
    responsePending.asUInt - outputFire.asUInt
  val canReserveResponse = occupiedAfterTransfers < 2.U
  val maskOutputRemaining = nextOutputIndex < config.polynomialSize.U
  val issueRead = state === emit && maskOutputRemaining &&
    canReserveResponse

  val coefficientIndex = Mux(
    nextOutputIndex === 0.U,
    0.U,
    config.polynomialSize.U - nextOutputIndex
  )
  val readAddress = coefficientIndex / config.lanes.U
  val readLane = coefficientIndex % config.lanes.U
  val readWord = maskMemory.read(readAddress, issueRead)

  responsePending := issueRead
  when(issueRead) {
    pendingLane := readLane
    pendingNegate := nextOutputIndex =/= 0.U
    nextOutputIndex := nextOutputIndex + 1.U
  }

  val readCoefficient = readWord(pendingLane)
  val negativeReadCoefficient =
    (0.U((config.torusWidth + 1).W) - readCoefficient)(
      config.torusWidth - 1,
      0
    )
  val bodyRequest = state === emit &&
    nextOutputIndex === config.polynomialSize.U && !responsePending &&
    !bodyQueued

  resultQueue.io.enq.valid := responsePending || bodyRequest
  resultQueue.io.enq.bits.data := Mux(
    responsePending,
    Mux(pendingNegate, negativeReadCoefficient, readCoefficient),
    body
  )
  resultQueue.io.enq.bits.last := bodyRequest
  when(responsePending) {
    assert(resultQueue.io.enq.ready, "sample extraction response overflow")
  }
  when(bodyRequest && resultQueue.io.enq.ready) {
    bodyQueued := true.B
  }

  when(io.inputStart && io.inputStartReady) {
    nextOutputIndex := 0.U
    responsePending := false.B
    bodyQueued := false.B
  }
  when(io.done) {
    state := idle
    bodyQueued := false.B
  }
}

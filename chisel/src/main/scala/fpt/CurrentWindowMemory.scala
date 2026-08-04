package fpt

import chisel3._
import chisel3.experimental.IntParam
import chisel3.util._

/** A synthesis-directed simple dual-port block memory.
  *
  * The current-coefficient window is only a few words deep but 8,192 bits
  * wide at Set II. Vivado otherwise implements that read as tens of thousands
  * of LUT muxes over the prefetched register buffers. Keeping the RAM style in
  * this small inline module makes the intended BRAM-for-LUT trade explicit and
  * gives the read a real registered physical boundary.
  */
private[fpt] final class PhysicalSimpleDualPortBlockMemory(
    val width: Int,
    val depth: Int
) extends BlackBox(
      Map(
        "WIDTH" -> IntParam(width),
        "DEPTH" -> IntParam(depth),
        "ADDRESS_WIDTH" -> IntParam(log2Ceil(depth))
      )
    )
    with HasBlackBoxInline {
  require(width >= 1)
  require(depth >= 2 && isPow2(depth))

  override def desiredName: String = "FptSimpleDualPortBlockMemory"

  private val addressWidth = log2Ceil(depth)
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val readEnable = Input(Bool())
    val readAddress = Input(UInt(addressWidth.W))
    val readData = Output(UInt(width.W))
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(addressWidth.W))
    val writeData = Input(UInt(width.W))
  })

  setInline(
    "FptSimpleDualPortBlockMemory.sv",
    """module FptSimpleDualPortBlockMemory #(
      |  parameter integer WIDTH = 1,
      |  parameter integer DEPTH = 2,
      |  parameter integer ADDRESS_WIDTH = 1
      |) (
      |  input  wire                         clock,
      |  input  wire                         readEnable,
      |  input  wire [ADDRESS_WIDTH-1:0]     readAddress,
      |  output reg  [WIDTH-1:0]             readData,
      |  input  wire                         writeEnable,
      |  input  wire [ADDRESS_WIDTH-1:0]     writeAddress,
      |  input  wire [WIDTH-1:0]             writeData
      |);
      |  (* ram_style = "block" *) reg [WIDTH-1:0] memory [0:DEPTH-1];
      |  reg [WIDTH-1:0] memoryReadData;
      |  always @(posedge clock) begin
      |    if (writeEnable)
      |      memory[writeAddress] <= writeData;
      |    if (readEnable) begin
      |      memoryReadData <= memory[readAddress];
      |      readData <= memoryReadData;
      |    end
      |  end
      |endmodule
      |""".stripMargin
  )
}

/** Re-packs two inverse-width prefetch beats into one forward-width window.
  *
  * Both accumulator components arrive together, but the cache has one write
  * port. Component zero is written when the second half-beat arrives and
  * component one is committed on the following cycle. That is exactly one
  * write per cycle and leaves the independent read port available to the
  * active CMUX command. `fillCommitted` waits for the final component-one
  * write, so a newly filled buffer cannot be read early.
  */
private[fpt] final class PrefetchedCurrentWindowMemory(
    val config: CmuxCoefficientConfig,
    val bufferCount: Int
) extends Module {
  import TransformUtil._

  require(config.windowedRotator)
  require(config.components == 2)
  require(config.forwardLanes == 2 * config.inverseLanes)
  require(bufferCount >= 2 && isPow2(bufferCount))
  require(config.forwardBeats >= 2 && isPow2(config.forwardBeats))

  private val bufferWidth = counterWidth(bufferCount)
  private val componentWidth = counterWidth(config.components)
  private val halfBeatWidth = counterWidth(config.inverseBeats)
  private val forwardBeatWidth = counterWidth(config.forwardBeats)
  private val addressDepth =
    bufferCount * config.components * config.forwardBeats
  private val addressWidth = log2Ceil(addressDepth)
  private val wordWidth = 2 * config.forwardLanes * config.torusWidth

  private def windowType: Vec[Vec[UInt]] =
    Vec(2, Vec(config.forwardLanes, UInt(config.torusWidth.W)))

  val io = IO(new Bundle {
    val fillValid = Input(Bool())
    val fillBuffer = Input(UInt(bufferWidth.W))
    val fillBeat = Input(UInt(halfBeatWidth.W))
    val fillLow = Input(
      Vec(
        config.components,
        Vec(config.inverseLanes, UInt(config.torusWidth.W))
      )
    )
    val fillHigh = Input(
      Vec(
        config.components,
        Vec(config.inverseLanes, UInt(config.torusWidth.W))
      )
    )
    val fillLast = Input(Bool())
    val fillCommitted = Output(Bool())

    val readEnable = Input(Bool())
    val readBuffer = Input(UInt(bufferWidth.W))
    val readComponent = Input(UInt(componentWidth.W))
    val readBeat = Input(UInt(forwardBeatWidth.W))
    val output = Output(windowType)
  })

  def packedAddress(buffer: UInt, component: UInt, beat: UInt): UInt =
    Cat(buffer, component, beat)(addressWidth - 1, 0)

  val lowerLow = Reg(
    Vec(
      config.components,
      Vec(config.inverseLanes, UInt(config.torusWidth.W))
    )
  )
  val lowerHigh = Reg(chiselTypeOf(lowerLow))
  val upperBeat = io.fillBeat(0)
  when(io.fillValid && !upperBeat) {
    lowerLow := io.fillLow
    lowerHigh := io.fillHigh
  }

  val assembled = Wire(Vec(config.components, windowType))
  for (component <- 0 until config.components) {
    for (lane <- 0 until config.inverseLanes) {
      assembled(component)(0)(lane) := lowerLow(component)(lane)
      assembled(component)(0)(config.inverseLanes + lane) :=
        io.fillLow(component)(lane)
      assembled(component)(1)(lane) := lowerHigh(component)(lane)
      assembled(component)(1)(config.inverseLanes + lane) :=
        io.fillHigh(component)(lane)
    }
  }

  val assembledForwardBeat =
    (io.fillBeat >> 1)(forwardBeatWidth - 1, 0)
  val assembleComplete = io.fillValid && upperBeat
  val directWriteAddress = packedAddress(
    io.fillBuffer,
    0.U(componentWidth.W),
    assembledForwardBeat
  )

  val pendingWriteValid = RegInit(false.B)
  val pendingWriteAddress = RegInit(0.U(addressWidth.W))
  val pendingWriteData = Reg(UInt(wordWidth.W))
  val pendingWriteCompletesFill = RegInit(false.B)

  when(assembleComplete) {
    assert(!pendingWriteValid, "current-window cache write queue overflow")
    assert(
      !io.fillLast || io.fillBeat === (config.inverseBeats - 1).U,
      "current-window final flag was not on the final prefetch beat"
    )
    pendingWriteValid := true.B
    pendingWriteAddress := packedAddress(
      io.fillBuffer,
      1.U(componentWidth.W),
      assembledForwardBeat
    )
    pendingWriteData := assembled(1).asUInt
    pendingWriteCompletesFill := io.fillLast
  }.elsewhen(pendingWriteValid) {
    pendingWriteValid := false.B
    pendingWriteCompletesFill := false.B
  }

  val memory = Module(
    new PhysicalSimpleDualPortBlockMemory(wordWidth, addressDepth)
  )
  memory.io.clock := clock
  memory.io.writeEnable := pendingWriteValid || assembleComplete
  memory.io.writeAddress := Mux(
    pendingWriteValid,
    pendingWriteAddress,
    directWriteAddress
  )
  memory.io.writeData := Mux(
    pendingWriteValid,
    pendingWriteData,
    assembled(0).asUInt
  )

  io.fillCommitted := pendingWriteValid && pendingWriteCompletesFill

  memory.io.readEnable := io.readEnable
  memory.io.readAddress := packedAddress(
    io.readBuffer,
    io.readComponent,
    io.readBeat
  )
  io.output := memory.io.readData.asTypeOf(windowType)
}

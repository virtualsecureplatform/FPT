package fpt

import chisel3._
import chisel3.experimental.IntParam
import chisel3.util._

/** Shallow source workset with one write and several synchronous reads.
  *
  * A window request needs the three accumulator blocks covering the rotated
  * span plus the two blocks covering the unrotated forward beat. Expressing
  * those reads from a `Reg(Vec(...))` turns the complete workset into flip-
  * flops and builds a wide arbitrary selector in front of every lane. This
  * explicitly multiported distributed memory instead lets Vivado replicate a
  * small LUTRAM image once per read port. All ports share the packed prefetch
  * write, so source-buffer fill still takes one beat per cycle.
  */
private[fpt] final class MultiportedCoefficientScratch(
    val depth: Int,
    val addressWidth: Int,
    val wordWidth: Int,
    val readPorts: Int,
    val banks: Int
) extends BlackBox(
      Map(
        "DEPTH" -> IntParam(depth),
        "ADDRESS_WIDTH" -> IntParam(addressWidth),
        "WORD_WIDTH" -> IntParam(wordWidth),
        "READ_PORTS" -> IntParam(readPorts),
        "BANKS" -> IntParam(banks)
      )
    )
    with HasBlackBoxInline {
  require(depth >= 2 && isPow2(depth))
  require(addressWidth == log2Ceil(depth))
  require(wordWidth >= 1)
  require(readPorts >= 1)
  require(banks >= 1 && isPow2(banks))
  require(wordWidth % banks == 0)

  override def desiredName: String = "FptMultiportedCoefficientScratch"

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(addressWidth.W))
    val inputData = Input(UInt(wordWidth.W))
    val primeCapture = Input(Bool())
    val primeData = Output(UInt(wordWidth.W))
    val readEnables = Input(UInt(readPorts.W))
    val readAddresses = Input(UInt((readPorts * addressWidth).W))
    val outputData = Output(UInt((readPorts * wordWidth).W))
  })

  setInline(
    "FptMultiportedCoefficientScratch.sv",
    """(* keep_hierarchy = "yes" *)
      |module FptCoefficientScratchBank #(
      |  parameter integer DEPTH = 2,
      |  parameter integer ADDRESS_WIDTH = 1,
      |  parameter integer BANK_WIDTH = 1
      |) (
      |  input  wire                       clock,
      |  input  wire                       writeEnable,
      |  input  wire [ADDRESS_WIDTH-1:0]   writeAddress,
      |  input  wire [BANK_WIDTH-1:0]      writeData,
      |  input  wire [ADDRESS_WIDTH-1:0]   readAddress,
      |  output reg  [BANK_WIDTH-1:0]      readData
      |);
      |  // Retain one local control register per narrow bank. Unlike
      |  // DONT_TOUCH, KEEP still permits physical optimization to replicate
      |  // the register if placement finds that profitable.
      |  (* keep = "true", max_fanout = 256 *)
      |  reg [ADDRESS_WIDTH-1:0] writeAddressCut;
      |  (* keep = "true", max_fanout = 256 *)
      |  reg writeEnableCut;
      |  // Keep one address pipeline per narrow bank. Without DONT_TOUCH,
      |  // synthesis merges these equivalent copies and recreates a single
      |  // multi-thousand-load LUTRAM address net across the middle SLR.
      |  (* keep = "true", dont_touch = "true", max_fanout = 64 *)
      |  reg [ADDRESS_WIDTH-1:0] readAddressCut;
      |  (* ram_style = "distributed" *)
      |  reg [BANK_WIDTH-1:0] memory [0:DEPTH-1];
      |
      |  always @(posedge clock) begin
      |    writeAddressCut <= writeAddress;
      |    writeEnableCut <= writeEnable;
      |    readAddressCut <= readAddress;
      |    if (writeEnableCut)
      |      memory[writeAddressCut] <= writeData;
      |    // Validity is pipelined separately, so bubble reads are harmless
      |    // and avoid a word-wide clock-enable broadcast.
      |    readData <= memory[readAddressCut];
      |  end
      |endmodule
      |
      |module FptCoefficientScratchPrimeBank #(
      |  parameter integer BANK_WIDTH = 1
      |) (
      |  input  wire                       clock,
      |  input  wire                       captureEnable,
      |  input  wire [BANK_WIDTH-1:0]      captureData,
      |  output reg  [BANK_WIDTH-1:0]      primeData
      |);
      |  (* keep = "true", max_fanout = 256 *)
      |  reg captureEnableCut;
      |
      |  always @(posedge clock) begin
      |    captureEnableCut <= captureEnable;
      |    if (captureEnableCut)
      |      primeData <= captureData;
      |  end
      |endmodule
      |
      |(* keep_hierarchy = "yes" *)
      |module FptCoefficientScratchReplica #(
      |  parameter integer DEPTH = 2,
      |  parameter integer ADDRESS_WIDTH = 1,
      |  parameter integer WORD_WIDTH = 1,
      |  parameter integer BANKS = 1,
      |  parameter integer BANK_WIDTH = WORD_WIDTH / BANKS
      |) (
      |  input  wire                       clock,
      |  input  wire                       writeEnable,
      |  input  wire [ADDRESS_WIDTH-1:0]   writeAddress,
      |  input  wire [WORD_WIDTH-1:0]      writeData,
      |  input  wire [ADDRESS_WIDTH-1:0]   readAddress,
      |  output wire [WORD_WIDTH-1:0]      readData
      |);
      |  // An 8,192-bit monolithic replica made every write-address bit drive
      |  // more than 9,000 placed loads. Slice it into independent lane/field
      |  // banks while retaining the same five logical synchronous reads.
      |  genvar bank;
      |  generate
      |    for (bank = 0; bank < BANKS; bank = bank + 1) begin : memory_banks
      |      (* keep_hierarchy = "yes" *)
      |      FptCoefficientScratchBank #(
      |        .DEPTH(DEPTH),
      |        .ADDRESS_WIDTH(ADDRESS_WIDTH),
      |        .BANK_WIDTH(BANK_WIDTH)
      |      ) bank_memory (
      |        .clock(clock),
      |        .writeEnable(writeEnable),
      |        .writeAddress(writeAddress),
      |        .writeData(writeData[bank*BANK_WIDTH +: BANK_WIDTH]),
      |        .readAddress(readAddress),
      |        .readData(readData[bank*BANK_WIDTH +: BANK_WIDTH])
      |      );
      |    end
      |  endgenerate
      |endmodule
      |
      |module FptMultiportedCoefficientScratch #(
      |  parameter integer DEPTH = 2,
      |  parameter integer ADDRESS_WIDTH = 1,
      |  parameter integer WORD_WIDTH = 1,
      |  parameter integer READ_PORTS = 1,
      |  parameter integer BANKS = 1
      |) (
      |  input  wire                                  clock,
      |  input  wire                                  writeEnable,
      |  input  wire [ADDRESS_WIDTH-1:0]              writeAddress,
      |  input  wire [WORD_WIDTH-1:0]                 inputData,
      |  input  wire                                  primeCapture,
      |  output wire [WORD_WIDTH-1:0]                 primeData,
      |  input  wire [READ_PORTS-1:0]                 readEnables,
      |  input  wire [READ_PORTS*ADDRESS_WIDTH-1:0]   readAddresses,
      |  output wire [READ_PORTS*WORD_WIDTH-1:0]      outputData
      |);
      |  reg [WORD_WIDTH-1:0] writeDataCut;
      |  localparam integer BANK_WIDTH = WORD_WIDTH / BANKS;
      |
      |  always @(posedge clock) begin
      |    // Delay data by the same cycle as each replica's local controls.
      |    writeDataCut <= inputData;
      |  end
      |
      |  // The first word of a workset's rolling rotation span is already on
      |  // the fill stream. Capture it in narrow banks so the steady-state
      |  // four read ports do not need a fifth replica for one startup read.
      |  genvar prime_bank;
      |  generate
      |    for (prime_bank = 0; prime_bank < BANKS;
      |         prime_bank = prime_bank + 1) begin : prime_banks
      |      (* keep_hierarchy = "yes" *)
      |      FptCoefficientScratchPrimeBank #(
      |        .BANK_WIDTH(BANK_WIDTH)
      |      ) prime_bank_register (
      |        .clock(clock),
      |        .captureEnable(primeCapture),
      |        .captureData(
      |          writeDataCut[prime_bank*BANK_WIDTH +: BANK_WIDTH]
      |        ),
      |        .primeData(primeData[prime_bank*BANK_WIDTH +: BANK_WIDTH])
      |      );
      |    end
      |  endgenerate
      |
      |  genvar replica;
      |  generate
      |    for (replica = 0; replica < READ_PORTS;
      |         replica = replica + 1) begin : scratch_replicas
      |      (* keep_hierarchy = "yes" *)
      |      FptCoefficientScratchReplica #(
      |        .DEPTH(DEPTH),
      |        .ADDRESS_WIDTH(ADDRESS_WIDTH),
      |        .WORD_WIDTH(WORD_WIDTH),
      |        .BANKS(BANKS)
      |      ) replica_memory (
      |        .clock(clock),
      |        .writeEnable(writeEnable),
      |        .writeAddress(writeAddress),
      |        .writeData(writeDataCut),
      |        .readAddress(
      |          readAddresses[replica*ADDRESS_WIDTH +: ADDRESS_WIDTH]
      |        ),
      |        .readData(outputData[replica*WORD_WIDTH +: WORD_WIDTH])
      |      );
      |    end
      |  endgenerate
      |endmodule
      |""".stripMargin
  )
}

/** Prefetched CMUX frontend with one time-shared stream-width rotator.
  *
  * The ordinary windowed frontend rotates the low and high polynomial halves
  * in parallel for every decomposition level.  At Set II that leaves two
  * independent 6,144-bit span boundaries and repeats the 32-bit difference
  * arithmetic for both 128-lane halves.  This implementation instead uses
  * the otherwise redundant level cycles to preprocess one half per cycle.
  * It computes every gadget level together, stores the centered digits in a
  * packed three-entry transpose, and emits prior worksets while the next is
  * being prepared. The third entry covers data still traversing the pipelined
  * selector and rotator when the following command starts.
  *
  * For the paper configuration preprocessing takes
  * `components * 2 * forwardBeats = 16` cycles, exactly matching the CMUX
  * command interval.  Consequently one rotator sustains II=16 while removing
  * the second wide rotation network and its physical boundary.
  */
private[fpt] final class BufferedAccumulatorUpdateBeat(
    config: CmuxCoefficientConfig,
    batchContexts: Int
) extends Bundle {
  val first = Bool()
  val context = UInt(TransformUtil.counterWidth(batchContexts).W)
  val low = Vec(
    config.components,
    Vec(config.inverseLanes, SInt(config.inverseFormat.width.W))
  )
  val high = Vec(
    config.components,
    Vec(config.inverseLanes, SInt(config.inverseFormat.width.W))
  )
}

final class PrecomputedWindowedBatchedCmuxCoefficientStore(
    override val config: CmuxCoefficientConfig,
    override val batchContexts: Int,
    val bufferedSingleAccumulator: Boolean = false,
    val coefficientPreprocessGuardBits: Option[Int] = None
) extends BatchedCmuxCoefficientStoreBase(config, batchContexts) {
  import TransformUtil._

  require(batchContexts >= 2)
  require(config.windowedRotator)
  coefficientPreprocessGuardBits.foreach { guardBits =>
    require(guardBits >= 0 && guardBits < config.remainingBits)
  }

  private val coefficientPreprocessWidth =
    coefficientPreprocessGuardBits
      .map(config.levels * config.baseBits + _)
      .getOrElse(config.torusWidth)
  private val coefficientDiscardBits =
    config.torusWidth - coefficientPreprocessWidth
  private val coefficientPreprocessMask =
    (BigInt(1) << coefficientPreprocessWidth) - 1
  private val coefficientPreprocessBias =
    (config.decompositionBias >> coefficientDiscardBits) &
      coefficientPreprocessMask

  private val contextWidth = counterWidth(batchContexts)
  private val componentWidth = counterWidth(config.components)
  private val levelWidth = counterWidth(config.levels)
  private val rows = config.components * config.levels
  private val rowWidth = counterWidth(rows)
  private val forwardBeatWidth = counterWidth(config.forwardBeats)
  private val polynomialBeatWidth = counterWidth(config.polynomialBeats)
  private val commandInterval = rows * config.forwardBeats
  private val cooldownWidth = counterWidth(commandInterval)
  private val bufferCount = 2
  private val bufferWidth = 1
  // One workset emits, one is being written by the rotator pipeline, and a
  // third must be allocated when the next 16-beat preprocessing interval
  // starts. Two buffers create an every-other-command bubble because the
  // final launch precedes the prior workset's final emitted beat.
  private val digitBufferCount = 3
  private val digitBufferWidth = counterWidth(digitBufferCount)
  private val halfCount = 2
  require(config.levels >= halfCount)
  private val preprocessingBeats =
    config.components * config.levels * config.forwardBeats
  require(
    preprocessingBeats <= commandInterval,
    "serialized window preprocessing must fit inside the command interval"
  )

  private val memoryConfig = ReplicatedAccumulatorBanksConfig(
    config,
    batchContexts
  )
  require(
    memoryConfig.halfBeats <= commandInterval,
    "accumulator prefetch must fit inside the command interval"
  )
  private val blockLanes = config.inverseLanes
  private val outputLanes = config.forwardLanes
  require(blockLanes >= 2)
  require(outputLanes >= blockLanes)
  require(outputLanes % blockLanes == 0)
  private val blocks = config.polynomialSize / blockLanes
  require(blocks >= 4 && isPow2(blocks))
  require(blocks == 2 * memoryConfig.halfBeats)
  private val blockIndexWidth = log2Ceil(blocks)
  private val blockLaneWidth = log2Ceil(blockLanes)
  private val outputLaneWidth = log2Ceil(outputLanes)
  private val ringWidth = log2Ceil(2 * config.polynomialSize)
  private val outputBlocks = outputLanes / blockLanes
  private val spanBlocks = outputBlocks + 1
  private val newSpanBlocks = spanBlocks - 1
  private val spanSize = spanBlocks * blockLanes
  private val sourceScratchDepth = bufferCount * memoryConfig.halfBeats
  private val sourceScratchAddressWidth = counterWidth(sourceScratchDepth)
  private val sourceScratchFields = config.components * halfCount
  private val sourceScratchWordWidth =
    blockLanes * sourceScratchFields * coefficientPreprocessWidth
  // Consecutive 128-lane beats advance by two 64-lane blocks. The trailing
  // block of one three-block rotation span is therefore the leading block of
  // the next. Capture the first leading block during fill, then read only the
  // two new span blocks and the two unrotated-current blocks every cycle.
  private val sourceScratchReadPorts = newSpanBlocks + outputBlocks

  val memory = Module(
    new ReplicatedAccumulatorBanks(
      memoryConfig,
      replicateReads = !bufferedSingleAccumulator
    )
  )
  memory.io.loadStart := io.loadStart
  memory.io.loadContext := io.loadContext
  memory.io.loadValid := io.loadValid
  memory.io.load := io.load
  io.loadReady := memory.io.loadReady
  io.loadDone := memory.io.loadDone
  io.loadDoneContext := memory.io.loadDoneContext
  io.contextLoaded := memory.io.contextLoaded

  val bufferedUpdatesIdle = WireDefault(true.B)
  if (bufferedSingleAccumulator) {
    val updateQueue = Module(
      new Queue(
        new BufferedAccumulatorUpdateBeat(config, batchContexts),
        memoryConfig.halfBeats,
        pipe = true,
        flow = true
      )
    )
    updateQueue.io.enq.valid := io.updateValid
    updateQueue.io.enq.bits.first := io.updateFirst
    updateQueue.io.enq.bits.context := io.updateContext
    updateQueue.io.enq.bits.low := io.updateLow
    updateQueue.io.enq.bits.high := io.updateHigh
    io.updateReady := updateQueue.io.enq.ready

    memory.io.updateValid := updateQueue.io.deq.valid
    memory.io.updateFirst := updateQueue.io.deq.bits.first
    memory.io.updateContext := updateQueue.io.deq.bits.context
    memory.io.updateLow := updateQueue.io.deq.bits.low
    memory.io.updateHigh := updateQueue.io.deq.bits.high
    updateQueue.io.deq.ready := memory.io.updateReady
    val finalQueuedBeatRetires = updateQueue.io.count === 1.U &&
      updateQueue.io.deq.valid && !updateQueue.io.deq.bits.first &&
      !io.updateValid
    // The memory's prefetchReady arbitration already admits only the final
    // beat of an active single-image update.  With a flow-through queue, an
    // incoming final beat can retire while count is zero; treating io.updateValid
    // itself as backlog inserted a needless cycle between update and prefetch.
    bufferedUpdatesIdle :=
      updateQueue.io.count === 0.U || finalQueuedBeatRetires

    when(io.updateValid) {
      assert(updateQueue.io.enq.ready, "buffered accumulator update overflow")
    }
    when(memory.io.updateReady && memory.io.updateValid) {
      assert(updateQueue.io.deq.valid, "buffered accumulator update underflow")
    }
  } else {
    memory.io.updateValid := io.updateValid
    memory.io.updateFirst := io.updateFirst
    memory.io.updateContext := io.updateContext
    memory.io.updateLow := io.updateLow
    memory.io.updateHigh := io.updateHigh
    io.updateReady := memory.io.updateReady
  }
  io.updateDone := memory.io.updateDone
  io.updateDoneContext := memory.io.updateDoneContext

  val busy = RegInit(VecInit(Seq.fill(batchContexts)(false.B)))
  io.contextBusy := busy

  // Pack all components and polynomial halves for one inverse-width block
  // into a single shallow word. The paper configuration has five read ports:
  // three consecutive blocks for rotation and two for the current beat.
  val sourceScratchBanks = math.min(
    32,
    Integer.lowestOneBit(sourceScratchWordWidth)
  )
  val sourceScratch = Module(
    new MultiportedCoefficientScratch(
      sourceScratchDepth,
      sourceScratchAddressWidth,
      sourceScratchWordWidth,
      sourceScratchReadPorts,
      banks = sourceScratchBanks
    )
  )
  sourceScratch.io.clock := clock
  val bufferOccupied = RegInit(VecInit(Seq.fill(bufferCount)(false.B)))
  val bufferExponent = Reg(Vec(bufferCount, UInt(config.exponentWidth.W)))
  val sourceReleaseValid = WireDefault(false.B)
  val sourceReleaseBuffer = WireDefault(0.U(bufferWidth.W))
  val sourceAvailable = VecInit((0 until bufferCount).map { buffer =>
    !bufferOccupied(buffer) ||
      (sourceReleaseValid && sourceReleaseBuffer === buffer.U)
  })
  val hasFreeBuffer = sourceAvailable.asUInt.orR
  val freeBuffer = PriorityEncoder(sourceAvailable.asUInt)

  val fillOutstanding = RegInit(false.B)
  // These payload fields are written when fillOutstanding is raised and are
  // only observed with a prefetch response, so they do not need reset loads.
  val fillBuffer = Reg(UInt(bufferWidth.W))
  val fillPrimeBeat = Reg(UInt(memoryConfig.halfBeatWidth.W))
  val primeOccupied = RegInit(false.B)

  private def sourceScratchAddress(buffer: UInt, beat: UInt): UInt = {
    val halfBeat = beat(memoryConfig.halfBeatWidth - 1, 0)
    (buffer * memoryConfig.halfBeats.U + halfBeat)(
      sourceScratchAddressWidth - 1,
      0
    )
  }

  val sourceScratchWriteWord = Wire(
    Vec(
      blockLanes,
      Vec(
        config.components,
        Vec(halfCount, UInt(coefficientPreprocessWidth.W))
      )
    )
  )
  for (lane <- 0 until blockLanes) {
    for (component <- 0 until config.components) {
      sourceScratchWriteWord(lane)(component)(0) :=
        memory.io.prefetchLow(component)(lane)(
          config.torusWidth - 1,
          coefficientDiscardBits
        )
      sourceScratchWriteWord(lane)(component)(1) :=
        memory.io.prefetchHigh(component)(lane)(
          config.torusWidth - 1,
          coefficientDiscardBits
        )
    }
  }
  sourceScratch.io.writeEnable := memory.io.prefetchValid
  sourceScratch.io.writeAddress := sourceScratchAddress(
    fillBuffer,
    memory.io.prefetchBeat
  )
  sourceScratch.io.inputData := sourceScratchWriteWord.asUInt
  sourceScratch.io.primeCapture := memory.io.prefetchValid &&
    memory.io.prefetchBeat === fillPrimeBeat

  // Completed prefetches wait here until the serialized preprocessor can
  // claim a digit buffer. Source buffers remain occupied while queued.
  val readySourceBuffers = Module(
    new Queue(
      UInt(bufferWidth.W),
      bufferCount,
      pipe = true,
      flow = true
    )
  )

  // Accept external commands at the transform row-stream interval.  A
  // source buffer is released shortly after its final span has entered the
  // selection pipeline, well before the same ping-pong slot is needed again.
  val commandCooldown = RegInit(0.U(cooldownWidth.W))
  val commandAvailable = memory.io.contextLoaded(io.commandContext) &&
    (!busy(io.commandContext) ||
      (memory.io.updateDone &&
        memory.io.updateDoneContext === io.commandContext))
  io.commandReady := commandCooldown === 0.U &&
    memory.io.prefetchReady && !fillOutstanding && !primeOccupied &&
    hasFreeBuffer && commandAvailable
  val commandFire = io.commandValid && io.commandReady
  when(commandFire) {
    commandCooldown := (commandInterval - 1).U
  }.elsewhen(commandCooldown =/= 0.U) {
    commandCooldown := commandCooldown - 1.U
  }
  when(io.commandValid) {
    assert(io.commandContext < batchContexts.U, "invalid command context")
  }

  // Preprocessing scheduler state is declared before drain admission so the
  // latter can prove that no workset still owns a transient source buffer.
  val preprocessActive = RegInit(false.B)
  val preprocessSourceBuffer = RegInit(0.U(bufferWidth.W))
  val preprocessDigitBuffer = RegInit(0.U(digitBufferWidth.W))
  val preprocessComponent = RegInit(0.U(componentWidth.W))
  val preprocessPhase = RegInit(0.U(levelWidth.W))
  val preprocessBeat = RegInit(0.U(forwardBeatWidth.W))
  val preprocessLaunchValid = preprocessActive &&
    preprocessPhase < halfCount.U
  val preprocessHalf = preprocessPhase === 1.U

  // A packed, replicated block-memory transpose holds all levels for the
  // emitting, pipeline-draining, and newly starting commands. The emitter
  // reads one low/high pair while the preprocessor writes another workset.
  val digitBufferOccupied = RegInit(
    VecInit(Seq.fill(digitBufferCount)(false.B))
  )
  val readyDigitBuffers = Module(
    new Queue(
      UInt(digitBufferWidth.W),
      digitBufferCount,
      pipe = true,
      flow = true
    )
  )

  // Keep one elastic stage between the synchronous transpose read and an
  // explicit physical-cut register. A bare block-RAM read still left its
  // clock-to-output delay and the SLR crossing in the same cycle as the first
  // forward-transform logic. The extra cut removes that path without reducing
  // the one-beat-per-cycle emission rate.
  val emitLeadActive = RegInit(false.B)
  val emitBuffer = RegInit(0.U(digitBufferWidth.W))
  val emitRow = RegInit(0.U(rowWidth.W))
  val emitBeat = RegInit(0.U(forwardBeatWidth.W))
  val readValid = RegInit(false.B)
  val readRow = RegInit(0.U(rowWidth.W))
  val readLast = RegInit(false.B)
  val leadTransformStart = RegInit(false.B)
  val readTransformStart = RegInit(false.B)
  val outputValid = RegInit(false.B)
  val outputRow = RegInit(0.U(rowWidth.W))
  val outputLast = RegInit(false.B)
  val digitReadLow = Wire(
    Vec(config.forwardLanes, SInt(config.baseBits.W))
  )
  val digitReadHigh = Wire(
    Vec(config.forwardLanes, SInt(config.baseBits.W))
  )
  val digitOutputBoundary = Module(
    new PhysicalCutRegister(
      2 * config.forwardLanes * config.baseBits
    )
  )
  digitOutputBoundary.io.clock := clock
  val outputAdvance = !outputValid || io.pairReady
  val readAdvance = !readValid || outputAdvance
  digitOutputBoundary.io.enable := outputAdvance && readValid
  digitOutputBoundary.io.inputData := Cat(
    digitReadHigh.asUInt,
    digitReadLow.asUInt
  )
  val boundaryDigits = digitOutputBoundary.io.outputData.asTypeOf(
    Vec(
      2,
      Vec(config.forwardLanes, SInt(config.baseBits.W))
    )
  )

  io.pairValid := outputValid
  io.rowIndex := outputRow
  io.pairLast := outputLast
  // SGen consumes this marker one cycle before the corresponding first beat.
  // Gate it with the read-to-output transfer so a downstream stall cannot
  // separate the marker from its data.
  io.transformStart :=
    readValid && readTransformStart && outputAdvance
  for (lane <- 0 until config.forwardLanes) {
    io.coefficientLow(lane) :=
      boundaryDigits(0)(lane) << config.forwardFormat.fractionalBits
    io.coefficientHigh(lane) :=
      boundaryDigits(1)(lane) << config.forwardFormat.fractionalBits
  }

  val emitLeadFire = emitLeadActive && readAdvance
  val emitBeatLast = emitBeat === (config.forwardBeats - 1).U
  val emitFinalRow = emitRow === (rows - 1).U
  val emitFinalLead = emitLeadFire && emitBeatLast && emitFinalRow
  val emitLaunchFromIdle = !emitLeadActive &&
    readyDigitBuffers.io.deq.valid && readAdvance
  val emitSwitch = emitFinalLead && readyDigitBuffers.io.deq.valid
  val emitFollowingRow = emitLeadFire && emitBeatLast && !emitFinalRow
  readyDigitBuffers.io.deq.ready := emitLaunchFromIdle || emitSwitch

  when(outputAdvance) {
    outputValid := readValid
    outputRow := readRow
    outputLast := readLast
  }
  when(readAdvance) {
    readValid := emitLeadActive
    readRow := emitRow
    readLast := emitLeadActive && emitBeatLast
    readTransformStart := leadTransformStart
    leadTransformStart :=
      emitLaunchFromIdle || emitSwitch || emitFollowingRow
  }

  when(emitLaunchFromIdle) {
    emitLeadActive := true.B
    emitBuffer := readyDigitBuffers.io.deq.bits
    emitRow := 0.U
    emitBeat := 0.U
  }.elsewhen(emitLeadFire) {
    when(emitBeatLast) {
      emitBeat := 0.U
      when(emitFinalRow) {
        when(emitSwitch) {
          emitLeadActive := true.B
          emitBuffer := readyDigitBuffers.io.deq.bits
          emitRow := 0.U
        }.otherwise {
          emitLeadActive := false.B
          emitRow := 0.U
        }
      }.otherwise {
        emitRow := emitRow + 1.U
      }
    }.otherwise {
      emitBeat := emitBeat + 1.U
    }
  }

  val digitAvailable = VecInit((0 until digitBufferCount).map { buffer =>
    !digitBufferOccupied(buffer) ||
      (emitFinalLead && emitBuffer === buffer.U)
  })
  val hasFreeDigitBuffer = digitAvailable.asUInt.orR
  val freeDigitBuffer = PriorityEncoder(digitAvailable.asUInt)

  val preprocessFinalHalfLaunch = preprocessActive &&
    preprocessComponent === (config.components - 1).U &&
    preprocessHalf &&
    preprocessBeat === (config.forwardBeats - 1).U
  val preprocessFinalCycle = preprocessActive &&
    preprocessComponent === (config.components - 1).U &&
    preprocessBeat === (config.forwardBeats - 1).U &&
    preprocessPhase === (config.levels - 1).U
  val preprocessCanAccept = !preprocessActive || preprocessFinalCycle
  readySourceBuffers.io.deq.ready :=
    preprocessCanAccept && hasFreeDigitBuffer
  val preprocessStart = readySourceBuffers.io.deq.fire

  when(preprocessStart) {
    preprocessActive := true.B
    preprocessSourceBuffer := readySourceBuffers.io.deq.bits
    preprocessDigitBuffer := freeDigitBuffer
    preprocessComponent := 0.U
    preprocessPhase := 0.U
    preprocessBeat := 0.U
  }.elsewhen(preprocessActive) {
    when(preprocessPhase === (config.levels - 1).U) {
      preprocessPhase := 0.U
      when(preprocessBeat === (config.forwardBeats - 1).U) {
        preprocessBeat := 0.U
        when(preprocessComponent === (config.components - 1).U) {
          preprocessActive := false.B
          preprocessComponent := 0.U
        }.otherwise {
          preprocessComponent := preprocessComponent + 1.U
        }
      }.otherwise {
        preprocessBeat := preprocessBeat + 1.U
      }
    }.otherwise {
      preprocessPhase := preprocessPhase + 1.U
    }
  }

  // The final source span is fully captured by the candidate registers three
  // clocks after launch. Releasing there, instead of waiting for the entire
  // rotator pipeline, preserves the two-buffer prefetch cadence.
  sourceReleaseValid := ShiftRegister(
    preprocessFinalHalfLaunch,
    3,
    false.B,
    true.B
  )
  sourceReleaseBuffer := ShiftRegister(preprocessSourceBuffer, 3)

  val drainCanStart = !fillOutstanding && memory.io.drainStartReady &&
    bufferedUpdatesIdle &&
    !readySourceBuffers.io.deq.valid && !preprocessActive &&
    !digitBufferOccupied.asUInt.orR && !emitLeadActive && !readValid &&
    !outputValid &&
    !io.commandValid && io.drainContext < batchContexts.U &&
    !busy(io.drainContext)
  io.drainStartReady := drainCanStart
  val drainFire = io.drainStart && drainCanStart
  when(io.drainStart) {
    assert(drainCanStart, "precomputed drain started while unavailable")
    assert(io.drainContext < batchContexts.U, "invalid drain context")
  }

  memory.io.drainStart := drainFire
  memory.io.drainContext := io.drainContext
  memory.io.drainReady := io.drainReady
  io.drainValid := memory.io.drainValid
  io.drain := memory.io.drain
  io.drainDone := memory.io.drainDone
  io.drainDoneContext := memory.io.drainDoneContext

  memory.io.prefetchStart := commandFire
  memory.io.prefetchContext := io.commandContext
  when(commandFire) {
    fillOutstanding := true.B
    fillBuffer := freeBuffer
    bufferExponent(freeBuffer) := io.exponent
    val firstRingPosition =
      ((2 * config.polynomialSize).U((ringWidth + 1).W) -&
        io.exponent.pad(ringWidth + 1))(ringWidth - 1, 0)
    val firstPhysicalBlock =
      firstRingPosition(ringWidth - 1, blockLaneWidth)
    fillPrimeBeat :=
      firstPhysicalBlock(memoryConfig.halfBeatWidth - 1, 0)
  }

  when(memory.io.prefetchValid) {
    assert(fillOutstanding, "prefetch data returned without an owner")
  }

  readySourceBuffers.io.enq.valid := memory.io.prefetchDone
  readySourceBuffers.io.enq.bits := fillBuffer
  when(readySourceBuffers.io.enq.valid) {
    assert(readySourceBuffers.io.enq.ready, "source buffer queue overflow")
  }
  when(memory.io.prefetchDone) {
    fillOutstanding := false.B
    primeOccupied := true.B
  }

  // Source-buffer ownership has three mutually exclusive endpoints. A new
  // allocation wins if a release and allocation ever share an edge.
  for (buffer <- 0 until bufferCount) {
    when(
      sourceReleaseValid && sourceReleaseBuffer === buffer.U
    ) {
      bufferOccupied(buffer) := false.B
    }
    when(commandFire && freeBuffer === buffer.U) {
      bufferOccupied(buffer) := true.B
    }
  }

  // Capture the serialized workset identity before any wide source mux. All
  // later pipeline registers advance unconditionally; validity is carried as
  // a narrow companion, so bubbles drain naturally and no wide CE net is
  // introduced.
  val selectionValid = RegNext(preprocessLaunchValid, false.B)
  val selectionSourceBuffer = RegEnable(
    preprocessSourceBuffer,
    0.U(bufferWidth.W),
    preprocessLaunchValid
  )
  val selectionDigitBuffer = RegEnable(
    preprocessDigitBuffer,
    0.U(digitBufferWidth.W),
    preprocessLaunchValid
  )
  val selectionComponent = RegEnable(
    preprocessComponent,
    0.U(componentWidth.W),
    preprocessLaunchValid
  )
  val selectionHalf = RegEnable(
    preprocessHalf,
    false.B,
    preprocessLaunchValid
  )
  val selectionBeat = RegEnable(
    preprocessBeat,
    0.U(forwardBeatWidth.W),
    preprocessLaunchValid
  )
  val selectionExponent = RegEnable(
    bufferExponent(preprocessSourceBuffer),
    0.U(config.exponentWidth.W),
    preprocessLaunchValid
  )

  val destination = Wire(UInt(ringWidth.W))
  destination :=
    (selectionBeat << outputLaneWidth) +
      Mux(selectionHalf, config.points.U, 0.U)
  val ringPosition =
    (2 * config.polynomialSize).U((ringWidth + 1).W) +&
      destination.pad(ringWidth + 1) -&
      selectionExponent.pad(ringWidth + 1)
  val v = ringPosition(ringWidth - 1, 0)
  val firstRing = v(ringWidth - 1, blockLaneWidth)
  val offset = v(blockLaneWidth - 1, 0)
  val physical = firstRing(log2Ceil(blocks) - 1, 0)
  val spanBlockIndices = VecInit((0 until spanBlocks).map { block =>
    (physical + block.U)(blockIndexWidth - 1, 0)
  })
  val currentFirstBlock =
    (Mux(selectionHalf, memoryConfig.halfBeats.U, 0.U) +
      selectionBeat * outputBlocks.U)(blockIndexWidth - 1, 0)
  val currentBlockIndices = VecInit((0 until outputBlocks).map { block =>
    (currentFirstBlock + block.U)(blockIndexWidth - 1, 0)
  })
  val preprocessScratchReadAddresses = Wire(
    Vec(sourceScratchReadPorts, UInt(sourceScratchAddressWidth.W))
  )
  for (block <- 0 until newSpanBlocks) {
    preprocessScratchReadAddresses(block) := sourceScratchAddress(
      selectionSourceBuffer,
      spanBlockIndices(block + 1)
    )
  }
  for (block <- 0 until outputBlocks) {
    preprocessScratchReadAddresses(newSpanBlocks + block) :=
      sourceScratchAddress(
        selectionSourceBuffer,
        currentBlockIndices(block)
      )
  }

  val sourceScratchReadData = sourceScratch.io.outputData.asTypeOf(
    Vec(sourceScratchReadPorts, UInt(sourceScratchWordWidth.W))
  )
  val sourceScratchReadWords = VecInit(
    sourceScratchReadData.map(_.asTypeOf(chiselTypeOf(sourceScratchWriteWord)))
  )
  // Each physical scratch bank owns a preserved read-address register. Delay
  // the narrow metadata by the same two cycles as address cut plus LUTRAM
  // output register; requests still launch every cycle.
  val candidateValid = ShiftRegister(selectionValid, 2, false.B, true.B)
  val candidateOffset = ShiftRegister(offset, 2)
  val candidateFirstRing = ShiftRegister(firstRing, 2)
  val candidateDigitBuffer = ShiftRegister(selectionDigitBuffer, 2)
  val candidateComponent = ShiftRegister(selectionComponent, 2)
  val candidateHalf = ShiftRegister(selectionHalf, 2)
  val candidateBeat = ShiftRegister(selectionBeat, 2)
  val candidateSpanHalves = ShiftRegister(
    VecInit(spanBlockIndices.map(_(blockIndexWidth - 1))),
    2
  )

  val primeScratchWord =
    sourceScratch.io.primeData.asTypeOf(chiselTypeOf(sourceScratchWriteWord))
  // Low and high halves now launch on alternating cycles. Keep one narrow
  // trailing block for each logical half so beat N+1 can reuse beat N's tail
  // without restoring the eliminated fifth scratch read. At a component
  // boundary, the low stream continues from the prior high tail and vice
  // versa; the first component starts both halves from the packed prime word.
  val rollingSpanHeads = Reg(
    Vec(
      halfCount,
      Vec(
        config.components,
        Vec(blockLanes, UInt(coefficientPreprocessWidth.W))
      )
    )
  )
  val candidateFirstBeat = candidateBeat === 0.U
  val candidateFirstComponentBeat = candidateComponent === 0.U &&
    candidateFirstBeat
  val rollingHeadIndex = Mux(
    candidateFirstBeat,
    (!candidateHalf).asUInt,
    candidateHalf.asUInt
  )
  val candidateSpanHead = VecInit((0 until blockLanes).map { lane =>
    Mux(
      candidateFirstComponentBeat,
      primeScratchWord(lane)(candidateComponent)(candidateSpanHalves(0)),
      rollingSpanHeads(rollingHeadIndex)(candidateComponent)(lane)
    )
  })
  val candidateSpan = VecInit(
    candidateSpanHead ++ (1 until spanBlocks).flatMap { block =>
      (0 until blockLanes).map { lane =>
        sourceScratchReadWords(block - 1)(lane)(candidateComponent)(
          candidateSpanHalves(block)
        )
      }
    }
  )
  val deferredFirstLowTail = Reg(
    Vec(blockLanes, UInt(coefficientPreprocessWidth.W))
  )
  val deferredFirstLowTailValid = RegInit(false.B)
  val deferFirstLowTail = candidateFirstBeat &&
    candidateComponent =/= 0.U && !candidateHalf
  when(candidateValid) {
    when(deferFirstLowTail) {
      for (lane <- 0 until blockLanes) {
        deferredFirstLowTail(lane) :=
          sourceScratchReadWords(newSpanBlocks - 1)(lane)(candidateComponent)(
            candidateSpanHalves(spanBlocks - 1)
          )
      }
      deferredFirstLowTailValid := true.B
    }.otherwise {
      for (component <- 0 until config.components) {
        for (lane <- 0 until blockLanes) {
          rollingSpanHeads(candidateHalf.asUInt)(component)(lane) :=
            sourceScratchReadWords(newSpanBlocks - 1)(lane)(component)(
              candidateSpanHalves(spanBlocks - 1)
            )
        }
      }
    }
    when(
      candidateFirstBeat && candidateComponent =/= 0.U && candidateHalf
    ) {
      assert(
        deferredFirstLowTailValid,
        "component boundary lost its deferred low-half tail"
      )
      for (lane <- 0 until blockLanes) {
        rollingSpanHeads(0)(candidateComponent)(lane) :=
          deferredFirstLowTail(lane)
      }
      deferredFirstLowTailValid := false.B
    }
  }
  val candidateCurrent = VecInit((0 until outputBlocks).flatMap { block =>
    (0 until blockLanes).map { lane =>
      sourceScratchReadWords(newSpanBlocks + block)(lane)(candidateComponent)(
        candidateHalf.asUInt
      )
    }
  })
  val candidateFirst = candidateFirstComponentBeat && !candidateHalf
  when(candidateValid && candidateFirst) {
    assert(primeOccupied, "rolling source span started without a prime word")
    primeOccupied := false.B
  }

  val selectedSpanBoundary = Module(
    new PhysicalCutRegister(spanSize * coefficientPreprocessWidth)
  )
  selectedSpanBoundary.io.clock := clock
  selectedSpanBoundary.io.enable := true.B
  selectedSpanBoundary.io.inputData := candidateSpan.asUInt
  val boundarySpan = selectedSpanBoundary.io.outputData.asTypeOf(candidateSpan)

  val currentBoundary = Module(
    new PhysicalCutRegister(outputLanes * coefficientPreprocessWidth)
  )
  currentBoundary.io.clock := clock
  currentBoundary.io.enable := true.B
  currentBoundary.io.inputData := candidateCurrent.asUInt
  val boundaryCurrent =
    currentBoundary.io.outputData.asTypeOf(candidateCurrent)

  val boundaryValid = RegNext(candidateValid, false.B)
  val boundaryOffset = RegNext(candidateOffset)
  val boundaryFirstRing = RegNext(candidateFirstRing)
  val boundaryDigitBuffer = RegNext(candidateDigitBuffer)
  val boundaryComponent = RegNext(candidateComponent)
  val boundaryHalf = RegNext(candidateHalf)
  val boundaryBeat = RegNext(candidateBeat)

  val window = Module(
    new PipelinedWindowedNegacyclicRotatorSpan(
      config.polynomialSize,
      coefficientPreprocessWidth,
      blockLanes,
      outputLanes
    )
  )
  for (block <- 0 until spanBlocks) {
    window.io.input(block) := VecInit((0 until blockLanes).map { lane =>
      boundarySpan(block * blockLanes + lane)
    })
  }
  window.io.offset := boundaryOffset
  window.io.firstRingBlock := boundaryFirstRing
  window.io.enable := true.B

  val alignedValid = ShiftRegister(
    boundaryValid,
    window.latency,
    false.B,
    true.B
  )
  val alignedCurrent = ShiftRegister(boundaryCurrent, window.latency)
  val alignedDigitBuffer = ShiftRegister(
    boundaryDigitBuffer,
    window.latency
  )
  val alignedComponent = ShiftRegister(boundaryComponent, window.latency)
  val alignedHalf = ShiftRegister(boundaryHalf, window.latency)
  val alignedBeat = ShiftRegister(boundaryBeat, window.latency)

  val biased = Wire(Vec(outputLanes, UInt(coefficientPreprocessWidth.W)))
  for (lane <- 0 until outputLanes) {
    val difference =
      (window.io.output(lane) - alignedCurrent(lane))(
        coefficientPreprocessWidth - 1,
        0
      )
    biased(lane) :=
      (difference +&
        coefficientPreprocessBias.U(coefficientPreprocessWidth.W))(
        coefficientPreprocessWidth - 1,
        0
      )
  }
  val stagedBiased = RegNext(biased)
  val digitWriteValid = RegNext(alignedValid, false.B)
  val digitWriteBuffer = RegNext(alignedDigitBuffer)
  val digitWriteComponent = RegNext(alignedComponent)
  val digitWriteHalf = RegNext(alignedHalf)
  val digitWriteBeat = RegNext(alignedBeat)
  private val digitAddressDepth = rows * config.forwardBeats
  private val digitMemoryDepth =
    digitBufferCount * digitAddressDepth
  private val digitMemoryAddressWidth = counterWidth(digitMemoryDepth)
  private def digitMemoryAddress(
      buffer: UInt,
      row: UInt,
      beat: UInt
  ): UInt = {
    (buffer * digitAddressDepth.U + row * config.forwardBeats.U + beat)(
      digitMemoryAddressWidth - 1,
      0
    )
  }
  val centeredDigits = Wire(
    Vec(
      config.forwardLanes,
      Vec(config.levels, SInt(config.baseBits.W))
    )
  )
  for (lane <- 0 until config.forwardLanes) {
    for (level <- 0 until config.levels) {
      val shift =
        coefficientPreprocessWidth - (level + 1) * config.baseBits
      val digit =
        (stagedBiased(lane) >> shift)(config.baseBits - 1, 0)
      centeredDigits(lane)(level) :=
        (digit - (BigInt(1) << (config.baseBits - 1)).U)(
          config.baseBits - 1,
          0
        ).asSInt
    }
  }

  // The serialized rotator produces the low and high halves of one beat on
  // adjacent launch phases. Pair them before the transpose, then write one
  // decomposition level per cycle. For Set II this turns two duplicated
  // 48 x 2,560-bit memories into one memory of the same shape: level zero is
  // written with the high-half result and level one is written while the next
  // low half is captured. More levels use the otherwise idle launch phases.
  val heldLowDigits = Reg(
    Vec(
      config.forwardLanes,
      Vec(config.levels, SInt(config.baseBits.W))
    )
  )
  val heldLowValid = RegInit(false.B)
  val heldLowBuffer = Reg(UInt(digitBufferWidth.W))
  val heldLowComponent = Reg(UInt(componentWidth.W))
  val heldLowBeat = Reg(UInt(forwardBeatWidth.W))
  val serializedHighDigits = Reg(
    Vec(
      config.forwardLanes,
      Vec(config.levels - 1, SInt(config.baseBits.W))
    )
  )
  val serializedValid = RegInit(false.B)
  val serializedLevel = RegInit(1.U(levelWidth.W))

  val lowDigitArrives = digitWriteValid && !digitWriteHalf
  val highDigitArrives = digitWriteValid && digitWriteHalf
  when(lowDigitArrives) {
    assert(!heldLowValid, "digit low half overwritten before pairing")
    heldLowDigits := centeredDigits
    heldLowValid := true.B
    heldLowBuffer := digitWriteBuffer
    heldLowComponent := digitWriteComponent
    heldLowBeat := digitWriteBeat
  }
  when(highDigitArrives) {
    assert(heldLowValid, "digit high half arrived without its low half")
    assert(!serializedValid, "digit level serializer overflow")
    assert(heldLowBuffer === digitWriteBuffer, "digit buffer halves differ")
    assert(
      heldLowComponent === digitWriteComponent,
      "digit component halves differ"
    )
    assert(heldLowBeat === digitWriteBeat, "digit beat halves differ")
    heldLowValid := false.B
    for (lane <- 0 until config.forwardLanes) {
      for (level <- 1 until config.levels) {
        serializedHighDigits(lane)(level - 1) := centeredDigits(lane)(level)
      }
    }
    serializedValid := true.B
    serializedLevel := 1.U
  }

  when(serializedValid) {
    when(serializedLevel === (config.levels - 1).U) {
      serializedValid := false.B
    }.otherwise {
      serializedLevel := serializedLevel + 1.U
    }
  }

  val digitMemoryWriteValid = highDigitArrives || serializedValid
  val digitMemoryWriteLevel = Mux(highDigitArrives, 0.U, serializedLevel)
  val digitMemoryWriteRow =
    heldLowComponent * config.levels.U + digitMemoryWriteLevel
  val digitMemoryWriteAddress = digitMemoryAddress(
    heldLowBuffer,
    digitMemoryWriteRow,
    heldLowBeat
  )
  val digitMemoryWriteData = Wire(
    Vec(
      halfCount,
      Vec(config.forwardLanes, SInt(config.baseBits.W))
    )
  )
  val serializedHighIndex = (serializedLevel - 1.U)(
    counterWidth(config.levels - 1) - 1,
    0
  )
  for (lane <- 0 until config.forwardLanes) {
    val serializedHighDigit =
      if (config.levels == 2) serializedHighDigits(lane)(0)
      else serializedHighDigits(lane)(serializedHighIndex)
    digitMemoryWriteData(0)(lane) :=
      heldLowDigits(lane)(digitMemoryWriteLevel)
    digitMemoryWriteData(1)(lane) := Mux(
      highDigitArrives,
      centeredDigits(lane)(0),
      serializedHighDigit
    )
  }

  val digitMemory = SyncReadMem(
    digitMemoryDepth,
    Vec(
      halfCount,
      Vec(config.forwardLanes, SInt(config.baseBits.W))
    )
  )
  when(digitMemoryWriteValid) {
    digitMemory.write(digitMemoryWriteAddress, digitMemoryWriteData)
  }

  // The final serialized level is written on the cycle after the final high
  // half. Emission starts at row zero, so that last address has a complete
  // row stream of lead time before it can be read. Publish the buffer now to
  // preserve the External Product's proven zero-offset rotating-bank phase.
  val digitWriteFinal = highDigitArrives &&
    digitWriteComponent === (config.components - 1).U &&
    digitWriteBeat === (config.forwardBeats - 1).U
  readyDigitBuffers.io.enq.valid := digitWriteFinal
  readyDigitBuffers.io.enq.bits := digitWriteBuffer
  when(digitWriteFinal) {
    assert(readyDigitBuffers.io.enq.ready, "digit buffer queue overflow")
  }

  // A final emitted beat frees its backing transpose at the same edge that
  // the block RAM captures its read. Allow the preprocessor to allocate that
  // buffer immediately; the allocation assignment deliberately has priority.
  for (buffer <- 0 until digitBufferCount) {
    when(emitFinalLead && emitBuffer === buffer.U) {
      digitBufferOccupied(buffer) := false.B
    }
    when(preprocessStart && freeDigitBuffer === buffer.U) {
      digitBufferOccupied(buffer) := true.B
    }
  }

  val requestedDigitReadAddress =
    digitMemoryAddress(emitBuffer, emitRow, emitBeat)
  val heldDigitReadAddress = RegInit(0.U(digitMemoryAddressWidth.W))
  when(emitLeadFire) {
    heldDigitReadAddress := requestedDigitReadAddress
  }
  // A SyncReadMem's output is unspecified on a disabled read. Keep the last
  // issued address active while an output beat is stalled so pairValid always
  // denotes stable coefficient data, including the final beat of a workset.
  val digitReadEnable = emitLeadFire || readValid
  val activeDigitReadAddress = Mux(
    emitLeadFire,
    requestedDigitReadAddress,
    heldDigitReadAddress
  )
  val digitReadWords = digitMemory.read(
    activeDigitReadAddress,
    digitReadEnable
  )
  for (lane <- 0 until config.forwardLanes) {
    digitReadLow(lane) := digitReadWords(0)(lane)
    digitReadHigh(lane) := digitReadWords(1)(lane)
  }
  when(digitMemoryWriteValid && digitReadEnable) {
    assert(
      digitMemoryWriteAddress =/= activeDigitReadAddress,
      "digit transpose read and write targeted the same address"
    )
  }

  when(io.updateFirst && io.updateValid) {
    assert(busy(io.updateContext), "inverse update targets an idle context")
  }
  for (context <- 0 until batchContexts) {
    when(
      memory.io.updateDone &&
        memory.io.updateDoneContext === context.U
    ) {
      busy(context) := false.B
    }
    when(commandFire && io.commandContext === context.U) {
      busy(context) := true.B
    }
  }
  when(io.loadStart) {
    assert(!busy(io.loadContext), "cannot load an in-flight context")
    assert(!fillOutstanding, "cannot load during an accumulator prefetch")
    assert(!readySourceBuffers.io.deq.valid, "cannot load queued source data")
    assert(!preprocessActive, "cannot load during coefficient preprocessing")
    assert(!digitBufferOccupied.asUInt.orR, "cannot load live digit data")
    assert(
      !emitLeadActive && !readValid && !outputValid,
      "cannot load during emission"
    )
    assert(!io.updateValid, "cannot load with inverse update data")
    assert(bufferedUpdatesIdle, "cannot load with buffered inverse updates")
  }

  sourceScratch.io.readAddresses := preprocessScratchReadAddresses.asUInt
  sourceScratch.io.readEnables :=
    VecInit(Seq.fill(sourceScratchReadPorts)(selectionValid)).asUInt
}

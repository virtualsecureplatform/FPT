package fpt

import chisel3._
import chisel3.util._

sealed trait BatchedCoefficientStorage

object BatchedCoefficientStorage {
  case object RegisterArray extends BatchedCoefficientStorage
  case object ReplicatedBanks extends BatchedCoefficientStorage
  case object PrecomputedWindowedReplicatedBanks
      extends BatchedCoefficientStorage
  case object PrecomputedWindowedBufferedSingleBanks
      extends BatchedCoefficientStorage
  case object BitwiseReplicatedBanks extends BatchedCoefficientStorage
}

final case class BatchedCmuxEngineConfig(
    engine: CmuxEngineConfig,
    batchContexts: Int,
    coefficientStorage: BatchedCoefficientStorage =
      BatchedCoefficientStorage.RegisterArray,
    serializeInverseComponents: Boolean = false,
    useSynchronousExternalProductMemory: Boolean = false,
    useRotatingThreeBankExternalProductMemory: Boolean = false,
    decoupledBootstrappingKey: Boolean = false,
    pendingKeyRequestEntries: Int = 4,
    registerForwardSlrInput: Boolean = false,
    registerInverseSlrOutput: Boolean = false,
    coefficientPreprocessGuardBits: Option[Int] = None
) {
  require(batchContexts >= 2)
  require(
    pendingKeyRequestEntries >= 1 && isPow2(pendingKeyRequestEntries),
    "pending key-request entries must be a positive power of two"
  )
  require(
    engine.forwardSGen.isDefined && engine.inverseSGen.isDefined,
    "the batched engine requires continuous-flow SGen transforms"
  )
  coefficientPreprocessGuardBits.foreach { guardBits =>
    require(
      guardBits >= 0 && guardBits < engine.coefficient.remainingBits,
      "coefficient preprocessing guard bits must fit below the gadget digits"
    )
  }
  if (serializeInverseComponents) {
    require(engine.coefficient.components == 2)
    require(engine.externalProduct.outputComponents == 2)
    require(
      engine.inverseSGen.exists(_.inputLeadCycles == 1),
      "serialized inverse components require a one-cycle SGen input lead"
    )
  }
  if (useRotatingThreeBankExternalProductMemory) {
    require(serializeInverseComponents)
    require(useSynchronousExternalProductMemory)
  }
  val rows: Int = engine.externalProduct.rows
  val commandInterval: Int = rows * engine.forwardTransform.frameBeats
  val contextWidth: Int = TransformUtil.counterWidth(batchContexts)
}

/** Fixed-rate destination register at the coefficient-to-forward-SGen SLR cut.
  *
  * SGen cannot be backpressured after its one-cycle start lead. The coefficient
  * store already provides that same fixed-rate contract, so this stage accepts
  * every source beat and asserts if the destination ever violates it. Keeping
  * readiness constant prevents a combinational control path from returning
  * across the SLR; delaying start and valid with the payload preserves SGen's
  * lead-cycle relationship.
  */
private[fpt] final class BatchedCmuxForwardInputBoundary(
    lanes: Int,
    dataWidth: Int,
    preservePhysicalRegister: Boolean = true
) extends Module {
  require(lanes >= 1)
  require(dataWidth >= 1)

  val io = IO(new Bundle {
    val sourceStart = Input(Bool())
    val sourceValid = Input(Bool())
    val sourceReady = Output(Bool())
    val sourceLow = Input(Vec(lanes, SInt(dataWidth.W)))
    val sourceHigh = Input(Vec(lanes, SInt(dataWidth.W)))
    val destinationStart = Output(Bool())
    val destinationValid = Output(Bool())
    val destinationReady = Input(Bool())
    val destinationLow = Output(Vec(lanes, SInt(dataWidth.W)))
    val destinationHigh = Output(Vec(lanes, SInt(dataWidth.W)))
  })

  private val inputPayload = Cat(io.sourceHigh.asUInt, io.sourceLow.asUInt)
  private val outputPayload = if (preservePhysicalRegister) {
    val payloadBoundary = Module(
      new PhysicalCutRegister(2 * lanes * dataWidth)
    )
    payloadBoundary.io.clock := clock
    // Capture unconditionally so sourceValid does not cross the SLR and drive
    // the clock enable of every payload register.
    payloadBoundary.io.enable := true.B
    payloadBoundary.io.inputData := inputPayload
    payloadBoundary.io.outputData
  } else {
    RegNext(inputPayload)
  }
  private val unpacked = outputPayload.asTypeOf(
    Vec(2, Vec(lanes, SInt(dataWidth.W)))
  )
  io.destinationLow := unpacked(0)
  io.destinationHigh := unpacked(1)
  private val inputControl = Cat(io.sourceValid, io.sourceStart)
  private val outputControl = if (preservePhysicalRegister) {
    val controlBoundary = Module(new PhysicalCutRegister(2))
    controlBoundary.io.clock := clock
    controlBoundary.io.enable := true.B
    controlBoundary.io.inputData := inputControl
    controlBoundary.io.outputData
  } else {
    RegNext(inputControl)
  }
  io.destinationStart := outputControl(0)
  io.destinationValid := outputControl(1)
  io.sourceReady := true.B

  when(io.destinationValid) {
    assert(
      io.destinationReady,
      "forward SGen destination boundary cannot be backpressured"
    )
  }
}

/** Fixed-rate receiver register at the inverse-SGen-to-join SLR cut.
  *
  * The serialized inverse transform cannot be stopped once a frame starts.
  * Registering its payload and valid token unconditionally makes the crossing
  * data-only and leaves the ComponentJoin write-enable fanout local to SLR1.
  */
private[fpt] final class BatchedCmuxInverseOutputBoundary(
    lanes: Int,
    dataWidth: Int,
    preservePhysicalRegister: Boolean = true
) extends Module {
  require(lanes >= 1)
  require(dataWidth >= 1)

  val io = IO(new Bundle {
    val sourceValid = Input(Bool())
    val sourceReady = Output(Bool())
    val sourceLow = Input(Vec(lanes, SInt(dataWidth.W)))
    val sourceHigh = Input(Vec(lanes, SInt(dataWidth.W)))
    val destinationValid = Output(Bool())
    val destinationReady = Input(Bool())
    val destinationLow = Output(Vec(lanes, SInt(dataWidth.W)))
    val destinationHigh = Output(Vec(lanes, SInt(dataWidth.W)))
  })

  private val inputPayload = Cat(io.sourceHigh.asUInt, io.sourceLow.asUInt)
  private val outputPayload = if (preservePhysicalRegister) {
    val payloadBoundary = Module(
      new PhysicalCutRegister(2 * lanes * dataWidth)
    )
    payloadBoundary.io.clock := clock
    payloadBoundary.io.enable := true.B
    payloadBoundary.io.inputData := inputPayload
    payloadBoundary.io.outputData
  } else {
    RegNext(inputPayload)
  }
  private val unpacked = outputPayload.asTypeOf(
    Vec(2, Vec(lanes, SInt(dataWidth.W)))
  )
  io.destinationLow := unpacked(0)
  io.destinationHigh := unpacked(1)

  private val outputValid = if (preservePhysicalRegister) {
    val validBoundary = Module(new PhysicalCutRegister(1))
    validBoundary.io.clock := clock
    validBoundary.io.enable := true.B
    validBoundary.io.inputData := io.sourceValid
    validBoundary.io.outputData(0)
  } else {
    RegNext(io.sourceValid, false.B)
  }
  io.destinationValid := outputValid
  io.sourceReady := true.B

  when(io.destinationValid) {
    assert(
      io.destinationReady,
      "inverse SGen receiver boundary cannot be backpressured"
    )
  }
}

private[fpt] final class BatchedCmuxPendingKeyRequest(
    contextWidth: Int,
    rowWidth: Int,
    beatWidth: Int,
    inputLanes: Int,
    spectrumWidth: Int
) extends Bundle {
  val first = Bool()
  val tag = UInt(contextWidth.W)
  val row = UInt(rowWidth.W)
  val beat = UInt(beatWidth.W)
  val decomposition = Vec(inputLanes, new ComplexSInt(spectrumWidth))
}

/** Elastic boundary registers and a configurable FIFO between the transforms.
  *
  * The input payload register captures on local readiness, independent of
  * upstream valid. This makes the wide forward-SLR crossing a pure data-to-D
  * path: the crossing valid bit only drives one local register instead of the
  * clock enable of every FIFO bit. Behind it, every payload bit follows the
  * same enqueue-enabled shift-register chain and the count selects the oldest
  * live tap. A matching two-slot registered output FIFO keeps that count and tap
  * selector out of the External Product memory-address cone. Its conservative
  * full handling also prevents downstream readiness from driving any wide
  * payload register enable. The input boundary, configurable middle entries,
  * and two output entries absorb key-memory latency and a registered External
  * Product bank-reuse guard. Input ready is likewise conservative at the full
  * boundary, which removes the downstream-ready round trip from the SLL
  * register clock enables.
  */
private[fpt] final class BatchedCmuxPendingKeyRequestQueue(
    contextWidth: Int,
    rowWidth: Int,
    beatWidth: Int,
    inputLanes: Int,
    spectrumWidth: Int,
    requestEntries: Int = 4
) extends Module {
  require(requestEntries >= 1 && isPow2(requestEntries))

  private def requestType = new BatchedCmuxPendingKeyRequest(
    contextWidth,
    rowWidth,
    beatWidth,
    inputLanes,
    spectrumWidth
  )
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(requestType))
    val deq = Decoupled(requestType)
  })

  val inputBoundary = Reg(requestType)
  val inputBoundaryValid = RegInit(false.B)
  // Keep these as two explicit aggregate registers instead of a generic
  // Queue. At Set-II width, a two-entry Queue can become thousands of LUTRAM
  // bits. Circular pointers avoid shifting either payload on dequeue, so the
  // downstream ready path only reaches the narrow occupancy and read pointer.
  val outputBoundary0 = Reg(requestType)
  val outputBoundary1 = Reg(requestType)
  val outputReadPointer = RegInit(false.B)
  val outputWritePointer = RegInit(false.B)
  val outputCount = RegInit(0.U(2.W))
  // Keep nonempty registered instead of decoding outputCount on the dequeue
  // interface. At this boundary, deq.valid feeds the External Product's
  // UltraRAM address/control cone; terminating the occupancy decode here
  // leaves that crossing as a single-register control path.
  val outputBoundaryValid = RegInit(false.B)

  io.deq.valid := outputBoundaryValid
  io.deq.bits := Mux(
    outputReadPointer,
    outputBoundary1,
    outputBoundary0
  )
  val outputDeqFire = outputBoundaryValid && io.deq.ready
  // Do not refill a full output FIFO from a same-cycle dequeue. Its second
  // resident word covers that conservative cycle without interrupting the
  // consumer, and the wide write enables remain independent of io.deq.ready.
  val outputEnqReady = outputCount =/= 2.U
  // With one configured middle entry, the input boundary itself provides the
  // required elastic slot. Bypass the otherwise redundant full-width request
  // register; this removes roughly one Set-II payload (about 7.8k FFs).
  val requests = if (requestEntries > 1) {
    Some(Reg(Vec(requestEntries, requestType)))
  } else {
    None
  }
  private val requestIndexWidth = TransformUtil.counterWidth(requestEntries)
  private val requestCountWidth =
    TransformUtil.counterWidth(requestEntries + 1)
  val count = WireDefault(0.U(requestCountWidth.W))
  val countReg = if (requestEntries > 1) {
    Some(RegInit(0.U(requestCountWidth.W)))
  } else {
    None
  }
  countReg.foreach(count := _)
  val oldestRequest = if (requestEntries == 1) {
    inputBoundary
  } else {
    val oldest = Mux(count === 0.U, 0.U, count - 1.U)(
      requestIndexWidth - 1,
      0
    )
    requests.get(oldest)
  }
  val middleValid = if (requestEntries == 1) {
    inputBoundaryValid
  } else {
    count =/= 0.U
  }
  val outputEnqFire = middleValid && outputEnqReady
  when(outputEnqFire) {
    when(outputWritePointer) {
      outputBoundary1 := oldestRequest
    }.otherwise {
      outputBoundary0 := oldestRequest
    }
    outputWritePointer := !outputWritePointer
  }
  when(outputDeqFire) {
    outputReadPointer := !outputReadPointer
  }
  when(outputEnqFire =/= outputDeqFire) {
    outputCount := Mux(
      outputEnqFire,
      outputCount + 1.U,
      outputCount - 1.U
    )
    // Enqueue-only always makes the FIFO nonempty. Dequeue-only clears the
    // flag only when removing the last resident word; a full FIFO retains one
    // word because same-cycle refill is deliberately disabled above.
    outputBoundaryValid := Mux(
      outputEnqFire,
      true.B,
      outputCount =/= 1.U
    )
  }

  val queueDeqFire = outputEnqFire
  // Multi-entry configurations retain the SRL-like middle queue. For the
  // one-entry physical configuration, moving the input boundary directly to
  // the output FIFO also frees that boundary for a simultaneous refill.
  val shiftReady = if (requestEntries == 1) {
    outputEnqReady
  } else {
    count =/= requestEntries.U
  }
  val enqFire = inputBoundaryValid && shiftReady
  // Do not use same-cycle downstream readiness here. If both the SRL and
  // input boundary are full, hold the boundary word until the registered
  // count exposes a slot, then advertise that local credit.
  io.enq.ready := !inputBoundaryValid || shiftReady

  // When ready is high, an invalid upstream beat can safely overwrite the
  // payload because its valid bit is cleared at the same edge. Avoiding valid
  // in this clock enable is what keeps the single crossing control bit from
  // becoming a 7,000-plus-load net after placement.
  when(io.enq.ready) {
    inputBoundary := io.enq.bits
    inputBoundaryValid := io.enq.valid
  }.elsewhen(enqFire) {
    inputBoundaryValid := false.B
  }

  // Preserve this name for the implementation flow's targeted fanout
  // replication. Both inputs are local to the pending-request hierarchy.
  // Shift only on enqueue. Dequeue changes the selected live tap, so every
  // data bit retains a single common clock enable and a pure shift chain.
  if (requestEntries > 1) {
    when(enqFire) {
      for (stage <- requestEntries - 1 to 1 by -1) {
        requests.get(stage) := requests.get(stage - 1)
      }
      requests.get(0) := inputBoundary
    }
    when(enqFire =/= queueDeqFire) {
      countReg.get := Mux(enqFire, count + 1.U, count - 1.U)
    }
  }
}

private[fpt] final class BatchedCmuxInverseBeat(
    tagWidth: Int,
    lanes: Int,
    dataWidth: Int
) extends Bundle {
  val first = Bool()
  val tag = UInt(tagWidth.W)
  val input = Vec(lanes, new ComplexSInt(dataWidth))
}

private[fpt] final class BatchedCmuxExternalOutputBeat(
    tagWidth: Int,
    lanes: Int,
    dataWidth: Int
) extends Bundle {
  val first = Bool()
  val tag = UInt(tagWidth.W)
  val input = Vec(lanes, new ComplexSInt(dataWidth))
}

/** Elastic register for the already-selected External Product stream.
  *
  * Selection remains beside the final accumulator memory.  Carrying only one
  * component here halves the register boundary and removes the former
  * downstream component mux while retaining one beat per cycle.
  */
private[fpt] final class BatchedCmuxExternalOutputPipeline(
    tagWidth: Int,
    lanes: Int,
    dataWidth: Int
) extends Module {
  private def beatType = new BatchedCmuxExternalOutputBeat(
    tagWidth,
    lanes,
    dataWidth
  )
  val io = IO(new Bundle {
    val start = Input(Bool())
    val outputStart = Output(Bool())
    val enq = Flipped(Decoupled(beatType))
    val deq = Decoupled(beatType)
  })

  io.outputStart := RegNext(io.start, false.B)

  val payload = Reg(beatType)
  val valid = RegInit(false.B)
  io.enq.ready := !valid || io.deq.ready
  io.deq.valid := valid
  io.deq.bits := payload

  when(io.enq.ready) {
    valid := io.enq.valid
    when(io.enq.valid) {
      payload := io.enq.bits
    }
  }
}

/** Registered External Product to inverse-transform SLR boundary.
  *
  * This deliberately uses two explicit aggregate registers instead of a
  * generic two-entry Queue. At Set-II width Vivado otherwise implements the
  * Queue payload as thousands of distributed-RAM bits, leaving the first
  * entry's write input directly on the UltraRAM output path.
  */
private[fpt] final class BatchedCmuxInverseBoundary(
    tagWidth: Int,
    lanes: Int,
    dataWidth: Int
) extends Module {
  private def beatType = new BatchedCmuxInverseBeat(
    tagWidth,
    lanes,
    dataWidth
  )
  val io = IO(new Bundle {
    val start = Input(Bool())
    val outputStart = Output(Bool())
    val enq = Flipped(Decoupled(beatType))
    val deq = Decoupled(beatType)
  })

  // External Product asserts start one cycle before its first valid beat.
  // Delaying both start and data by one cycle preserves that SGen contract.
  io.outputStart := RegNext(io.start, false.B)

  val head = Reg(beatType)
  val tail = Reg(beatType)
  val count = RegInit(0.U(2.W))
  // The inverse SGen has a fixed, non-backpressurable schedule after its
  // start lead. Two credits cover that lead, so exposing FIFO fullness to
  // External Product only creates a false cross-SLR ready path. Keep the
  // source running and assert the fixed-latency contract explicitly.
  io.enq.ready := true.B
  io.deq.valid := count =/= 0.U
  io.deq.bits := head

  val enqFire = io.enq.valid && io.enq.ready
  val deqFire = io.deq.valid && io.deq.ready
  val enqHasSpace = count =/= 2.U || deqFire
  when(io.enq.valid) {
    assert(enqHasSpace, "inverse SGen boundary overflow")
  }
  val enqStore = enqFire && enqHasSpace
  when(deqFire && count === 2.U) {
    head := tail
  }
  when(enqStore) {
    when(deqFire) {
      when(count === 1.U) {
        head := io.enq.bits
      }.elsewhen(count === 2.U) {
        tail := io.enq.bits
      }
    }.otherwise {
      when(count === 0.U) {
        head := io.enq.bits
      }.otherwise {
        tail := io.enq.bits
      }
    }
  }
  when(enqStore =/= deqFire) {
    count := Mux(enqStore, count + 1.U, count - 1.U)
  }
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
    val keyReadRequestValid = Output(Bool())
    val keyReadRequestReady = Input(Bool())
    val keyReadRequestContext = Output(UInt(contextWidth.W))
    val keyReadRequestRow = Output(UInt(rowWidth.W))
    val keyReadRequestBeat = Output(
      UInt(TransformUtil.counterWidth(externalConfig.inputFrameBeats).W)
    )
    val bootstrappingKeyValid = Input(Bool())
    val bootstrappingKeyReady = Output(Bool())

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
      case BatchedCoefficientStorage.PrecomputedWindowedReplicatedBanks =>
        Module(
          new PrecomputedWindowedBatchedCmuxCoefficientStore(
            coefficientConfig,
            config.batchContexts,
            coefficientPreprocessGuardBits =
              config.coefficientPreprocessGuardBits
          )
        )
      case BatchedCoefficientStorage.PrecomputedWindowedBufferedSingleBanks =>
        Module(
          new PrecomputedWindowedBatchedCmuxCoefficientStore(
            coefficientConfig,
            config.batchContexts,
            bufferedSingleAccumulator = true,
            coefficientPreprocessGuardBits =
              config.coefficientPreprocessGuardBits
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
      contextWidth,
      serializeComponents = config.serializeInverseComponents,
      useSynchronousMemory = config.useSynchronousExternalProductMemory,
      useRotatingThreeBankMemory =
        config.useRotatingThreeBankExternalProductMemory
    )
  )

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
  // A busy context cannot be commanded again until its inverse update has
  // completed, so at most batchContexts command tags can be live. The tag
  // FIFO has two additional slots and therefore cannot fill under the legal
  // command protocol. Keeping its combinational ready out of command
  // acceptance avoids feeding the dequeue pointer back into the coefficient
  // prefetch address path. Register the enqueue as well: the transform has
  // ample latency before its first output, while the extra boundary keeps the
  // command scheduler's state decode off the distributed-RAM write controls.
  coefficients.io.commandValid := io.commandValid
  coefficients.io.commandContext := io.commandContext
  coefficients.io.exponent := io.exponent
  io.commandReady := coefficients.io.commandReady
  val commandFire = io.commandValid && io.commandReady
  val forwardTagEnqueueValid = RegNext(commandFire, false.B)
  val forwardTagEnqueueContext = RegEnable(
    io.commandContext,
    0.U(contextWidth.W),
    commandFire
  )
  forwardTags.io.enq.valid := forwardTagEnqueueValid
  forwardTags.io.enq.bits := forwardTagEnqueueContext
  when(forwardTagEnqueueValid) {
    assert(forwardTags.io.enq.ready, "forward tag queue overflow")
  }

  if (config.registerForwardSlrInput) {
    val forwardInputBoundary = Module(
      new BatchedCmuxForwardInputBoundary(
        coefficientConfig.forwardLanes,
        coefficientConfig.forwardFormat.width
      )
    )
    forwardInputBoundary.io.sourceStart := coefficients.io.transformStart
    forwardInputBoundary.io.sourceValid := coefficients.io.pairValid
    forwardInputBoundary.io.sourceLow := coefficients.io.coefficientLow
    forwardInputBoundary.io.sourceHigh := coefficients.io.coefficientHigh
    coefficients.io.pairReady := forwardInputBoundary.io.sourceReady

    forward.io.start := forwardInputBoundary.io.destinationStart
    forward.io.pairValid := forwardInputBoundary.io.destinationValid
    forward.io.coefficientLow := forwardInputBoundary.io.destinationLow
    forward.io.coefficientHigh := forwardInputBoundary.io.destinationHigh
    forwardInputBoundary.io.destinationReady := forward.io.pairReady
  } else {
    forward.io.start := coefficients.io.transformStart
    forward.io.pairValid := coefficients.io.pairValid
    forward.io.coefficientLow := coefficients.io.coefficientLow
    forward.io.coefficientHigh := coefficients.io.coefficientHigh
    coefficients.io.pairReady := forward.io.pairReady
  }
  forward.io.twist := io.forwardTwist
  forward.io.fftTwiddle := 0.U.asTypeOf(forward.io.fftTwiddle)
  io.forwardTwistIndex := forward.io.twistIndex

  val forwardTransactionBeat = RegInit(
    0.U(forwardTransactionBeatWidth.W)
  )
  val forwardFirst = forwardTransactionBeat === 0.U
  val forwardLast = forwardTransactionBeat ===
    (forwardTransactionBeats - 1).U
  external.io.bootstrappingKey := io.bootstrappingKey
  io.keyReadRequestValid := false.B
  io.keyReadRequestContext := 0.U
  io.keyReadRequestRow := 0.U
  io.keyReadRequestBeat := 0.U
  io.bootstrappingKeyReady := false.B

  if (!config.decoupledBootstrappingKey) {
    external.io.inputValid := forward.io.outputValid &&
      forwardTags.io.deq.valid
    external.io.inputFirst := external.io.inputValid && forwardFirst
    external.io.inputTag := forwardTags.io.deq.bits
    external.io.decomposition := forward.io.output
    forward.io.outputReady := external.io.inputReady &&
      forwardTags.io.deq.valid
    val forwardOutputFire = external.io.inputValid && external.io.inputReady
    forwardTags.io.deq.ready := forwardOutputFire && forwardLast
    when(forwardOutputFire) {
      when(forwardLast) {
        forwardTransactionBeat := 0.U
      }.otherwise {
        forwardTransactionBeat := forwardTransactionBeat + 1.U
      }
    }
    io.keyContext := Mux(
      forwardTags.io.deq.valid,
      forwardTags.io.deq.bits,
      0.U
    )
  } else {
    val requestRow = (
      forwardTransactionBeat / externalConfig.inputFrameBeats.U
    )(rowWidth - 1, 0)
    val requestBeat = (
      forwardTransactionBeat % externalConfig.inputFrameBeats.U
    )(TransformUtil.counterWidth(externalConfig.inputFrameBeats) - 1, 0)

    // Retain enough forward outputs to cover a registered wrapper request,
    // the key buffer's synchronous read, and the registered External Product
    // bank-reuse guard without a combinational ready bypass. Physical builds
    // may trade middle entries between SRL LUTs and ordinary register slices.
    val pendingRequestType = new BatchedCmuxPendingKeyRequest(
      contextWidth,
      rowWidth,
      TransformUtil.counterWidth(externalConfig.inputFrameBeats),
      externalConfig.inputLanes,
      externalConfig.spectrum.width
    )
    val pendingRequests = Module(
      new BatchedCmuxPendingKeyRequestQueue(
        contextWidth,
        rowWidth,
        TransformUtil.counterWidth(externalConfig.inputFrameBeats),
        externalConfig.inputLanes,
        externalConfig.spectrum.width,
        config.pendingKeyRequestEntries
      )
    )

    io.keyReadRequestValid := forward.io.outputValid &&
      forwardTags.io.deq.valid && pendingRequests.io.enq.ready
    io.keyReadRequestContext := Mux(
      forwardTags.io.deq.valid,
      forwardTags.io.deq.bits,
      0.U
    )
    io.keyReadRequestRow := requestRow
    io.keyReadRequestBeat := requestBeat
    val requestFire = io.keyReadRequestValid && io.keyReadRequestReady
    forward.io.outputReady := forwardTags.io.deq.valid &&
      pendingRequests.io.enq.ready && io.keyReadRequestReady
    forwardTags.io.deq.ready := requestFire && forwardLast

    val request = Wire(pendingRequestType)
    request.first := forwardFirst
    request.tag := forwardTags.io.deq.bits
    request.row := requestRow
    request.beat := requestBeat
    request.decomposition := forward.io.output
    pendingRequests.io.enq.valid := requestFire
    pendingRequests.io.enq.bits := request
    when(requestFire) {
      when(forwardLast) {
        forwardTransactionBeat := 0.U
      }.otherwise {
        forwardTransactionBeat := forwardTransactionBeat + 1.U
      }
    }

    val pendingHead = pendingRequests.io.deq.bits
    external.io.inputValid := pendingRequests.io.deq.valid &&
      io.bootstrappingKeyValid
    external.io.inputFirst := pendingRequests.io.deq.valid && pendingHead.first
    external.io.inputTag := pendingHead.tag
    external.io.decomposition := pendingHead.decomposition
    io.bootstrappingKeyReady := pendingRequests.io.deq.valid &&
      external.io.inputReady
    pendingRequests.io.deq.ready := io.bootstrappingKeyValid &&
      external.io.inputReady
    io.keyContext := pendingHead.tag

    val responseFire = pendingRequests.io.deq.valid &&
      pendingRequests.io.deq.ready
    when(responseFire) {
      assert(
        external.io.keyRow === pendingHead.row,
        "bootstrapping-key response row does not match External Product"
      )
      for (lane <- 0 until externalConfig.inputLanes) {
        val expectedPoint = pendingHead.beat *
          externalConfig.inputLanes.U + lane.U
        assert(
          external.io.pointIndex(lane) === expectedPoint,
          "bootstrapping-key response point does not match External Product"
        )
      }
    }
  }
  io.keyRow := external.io.keyRow
  io.keyPoint := external.io.pointIndex
  io.keyValid := external.io.inputValid
  io.keyFirst := external.io.inputFirst

  if (config.serializeInverseComponents) {
    val inverse = Module(
      new SGenInverseTangentBackend(
        base.inverseTransform,
        base.inverseNormalizeShift,
        base.inverseSGen.get
      )
    )
    val inverseTags = Module(
      new Queue(UInt(contextWidth.W), config.batchContexts + 2)
    )

    // First register both External Product components before their serialized
    // selection. This keeps the memory clock-to-out/bank-select path separate
    // from the component mux.
    val externalOutputPipeline = Module(
      new BatchedCmuxExternalOutputPipeline(
        contextWidth,
        externalConfig.outputLanes,
        externalConfig.accumulator.width
      )
    )
    externalOutputPipeline.io.start := external.io.outputStart
    externalOutputPipeline.io.enq.valid := external.io.outputValid
    external.io.outputReady := externalOutputPipeline.io.enq.ready
    externalOutputPipeline.io.enq.bits.first := external.io.outputFirst
    externalOutputPipeline.io.enq.bits.tag := external.io.outputTag
    externalOutputPipeline.io.enq.bits.input := external.io.serializedOutput

    // Register the selected stream again before its SLR1 -> SLR2 crossing.
    // The start token and data are delayed together, retaining SGen's
    // one-cycle input-lead contract while removing the External Product state
    // machine from the inverse core's reset/control cone.
    val inverseBoundary = Module(
      new BatchedCmuxInverseBoundary(
        contextWidth,
        externalConfig.outputLanes,
        externalConfig.accumulator.width
      )
    )
    inverseBoundary.io.start := externalOutputPipeline.io.outputStart
    inverseBoundary.io.enq.valid := externalOutputPipeline.io.deq.valid
    externalOutputPipeline.io.deq.ready := inverseBoundary.io.enq.ready
    inverseBoundary.io.enq.bits.first :=
      externalOutputPipeline.io.deq.bits.first
    inverseBoundary.io.enq.bits.tag :=
      externalOutputPipeline.io.deq.bits.tag
    inverseBoundary.io.enq.bits.input := externalOutputPipeline.io.deq.bits.input
    inverse.io.start := inverseBoundary.io.outputStart
    inverse.io.inputValid := inverseBoundary.io.deq.valid &&
      inverse.io.inputReady
    inverse.io.input := inverseBoundary.io.deq.bits.input
    inverseBoundary.io.deq.ready := inverse.io.inputReady
    inverse.io.fftTwiddle := 0.U.asTypeOf(inverse.io.fftTwiddle)
    inverse.io.untwist := io.inverseUntwist
    io.inverseUntwistIndex := inverse.io.untwistIndex

    inverseTags.io.enq.valid := inverseBoundary.io.deq.valid &&
      inverseBoundary.io.deq.ready && inverseBoundary.io.deq.bits.first
    inverseTags.io.enq.bits := inverseBoundary.io.deq.bits.tag
    when(inverseTags.io.enq.valid) {
      assert(inverseTags.io.enq.ready, "inverse tag queue overflow")
    }

    // The tag is present for at least one complete inverse component before
    // ComponentJoin emits an update. Capture it beside the coefficient
    // memories so the tag FIFO's distributed-RAM dequeue pointer cannot feed
    // the first replicated-BRAM address directly. Serialized component zero
    // also leaves a complete frame in which to capture the next tag after a
    // dequeue.
    val registeredInverseTag = RegEnable(
      inverseTags.io.deq.bits,
      0.U(contextWidth.W),
      inverseTags.io.deq.valid
    )
    val registeredInverseTagValid = RegNext(
      inverseTags.io.deq.valid,
      false.B
    )

    val componentJoin = Module(
      new InverseComponentJoin(
        base.inverseTransform.frameBeats,
        base.inverseTransform.lanes,
        base.inverseTransform.dataWidth
      )
    )
    if (config.registerInverseSlrOutput) {
      val inverseOutputMiddleRelay = Module(
        new BatchedCmuxInverseOutputBoundary(
          base.inverseTransform.lanes,
          base.inverseTransform.dataWidth
        )
      )
      val inverseOutputDestinationRelay = Module(
        new BatchedCmuxInverseOutputBoundary(
          base.inverseTransform.lanes,
          base.inverseTransform.dataWidth
        )
      )
      inverseOutputMiddleRelay.io.sourceValid := inverse.io.outputValid
      inverseOutputMiddleRelay.io.sourceLow := inverse.io.coefficientLow
      inverseOutputMiddleRelay.io.sourceHigh := inverse.io.coefficientHigh
      inverse.io.outputReady := inverseOutputMiddleRelay.io.sourceReady

      inverseOutputDestinationRelay.io.sourceValid :=
        inverseOutputMiddleRelay.io.destinationValid
      inverseOutputDestinationRelay.io.sourceLow :=
        inverseOutputMiddleRelay.io.destinationLow
      inverseOutputDestinationRelay.io.sourceHigh :=
        inverseOutputMiddleRelay.io.destinationHigh
      inverseOutputMiddleRelay.io.destinationReady :=
        inverseOutputDestinationRelay.io.sourceReady

      componentJoin.io.inputValid :=
        inverseOutputDestinationRelay.io.destinationValid
      componentJoin.io.inputLow := inverseOutputDestinationRelay.io.destinationLow
      componentJoin.io.inputHigh := inverseOutputDestinationRelay.io.destinationHigh
      inverseOutputDestinationRelay.io.destinationReady :=
        componentJoin.io.inputReady
    } else {
      componentJoin.io.inputValid := inverse.io.outputValid
      inverse.io.outputReady := componentJoin.io.inputReady
      componentJoin.io.inputLow := inverse.io.coefficientLow
      componentJoin.io.inputHigh := inverse.io.coefficientHigh
    }
    componentJoin.io.outputReady := coefficients.io.updateReady &&
      registeredInverseTagValid

    coefficients.io.updateValid := componentJoin.io.outputValid &&
      registeredInverseTagValid
    coefficients.io.updateFirst := coefficients.io.updateValid &&
      componentJoin.io.outputFirst
    coefficients.io.updateContext := registeredInverseTag
    coefficients.io.updateLow := componentJoin.io.outputLow
    coefficients.io.updateHigh := componentJoin.io.outputHigh
    when(componentJoin.io.outputValid) {
      assert(registeredInverseTagValid, "missing serialized inverse tag")
    }
    inverseTags.io.deq.ready := componentJoin.io.outputValid &&
      componentJoin.io.outputReady && componentJoin.io.outputLast
  } else {
    val inverses = Seq.fill(coefficientConfig.components) {
      Module(
        new SGenInverseTangentBackend(
          base.inverseTransform,
          base.inverseNormalizeShift,
          base.inverseSGen.get
        )
      )
    }

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

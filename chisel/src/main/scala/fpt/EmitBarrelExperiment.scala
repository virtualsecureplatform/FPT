package fpt

import circt.stage.ChiselStage

import java.nio.file.Path

/** U280 windowed-barrel physical accelerator emitter.
  *
  * Identical to `EmitPaperBufferedBlindRotateAccelerator` except the CMUX
  * coefficient frontend precomputes the paper barrel's requested stream-width
  * windows from replicated banks instead of materializing its full-width mux
  * or using the bitwise transposed working sets. One 128-lane rotator handles
  * the low and high halves on alternating preprocessing cycles and stores all
  * gadget levels together. The prior workset emits concurrently, preserving
  * II=16 without the bitwise frontend's unroutable full-polynomial transpose.
  */
object EmitPaperBufferedBarrelBlindRotateAccelerator extends App {
  require(
    args.length >= 3 && args.length <= 4,
    "usage: EmitPaperBufferedBarrelBlindRotateAccelerator " +
      "OUTPUT_DIR SGEN_FORWARD_V SGEN_INVERSE_V [DOMAIN_DIMENSION]"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val domainDimension =
    if (args.length >= 4) args(3).toInt
    else PaperSetII.blindRotateDomainDimension
  require(domainDimension >= 1, "DOMAIN_DIMENSION must be positive")

  val baseEngine = PaperSetII.cmuxEngine(
    forwardPath.toString,
    inversePath.toString,
    includeVerilogSource = false
  )
  // Stream-width windowed rotation: the full-width barrel is ~283K LUTs of
  // single-block muxing whose 32K-bit chain also cannot straddle an SLR
  // boundary. Precomputing both gadget levels while alternating polynomial
  // halves lets one inverse-bank-width span aligner serve every emitted pair.
  val engine = baseEngine.copy(
    coefficient = baseEngine.coefficient.copy(windowedRotator = true),
    // The combinational Gauss correction path is over 5 ns when driven by
    // the real key-buffer BRAM. Four exact products avoid that fabric-heavy
    // correction network, use eight DSPs instead of the mapped Gauss path's
    // ten, and pipeline the External Product at its natural synchronous-
    // memory boundary.
    externalProduct = baseEngine.externalProduct.copy(
      multiplier = ExternalProductMultiplier.ExactPipelinedSchoolbookDsp
    )
  )
  val blindRotate = BatchedBlindRotateEngineConfig(
    BatchedCmuxEngineConfig(
      engine,
      // 16, not barrelBatchContexts = 14: the 864-bit key port needs 256
      // beats per bootstrapping-key coefficient, so the per-coefficient
      // reuse window must be at least 256 cycles. A 14-context window is
      // 14 x 16 = 224 cycles and outruns the loader, which backpressures
      // the forward SGen core after ~forward-latency/32 CMUX steps.
      batchContexts = PaperSetII.bitwiseBatchContexts,
      coefficientStorage =
        BatchedCoefficientStorage.PrecomputedWindowedReplicatedBanks,
      serializeInverseComponents = true,
      useSynchronousExternalProductMemory = true,
      decoupledBootstrappingKey = true,
      // The crossing register, one local stream slice, and the two-entry
      // output boundary provide four credits for the registered key request,
      // synchronous key read, and External Product bank-reuse guard. Avoid
      // spending one SRLC32E per 7,684-bit payload merely for deeper slack.
      pendingKeyRequestEntries = 1
    ),
    domainDimension
  )
  val config = BufferedBlindRotateConfig(blindRotate, PaperSetII.keyLoadLanes)

  ChiselStage.emitSystemVerilogFile(
    new BufferedBlindRotateAccelerator(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  val systemVerilog = outputDirectory.resolve(
    "BufferedBlindRotateAccelerator.sv"
  )
  SynthesisEmitter.removeInlineFileList(systemVerilog)
  SynthesisEmitter.addBlockRamStyle(
    systemVerilog,
    config.keyMemoryModuleName
  )
  // Duplicate the packed transpose so low and high halves can be read while
  // the next workset is written. Each 48 x 2,560-bit copy maps to 36 RAMB36s,
  // replacing hundreds of shallow lane-local distributed memories.
  SynthesisEmitter.addBlockRamStyle(
    systemVerilog,
    "digitMemories_48x2560"
  )
  // Each 4 x 15,360-bit External Product ping-pong bank otherwise consumes
  // 8,780 distributed-memory LUTs. Use the U280's otherwise-idle UltraRAM for
  // the padded twin, but leave the other shallow bank distributed: mapping it
  // to 213 RAMB36s leaves only seven free BRAM sites in the middle SLR and
  // stretches the accumulator-bank BRAM-to-BRAM update paths across the die.
  SynthesisEmitter.useReadClockForMemoryWrites(
    systemVerilog,
    "accumulatorMemories_0_4x15361"
  )
  SynthesisEmitter.addUltraRamStyle(
    systemVerilog,
    "accumulatorMemories_0_4x15361"
  )
}

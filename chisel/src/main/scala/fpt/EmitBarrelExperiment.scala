package fpt

import circt.stage.ChiselStage

import java.nio.file.Path

/** Experimental barrel-frontend physical accelerator emitter.
  *
  * Identical to `EmitPaperBufferedBlindRotateAccelerator` except the CMUX
  * coefficient frontend uses the paper's full-width negacyclic barrel
  * (`ReplicatedBanks`, 14 contexts) instead of the bitwise transposed
  * working sets. The bitwise frontend's transpose wiring is unroutable on
  * the U280 at full Set-II parallelism (global congestion level 7), so this
  * emitter exists to measure the road-tested paper configuration.
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
  // boundary, so no placement routes it on the U280. The windowed store
  // reads only the two ring blocks each beat consumes.
  val engine = baseEngine.copy(
    coefficient = baseEngine.coefficient.copy(windowedRotator = true)
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
      coefficientStorage = BatchedCoefficientStorage.ReplicatedBanks,
      serializeInverseComponents = true,
      useSynchronousExternalProductMemory = true,
      decoupledBootstrappingKey = true
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
}

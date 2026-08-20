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
private[fpt] object PaperU280BufferedBarrelConfig {
  def apply(
      forwardPath: String,
      inversePath: String,
      domainDimension: Int
  ): BufferedBlindRotateConfig = {
    require(domainDimension >= 1, "DOMAIN_DIMENSION must be positive")

    val baseEngine = PaperSetII.cmuxEngine(
      forwardPath,
      inversePath,
      includeVerilogSource = false
    )
    val engine = baseEngine.copy(
      coefficient = baseEngine.coefficient.copy(windowedRotator = true),
      externalProduct = baseEngine.externalProduct.copy(
        multiplier = ExternalProductMultiplier.ExactPipelinedSchoolbookDsp
      )
    )
    val blindRotate = BatchedBlindRotateEngineConfig(
      BatchedCmuxEngineConfig(
        engine,
        batchContexts = PaperSetII.bitwiseBatchContexts,
        coefficientStorage =
          BatchedCoefficientStorage.PrecomputedWindowedReplicatedBanks,
        serializeInverseComponents = true,
        useSynchronousExternalProductMemory = true,
        decoupledBootstrappingKey = true,
        pendingKeyRequestEntries = 1,
        registerForwardSlrInput = true
      ),
      domainDimension
    )
    BufferedBlindRotateConfig(blindRotate, PaperSetII.keyLoadLanes)
  }
}

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

  val config = PaperU280BufferedBarrelConfig(
    forwardPath.toString,
    inversePath.toString,
    domainDimension
  )

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

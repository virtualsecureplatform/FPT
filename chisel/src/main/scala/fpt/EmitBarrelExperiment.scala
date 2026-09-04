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
  * the paper's II=16 without the bitwise frontend's unroutable full-polynomial
  * transpose.
  */
private[fpt] object PaperU280BufferedBarrelConfig {
  private def coefficientStorage: BatchedCoefficientStorage =
    sys.env.getOrElse("FPT_ACCUMULATOR_ARCH", "buffered_single") match {
      case "buffered_single" =>
        BatchedCoefficientStorage.PrecomputedWindowedBufferedSingleBanks
      case "replicated" =>
        BatchedCoefficientStorage.PrecomputedWindowedReplicatedBanks
      case value =>
        throw new IllegalArgumentException(
          s"FPT_ACCUMULATOR_ARCH must be buffered_single or replicated, got $value"
        )
    }

  private def coefficientPreprocessGuardBits: Option[Int] =
    sys.env.getOrElse("FPT_COEFFICIENT_PREPROCESS_GUARD_BITS", "4") match {
      case "exact" => None
      case value => Some(value.toInt)
    }

  private def externalProductMultiplier: ExternalProductMultiplier =
    sys.env.getOrElse("FPT_EXTERNAL_PRODUCT_MULTIPLIER", "schoolbook") match {
      case "schoolbook" =>
        ExternalProductMultiplier.ExactPipelinedSchoolbookDsp
      case "quantized_gauss" =>
        ExternalProductMultiplier.ExactPipelinedQuantizedGaussDsp
      case value =>
        throw new IllegalArgumentException(
          s"FPT_EXTERNAL_PRODUCT_MULTIPLIER must be schoolbook or quantized_gauss, got $value"
        )
    }

  def apply(
      forwardPath: String,
      inversePath: String,
      domainDimension: Int,
      includeVerilogSource: Boolean = false
  ): BufferedBlindRotateConfig = {
    require(domainDimension >= 1, "DOMAIN_DIMENSION must be positive")

    val baseEngine = PaperSetII.cmuxEngine(
      forwardPath,
      inversePath,
      includeVerilogSource = includeVerilogSource
    )
    val engine = baseEngine.copy(
      coefficient = baseEngine.coefficient.copy(windowedRotator = true),
      externalProduct = baseEngine.externalProduct.copy(
        multiplier = externalProductMultiplier
      )
    )
    val blindRotate = BatchedBlindRotateEngineConfig(
      BatchedCmuxEngineConfig(
        engine,
        batchContexts = PaperSetII.bitwiseBatchContexts,
        coefficientStorage = coefficientStorage,
        serializeInverseComponents = true,
        useSynchronousExternalProductMemory = true,
        useRotatingThreeBankExternalProductMemory = true,
        decoupledBootstrappingKey = true,
        pendingKeyRequestEntries = 1,
        registerForwardSlrInput = true,
        registerInverseSlrOutput = true,
        coefficientPreprocessGuardBits = coefficientPreprocessGuardBits
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
  // The generated width tracks the selected transform parallelism.
  SynthesisEmitter.addBlockRamStyleByPrefix(systemVerilog, "digitMemory_")
  SynthesisEmitter.addDistributedRamStyleByPrefix(
    systemVerilog,
    "firstComponentMemory_"
  )
  // Row-to-row sums remain in the feedback pipeline. Only the four final
  // 15,360-bit words occupy physical memory.
  SynthesisEmitter.useReadClockForMemoryWritesByPrefix(
    systemVerilog,
    "forwardMemories_"
  )
  SynthesisEmitter.addUltraRamStyleByPrefix(systemVerilog, "forwardMemories_")
  if (
    config.blindRotate.cmux.coefficientStorage ==
      BatchedCoefficientStorage.PrecomputedWindowedBufferedSingleBanks
  ) {
    SynthesisEmitter.addDistributedRamStyleByPrefix(systemVerilog, "ram_8x")
  }
}

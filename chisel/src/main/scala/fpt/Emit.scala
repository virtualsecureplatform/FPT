package fpt

import circt.stage.ChiselStage

import java.nio.file.{Files, Path}

private object SynthesisEmitter {
  val firtoolOptions: Array[String] = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "--lowering-options=disallowLocalVariables"
  )

  def outputDirectory(argument: String): String =
    Path.of(argument).toAbsolutePath.normalize.toString

  /** CIRCT concatenates the inline BlackBox source and its auxiliary file-list
    * artifact when `emitSystemVerilogFile` is used. The source is valid and
    * intentionally retained; the final plain-text filename is not Verilog and
    * must be removed from the self-contained synthesis file.
    */
  def removeInlineFileList(systemVerilog: Path): Unit = {
    val marker =
      "\n// ----- 8< ----- FILE \"firrtl_black_box_resource_files.f\" " +
        "----- 8< -----\n"
    val source = Files.readString(systemVerilog)
    val markerIndex = source.indexOf(marker)
    if (markerIndex >= 0) {
      Files.writeString(systemVerilog, source.substring(0, markerIndex) + "\n")
    }
  }
}

object PaperSetII {
  val blindRotateDomainDimension = 630
  val barrelBatchContexts = 14
  val bitwiseBatchContexts = 16

  val coefficient = CmuxCoefficientConfig(
    polynomialSize = 1024,
    forwardLanes = 128,
    inverseLanes = 64,
    components = 2,
    levels = 2,
    baseBits = 10,
    torusWidth = 32,
    forwardFormat = FixedFormat(18, 12),
    inverseFormat = FixedFormat(27, 3)
  )
  val forward = TransformConfig(
    points = 512,
    lanes = 128,
    dataWidth = 30,
    twiddleWidth = 26,
    twiddleFractionalBits = 24
  )
  val inverse = TransformConfig(
    points = 512,
    lanes = 64,
    dataWidth = 30,
    twiddleWidth = 26,
    twiddleFractionalBits = 24
  )
  val external = ExternalProductConfig(
    points = 512,
    inputLanes = 128,
    outputLanes = 64,
    rows = 4,
    outputComponents = 2,
    spectrum = FixedFormat(18, 12),
    bootstrappingKey = FixedFormat(8, 19),
    accumulator = FixedFormat(27, 3),
    multiplier = ExternalProductMultiplier.ExactGaussDsp
  )
  def cmuxEngine(
      forwardPath: String,
      inversePath: String,
      includeVerilogSource: Boolean,
      bitwiseBitsPerCycle: Option[Int] = None
  ): CmuxEngineConfig =
    CmuxEngineConfig(
      coefficient,
      forward,
      inverse,
      external,
      inverseNormalizeShift = 0,
      forwardSGen = Some(
        SGenBackendConfig(
          "FptSGenForward",
          forwardPath,
          includeVerilogSource = includeVerilogSource,
          integratedTangent = true
        )
      ),
      inverseSGen = Some(
        SGenBackendConfig(
          "FptSGenInverse",
          inversePath,
          includeVerilogSource = includeVerilogSource,
          integratedTangent = true
        )
      ),
      bitwiseBitsPerCycle = bitwiseBitsPerCycle
    )
}

object EmitPaperExternalProduct extends App {
  require(args.length == 1, "usage: EmitPaperExternalProduct OUTPUT_DIR")

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  ChiselStage.emitSystemVerilogFile(
    new DoubleBufferedExternalProductAccumulator(
      PaperSetII.external,
      tagWidth = 4,
      useSynchronousMemory = true
    ),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.removeInlineFileList(
    outputDirectory.resolve("DoubleBufferedExternalProductAccumulator.sv")
  )
}

object EmitPaperCmux extends App {
  require(
    args.length == 3,
    "usage: EmitPaperCmux OUTPUT_DIR SGEN_FORWARD_V SGEN_INVERSE_V"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val config = PaperSetII.cmuxEngine(
    forwardPath.toString,
    inversePath.toString,
    includeVerilogSource = false
  )

  ChiselStage.emitSystemVerilogFile(
    new CmuxEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.removeInlineFileList(
    outputDirectory.resolve("CmuxEngine.sv")
  )
}

object EmitPaperBitwiseCmux extends App {
  require(
    args.length == 3,
    "usage: EmitPaperBitwiseCmux OUTPUT_DIR SGEN_FORWARD_V SGEN_INVERSE_V"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val config = PaperSetII.cmuxEngine(
    forwardPath.toString,
    inversePath.toString,
    includeVerilogSource = false,
    bitwiseBitsPerCycle = Some(2)
  )

  ChiselStage.emitSystemVerilogFile(
    new CmuxEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.removeInlineFileList(
    outputDirectory.resolve("CmuxEngine.sv")
  )
}

object EmitPaperBatchedCmux extends App {
  require(
    args.length == 3,
    "usage: EmitPaperBatchedCmux OUTPUT_DIR SGEN_FORWARD_V SGEN_INVERSE_V"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val engine = PaperSetII.cmuxEngine(
    forwardPath.toString,
    inversePath.toString,
    includeVerilogSource = false
  )
  val config = BatchedCmuxEngineConfig(
    engine,
    batchContexts = 14,
    serializeInverseComponents = true,
    useSynchronousExternalProductMemory = true
  )

  ChiselStage.emitSystemVerilogFile(
    new BatchedCmuxEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.removeInlineFileList(
    outputDirectory.resolve("BatchedCmuxEngine.sv")
  )
}

object EmitPaperBankedBatchedCmux extends App {
  require(
    args.length == 3,
    "usage: EmitPaperBankedBatchedCmux OUTPUT_DIR SGEN_FORWARD_V SGEN_INVERSE_V"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val engine = PaperSetII.cmuxEngine(
    forwardPath.toString,
    inversePath.toString,
    includeVerilogSource = false
  )
  val config = BatchedCmuxEngineConfig(
    engine,
    batchContexts = PaperSetII.barrelBatchContexts,
    coefficientStorage = BatchedCoefficientStorage.ReplicatedBanks,
    serializeInverseComponents = true,
    useSynchronousExternalProductMemory = true
  )

  ChiselStage.emitSystemVerilogFile(
    new BatchedCmuxEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.removeInlineFileList(
    outputDirectory.resolve("BatchedCmuxEngine.sv")
  )
}

object EmitPaperBitwiseBatchedCmux extends App {
  require(
    args.length == 3,
    "usage: EmitPaperBitwiseBatchedCmux OUTPUT_DIR SGEN_FORWARD_V SGEN_INVERSE_V"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val engine = PaperSetII.cmuxEngine(
    forwardPath.toString,
    inversePath.toString,
    includeVerilogSource = false,
    bitwiseBitsPerCycle = Some(2)
  )
  val config = BatchedCmuxEngineConfig(
    engine,
    batchContexts = PaperSetII.bitwiseBatchContexts,
    coefficientStorage = BatchedCoefficientStorage.BitwiseReplicatedBanks,
    serializeInverseComponents = true,
    useSynchronousExternalProductMemory = true
  )

  ChiselStage.emitSystemVerilogFile(
    new BatchedCmuxEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.removeInlineFileList(
    outputDirectory.resolve("BatchedCmuxEngine.sv")
  )
}

object EmitPaperBatchedBlindRotate extends App {
  require(
    args.length == 3 || args.length == 4,
    "usage: EmitPaperBatchedBlindRotate OUTPUT_DIR SGEN_FORWARD_V " +
      "SGEN_INVERSE_V [DOMAIN_DIMENSION]"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val domainDimension =
    if (args.length == 4) args(3).toInt
    else PaperSetII.blindRotateDomainDimension
  require(domainDimension >= 1, "DOMAIN_DIMENSION must be positive")
  val engine = PaperSetII.cmuxEngine(
    forwardPath.toString,
    inversePath.toString,
    includeVerilogSource = false
  )
  val config = BatchedBlindRotateEngineConfig(
    BatchedCmuxEngineConfig(
      engine,
      batchContexts = PaperSetII.barrelBatchContexts,
      coefficientStorage = BatchedCoefficientStorage.ReplicatedBanks,
      serializeInverseComponents = true,
      useSynchronousExternalProductMemory = true
    ),
    domainDimension
  )

  ChiselStage.emitSystemVerilogFile(
    new BatchedBlindRotateEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.removeInlineFileList(
    outputDirectory.resolve("BatchedBlindRotateEngine.sv")
  )
}

object EmitPaperBitwiseBatchedBlindRotate extends App {
  require(
    args.length == 3 || args.length == 4,
    "usage: EmitPaperBitwiseBatchedBlindRotate OUTPUT_DIR SGEN_FORWARD_V " +
      "SGEN_INVERSE_V [DOMAIN_DIMENSION]"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val domainDimension =
    if (args.length == 4) args(3).toInt
    else PaperSetII.blindRotateDomainDimension
  require(domainDimension >= 1, "DOMAIN_DIMENSION must be positive")
  val engine = PaperSetII.cmuxEngine(
    forwardPath.toString,
    inversePath.toString,
    includeVerilogSource = false,
    bitwiseBitsPerCycle = Some(2)
  )
  val config = BatchedBlindRotateEngineConfig(
    BatchedCmuxEngineConfig(
      engine,
      batchContexts = PaperSetII.bitwiseBatchContexts,
      coefficientStorage = BatchedCoefficientStorage.BitwiseReplicatedBanks,
      serializeInverseComponents = true,
      useSynchronousExternalProductMemory = true
    ),
    domainDimension
  )

  ChiselStage.emitSystemVerilogFile(
    new BatchedBlindRotateEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.removeInlineFileList(
    outputDirectory.resolve("BatchedBlindRotateEngine.sv")
  )
}

object EmitPaperBitwiseBatchedBlindRotateSampleExtract extends App {
  require(
    args.length == 3 || args.length == 4,
    "usage: EmitPaperBitwiseBatchedBlindRotateSampleExtract OUTPUT_DIR " +
      "SGEN_FORWARD_V SGEN_INVERSE_V [DOMAIN_DIMENSION]"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val domainDimension =
    if (args.length == 4) args(3).toInt
    else PaperSetII.blindRotateDomainDimension
  require(domainDimension >= 1, "DOMAIN_DIMENSION must be positive")
  val engine = PaperSetII.cmuxEngine(
    forwardPath.toString,
    inversePath.toString,
    includeVerilogSource = false,
    bitwiseBitsPerCycle = Some(2)
  )
  val config = BatchedBlindRotateEngineConfig(
    BatchedCmuxEngineConfig(
      engine,
      batchContexts = PaperSetII.bitwiseBatchContexts,
      coefficientStorage = BatchedCoefficientStorage.BitwiseReplicatedBanks,
      serializeInverseComponents = true,
      useSynchronousExternalProductMemory = true
    ),
    domainDimension
  )

  ChiselStage.emitSystemVerilogFile(
    new BatchedBlindRotateSampleExtractEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.removeInlineFileList(
    outputDirectory.resolve("BatchedBlindRotateSampleExtractEngine.sv")
  )
}

object EmitPaperAccumulatorBanks extends App {
  require(
    args.length == 1,
    "usage: EmitPaperAccumulatorBanks OUTPUT_DIR"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val config = ReplicatedAccumulatorBanksConfig(
    PaperSetII.coefficient,
    batchContexts = 12
  )

  ChiselStage.emitSystemVerilogFile(
    new ReplicatedAccumulatorBanks(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
}

object EmitPaperBitwiseReorder extends App {
  require(args.length == 1, "usage: EmitPaperBitwiseReorder OUTPUT_DIR")

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  ChiselStage.emitSystemVerilogFile(
    new BitwiseNegacyclicReorder(
      polynomialSize = 1024,
      loadLanes = 64,
      coefficientWidth = 32,
      bitsPerCycle = 2
    ),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
}

object EmitPaperBitwiseDecomposition extends App {
  require(args.length == 1, "usage: EmitPaperBitwiseDecomposition OUTPUT_DIR")

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  ChiselStage.emitSystemVerilogFile(
    new BitwiseCmuxDecompositionFrontend(
      config = PaperSetII.coefficient,
      bitsPerCycle = 2
    ),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
}

object EmitPaperBitwiseForwardFrontend extends App {
  require(args.length == 1, "usage: EmitPaperBitwiseForwardFrontend OUTPUT_DIR")

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  ChiselStage.emitSystemVerilogFile(
    new BitwiseCmuxForwardFrontend(
      config = PaperSetII.coefficient,
      bitsPerCycle = 2
    ),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
}

object EmitPaperYosysBarrelCoefficient extends App {
  require(args.length == 1, "usage: EmitPaperYosysBarrelCoefficient OUTPUT_DIR")

  ChiselStage.emitSystemVerilogFile(
    new CmuxCoefficientStore(PaperSetII.coefficient),
    args = Array(
      "--target-dir",
      SynthesisEmitter.outputDirectory(args(0))
    ),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
}

object EmitPaperYosysBitwiseCoefficient extends App {
  require(args.length == 1, "usage: EmitPaperYosysBitwiseCoefficient OUTPUT_DIR")

  ChiselStage.emitSystemVerilogFile(
    new BitwiseCmuxForwardFrontend(PaperSetII.coefficient, bitsPerCycle = 2),
    args = Array(
      "--target-dir",
      SynthesisEmitter.outputDirectory(args(0))
    ),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
}

object EmitPaperYosysBarrelBatchedCoefficient extends App {
  require(
    args.length == 1,
    "usage: EmitPaperYosysBarrelBatchedCoefficient OUTPUT_DIR"
  )

  ChiselStage.emitSystemVerilogFile(
    new PrefetchedBatchedCmuxCoefficientStore(
      PaperSetII.coefficient,
      batchContexts = 14
    ),
    args = Array(
      "--target-dir",
      SynthesisEmitter.outputDirectory(args(0))
    ),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
}

object EmitPaperYosysBitwiseBatchedCoefficient extends App {
  require(
    args.length == 1,
    "usage: EmitPaperYosysBitwiseBatchedCoefficient OUTPUT_DIR"
  )

  ChiselStage.emitSystemVerilogFile(
    new BitwisePrefetchedBatchedCmuxCoefficientStore(
      PaperSetII.coefficient,
      batchContexts = 15,
      bitsPerCycle = 2
    ),
    args = Array(
      "--target-dir",
      SynthesisEmitter.outputDirectory(args(0))
    ),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
}

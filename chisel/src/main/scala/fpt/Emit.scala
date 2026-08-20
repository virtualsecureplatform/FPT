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

  /** Mark one generated inferred memory with a synthesis RAM style. Chisel 6's CIRCT
    * emitter does not carry the legacy FIRRTL AttributeAnnotation API, so the
    * synthesis-only attribute is inserted into the generated artifact. The
    * surrounding module and declaration checks make emitter changes fail
    * loudly instead of silently tagging the wrong storage.
    */
  def addRamStyle(
      systemVerilog: Path,
      memoryModule: String,
      style: String
  ): Unit = {
    require(Set("block", "distributed", "ultra").contains(style))
    val source = Files.readString(systemVerilog)
    val moduleStart = source.indexOf(s"module $memoryModule(")
    require(moduleStart >= 0, s"missing memory module $memoryModule")
    require(
      source.indexOf(s"module $memoryModule(", moduleStart + 1) < 0,
      s"duplicate memory module $memoryModule"
    )
    val moduleEnd = source.indexOf("\nendmodule", moduleStart)
    require(moduleEnd >= 0, s"unterminated memory module $memoryModule")
    val memoryToken = " Memory["
    val token = source.indexOf(memoryToken, moduleStart)
    require(
      token >= 0 && token < moduleEnd,
      s"missing inferred-memory declaration in $memoryModule"
    )
    val lineStart = source.lastIndexOf('\n', token) + 1
    val lineEnd = source.indexOf('\n', token)
    val declaration = source.substring(lineStart, lineEnd)
    require(
      declaration.startsWith("  reg ") &&
        !declaration.contains("ram_style"),
      s"unexpected inferred-memory declaration: $declaration"
    )
    val tagged = declaration.replace(
      "  reg ",
      s"  (* ram_style = \"$style\" *) reg "
    )
    Files.writeString(
      systemVerilog,
      source.substring(0, lineStart) + tagged + source.substring(lineEnd)
    )
  }

  def addBlockRamStyle(systemVerilog: Path, memoryModule: String): Unit =
    addRamStyle(systemVerilog, memoryModule, "block")

  def addUltraRamStyle(systemVerilog: Path, memoryModule: String): Unit =
    addRamStyle(systemVerilog, memoryModule, "ultra")

  /** Resolve an emitter-generated memory name by its stable logical prefix.
    * Array dimensions vary with the selected transform parallelism.
    */
  def generatedModuleWithPrefix(systemVerilog: Path, prefix: String): String = {
    val source = Files.readString(systemVerilog)
    val modulePattern = "(?m)^module\\s+([A-Za-z_][A-Za-z0-9_]*)\\(".r
    val matches = modulePattern.findAllMatchIn(source).map(_.group(1))
      .filter(_.startsWith(prefix)).toSeq.distinct
    require(
      matches.size == 1,
      s"expected one generated module beginning '$prefix', found ${matches.mkString(", ")}"
    )
    matches.head
  }

  def addBlockRamStyleByPrefix(systemVerilog: Path, prefix: String): Unit =
    addBlockRamStyle(systemVerilog, generatedModuleWithPrefix(systemVerilog, prefix))

  def addUltraRamStyleByPrefix(systemVerilog: Path, prefix: String): Unit =
    addUltraRamStyle(systemVerilog, generatedModuleWithPrefix(systemVerilog, prefix))

  /** CIRCT gives a SyncReadMem's read and write ports distinct clock names
    * even when both connect to the enclosing Chisel clock. UltraRAM inference
    * requires one syntactic clock, so make that equivalence explicit in the
    * generated memory module after checking every affected token.
    */
  def useReadClockForMemoryWrites(
      systemVerilog: Path,
      memoryModule: String
  ): Unit = {
    val source = Files.readString(systemVerilog)
    val moduleStart = source.indexOf(s"module $memoryModule(")
    require(moduleStart >= 0, s"missing memory module $memoryModule")
    val moduleEnd = source.indexOf("\nendmodule", moduleStart)
    require(moduleEnd >= 0, s"unterminated memory module $memoryModule")
    val module = source.substring(moduleStart, moduleEnd)
    val writeClock = "always @(posedge W0_clk)"
    require(
      module.sliding(writeClock.length).count(_ == writeClock) == 1,
      s"expected one write clock in $memoryModule"
    )
    val rewritten = module.replace(writeClock, "always @(posedge R0_clk)")
    Files.writeString(
      systemVerilog,
      source.substring(0, moduleStart) + rewritten + source.substring(moduleEnd)
    )
  }

  def useReadClockForMemoryWritesByPrefix(
      systemVerilog: Path,
      prefix: String
  ): Unit =
    useReadClockForMemoryWrites(
      systemVerilog,
      generatedModuleWithPrefix(systemVerilog, prefix)
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

object EmitPaperBootstrappingKeyBuffer extends App {
  require(
    args.length == 1,
    "usage: EmitPaperBootstrappingKeyBuffer OUTPUT_DIR"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val config = BootstrappingKeyBufferConfig(
    PaperSetII.external,
    PaperSetII.bitwiseBatchContexts,
    PaperSetII.blindRotateDomainDimension,
    PaperSetII.keyLoadLanes
  )
  val systemVerilog = outputDirectory.resolve(
    "BootstrappingKeyPingPongBuffer.sv"
  )
  ChiselStage.emitSystemVerilogFile(
    new BootstrappingKeyPingPongBuffer(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.addBlockRamStyle(systemVerilog, "memory_32x13824")
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

object EmitPaperBufferedBitwiseBatchedBlindRotateSampleExtract extends App {
  require(
    args.length >= 3 && args.length <= 5,
    "usage: EmitPaperBufferedBitwiseBatchedBlindRotateSampleExtract " +
      "OUTPUT_DIR SGEN_FORWARD_V SGEN_INVERSE_V " +
      "[DOMAIN_DIMENSION [ARITHMETIC_PROFILE]]"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val domainDimension =
    if (args.length >= 4) args(3).toInt
    else PaperSetII.blindRotateDomainDimension
  val rtlProfile =
    if (args.length == 5) FptRtlProfile.named(args(4))
    else PaperSetII
  require(domainDimension >= 1, "DOMAIN_DIMENSION must be positive")
  val config = rtlProfile.bufferedBlindRotate(
    forwardPath.toString,
    inversePath.toString,
    domainDimension
  )

  ChiselStage.emitSystemVerilogFile(
    new BufferedBatchedBlindRotateSampleExtractEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  val systemVerilog = outputDirectory.resolve(
    "BufferedBatchedBlindRotateSampleExtractEngine.sv"
  )
  SynthesisEmitter.removeInlineFileList(systemVerilog)
  SynthesisEmitter.addBlockRamStyle(
    systemVerilog,
    config.keyMemoryModuleName
  )
}

object EmitPaperBufferedBlindRotateAccelerator extends App {
  require(
    args.length >= 3 && args.length <= 5,
    "usage: EmitPaperBufferedBlindRotateAccelerator " +
      "OUTPUT_DIR SGEN_FORWARD_V SGEN_INVERSE_V " +
      "[DOMAIN_DIMENSION [ARITHMETIC_PROFILE]]"
  )

  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val domainDimension =
    if (args.length >= 4) args(3).toInt
    else PaperSetII.blindRotateDomainDimension
  val rtlProfile =
    if (args.length == 5) FptRtlProfile.named(args(4))
    else PaperSetII
  require(domainDimension >= 1, "DOMAIN_DIMENSION must be positive")
  val config = rtlProfile.bufferedBlindRotate(
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

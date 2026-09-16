package fpt

import chisel3._
import circt.stage.ChiselStage
import java.nio.file.Path

object EmitLaneLocalCoefficientGate extends App {
  require(args.length == 1)
  val profile = PaperU280BufferedBarrelConfig(sys.env("FPT_SGEN_FORWARD"),
    sys.env("FPT_SGEN_INVERSE"), PaperSetII.blindRotateDomainDimension).blindRotate.cmux
  val c = profile.engine.coefficient
  val dir = Path.of(args(0)).toAbsolutePath
  ChiselStage.emitSystemVerilogFile(new PrecomputedWindowedBatchedCmuxCoefficientStore(
    c, PaperSetII.bitwiseBatchContexts, bufferedSingleAccumulator = true,
    coefficientPreprocessGuardBits = profile.coefficientPreprocessGuardBits,
    groupedScratchControls = true, coefficientLocality = true,
    bankLocalAccumulatorInit = true, coefficientLaneTileLanes = profile.coefficientLaneTileLanes,
    windowMsbFirst = profile.windowMsbFirst, minimalMetadataReset = profile.minimalMetadataReset),
    args = Array("--target-dir", dir.toString), firtoolOpts = SynthesisEmitter.firtoolOptions)
  val source = dir.resolve("PrecomputedWindowedBatchedCmuxCoefficientStore.sv")
  SynthesisEmitter.removeInlineFileList(source)
  SynthesisEmitter.addBlockRamStyleByPrefix(source, "digitMemory_")
  SynthesisEmitter.addDistributedRamStyleByPrefix(source, "ram_8x")
  SynthesisEmitter.useReadClockForMemoryWritesByPrefix(source, "forwardMemories_")
  SynthesisEmitter.addUltraRamStyleByPrefix(source, "forwardMemories_")
}

object EmitLaneLocalKeyGate extends App {
  require(args.length == 1)
  val cfg = BootstrappingKeyBufferConfig(PaperSetII.external, PaperSetII.bitwiseBatchContexts,
    PaperSetII.blindRotateDomainDimension, PaperSetII.keyLoadLanes,
    writeTileLanes = sys.env.getOrElse("FPT_U280_KEY_WRITE_TILE_LANES", "0").toInt,
    minimalMetadataReset = sys.env.get("FPT_U280_MINIMAL_METADATA_RESET").contains("1"))
  val dir = Path.of(args(0)).toAbsolutePath
  ChiselStage.emitSystemVerilogFile(new BootstrappingKeyPingPongBuffer(cfg),
    args = Array("--target-dir", dir.toString), firtoolOpts = SynthesisEmitter.firtoolOptions)
  val source = dir.resolve("BootstrappingKeyPingPongBuffer.sv")
  SynthesisEmitter.removeInlineFileList(source)
  val depth = cfg.bankCount * cfg.wordsPerCoefficient
  val width = cfg.complexValuesPerRead * 2 * cfg.externalProduct.bootstrappingKey.width / cfg.writeTiles
  SynthesisEmitter.addBlockRamStyle(source, s"memory_${depth}x${width}")
}

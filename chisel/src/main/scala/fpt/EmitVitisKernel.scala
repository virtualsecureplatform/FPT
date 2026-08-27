package fpt

import circt.stage.ChiselStage
import java.nio.file.Path

object EmitFptBlindRotateKernelController extends App {
  require(
    args.length == 3,
    "usage: EmitFptBlindRotateKernelController OUTPUT_DIR FORWARD_V INVERSE_V"
  )
  val outputDirectory = Path.of(args(0)).toAbsolutePath.normalize
  val forwardPath = Path.of(args(1)).toAbsolutePath.normalize
  val inversePath = Path.of(args(2)).toAbsolutePath.normalize
  val config = PaperU280BufferedBarrelConfig(
    forwardPath.toString,
    inversePath.toString,
    PaperSetII.blindRotateDomainDimension
  )

  ChiselStage.emitSystemVerilogFile(
    new FptBlindRotateKernelController(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  val systemVerilog = outputDirectory.resolve("FptBlindRotateKernelController.sv")
  SynthesisEmitter.removeInlineFileList(systemVerilog)
  SynthesisEmitter.addBlockRamStyle(systemVerilog, config.keyMemoryModuleName)
  SynthesisEmitter.addBlockRamStyleByPrefix(systemVerilog, "digitMemories_")
  SynthesisEmitter.useReadClockForMemoryWritesByPrefix(
    systemVerilog,
    "accumulatorMemories_0_"
  )
  SynthesisEmitter.useReadClockForMemoryWritesByPrefix(
    systemVerilog,
    "accumulatorMemories_1_"
  )
  SynthesisEmitter.addUltraRamStyleByPrefix(systemVerilog, "accumulatorMemories_0_")
  SynthesisEmitter.addUltraRamStyleByPrefix(systemVerilog, "accumulatorMemories_1_")
  SynthesisEmitter.addBlockRamStyleByPrefix(systemVerilog, "componentOneMemory_")
}

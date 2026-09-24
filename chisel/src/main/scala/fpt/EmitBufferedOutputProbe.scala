package fpt

import circt.stage.ChiselStage
import java.nio.file.Path

object EmitBufferedOutputProbe extends App {
  require(args.length == 1)
  val directory = Path.of(args(0)).toAbsolutePath.normalize
  ChiselStage.emitSystemVerilogFile(new BufferedSampleExtract(),
    args = Array("--target-dir", directory.toString), firtoolOpts = SynthesisEmitter.firtoolOptions)
  SynthesisEmitter.addBlockRamStyleByPrefix(directory.resolve("BufferedSampleExtract.sv"), "snapshotMaskMemory_")
}

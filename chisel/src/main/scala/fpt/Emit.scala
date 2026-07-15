package fpt

import circt.stage.ChiselStage

import java.nio.file.Path

object PaperSetII {
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
    accumulator = FixedFormat(27, 3)
  )
  def cmuxEngine(
      forwardPath: String,
      inversePath: String,
      includeVerilogSource: Boolean
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
      )
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
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
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
  val config = BatchedCmuxEngineConfig(engine, batchContexts = 12)

  ChiselStage.emitSystemVerilogFile(
    new BatchedCmuxEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
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
    batchContexts = 14,
    coefficientStorage = BatchedCoefficientStorage.ReplicatedBanks
  )

  ChiselStage.emitSystemVerilogFile(
    new BatchedCmuxEngine(config),
    args = Array("--target-dir", outputDirectory.toString),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
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
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}

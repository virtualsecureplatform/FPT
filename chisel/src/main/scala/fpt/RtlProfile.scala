package fpt

sealed class FptRtlProfile(
    val profileName: String,
    val arithmetic: ArithmeticProfile,
    val externalProductMultiplier: ExternalProductMultiplier
) {
  val blindRotateDomainDimension = 630
  val barrelBatchContexts = 14
  val bitwiseBatchContexts = 16
  val keyLoadLanes = 32

  val coefficient = CmuxCoefficientConfig(
    polynomialSize = 1024,
    forwardLanes = 128,
    inverseLanes = 64,
    components = 2,
    levels = 2,
    baseBits = 10,
    torusWidth = 32,
    forwardFormat = arithmetic.forwardFft,
    inverseFormat = arithmetic.inverseFft
  )

  private def transform(format: FixedFormat, lanes: Int): TransformConfig = {
    val twiddleWidth = format.width - arithmetic.twiddleWidthReduction
    require(twiddleWidth >= 2)
    TransformConfig(
      points = 512,
      lanes = lanes,
      dataWidth = format.width,
      twiddleWidth = twiddleWidth,
      twiddleFractionalBits = twiddleWidth - 2
    )
  }

  val forward: TransformConfig = transform(arithmetic.forwardFft, 128)
  val inverse: TransformConfig = transform(arithmetic.inverseFft, 64)
  val external = ExternalProductConfig(
    points = 512,
    inputLanes = 128,
    outputLanes = 64,
    rows = 4,
    outputComponents = 2,
    spectrum = arithmetic.forwardFft,
    bootstrappingKey = arithmetic.bootstrappingKey,
    accumulator = arithmetic.inverseFft,
    multiplier = externalProductMultiplier
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

  def bufferedBlindRotate(
      forwardPath: String,
      inversePath: String,
      domainDimension: Int,
      includeVerilogSource: Boolean = false
  ): BufferedBlindRotateConfig = {
    val engine = cmuxEngine(
      forwardPath,
      inversePath,
      includeVerilogSource = includeVerilogSource,
      bitwiseBitsPerCycle = Some(2)
    )
    val blindRotate = BatchedBlindRotateEngineConfig(
      BatchedCmuxEngineConfig(
        engine,
        batchContexts = bitwiseBatchContexts,
        coefficientStorage =
          BatchedCoefficientStorage.BitwiseReplicatedBanks,
        serializeInverseComponents = true,
        useSynchronousExternalProductMemory = true,
        decoupledBootstrappingKey = true
      ),
      domainDimension
    )
    BufferedBlindRotateConfig(blindRotate, keyLoadLanes, keyBanks = 3)
  }
}

object PaperSetII
    extends FptRtlProfile(
      "paper-set-ii",
      ArithmeticProfile.PaperSetII,
      ExternalProductMultiplier.ExactGaussDsp
    )

object TfheppHardware
    extends FptRtlProfile(
      "tfhepp-hardware",
      ArithmeticProfile.TfheppHardware,
      ExternalProductMultiplier.ExactGaussTwoLimbDsp
    )

object FptRtlProfile {
  def named(name: String): FptRtlProfile = name match {
    case PaperSetII.profileName => PaperSetII
    case TfheppHardware.profileName => TfheppHardware
    case _ =>
      throw new IllegalArgumentException(
        s"unknown arithmetic profile '$name'; expected " +
          s"${PaperSetII.profileName} or ${TfheppHardware.profileName}"
      )
  }
}

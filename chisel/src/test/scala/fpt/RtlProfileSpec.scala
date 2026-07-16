package fpt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class RtlProfileSpec extends AnyFlatSpec with Matchers {
  behavior of "FPT RTL arithmetic profiles"

  it should "retain the paper Set II synthesis contract" in {
    PaperSetII.arithmetic should be(ArithmeticProfile.PaperSetII)
    PaperSetII.forward.dataWidth should be(30)
    PaperSetII.forward.twiddleWidth should be(26)
    PaperSetII.inverse.dataWidth should be(30)
    PaperSetII.external.bootstrappingKey.width should be(27)
    PaperSetII.external.multiplier should be(
      ExternalProductMultiplier.ExactGaussDsp
    )
    val buffered = PaperSetII.bufferedBlindRotate(
      "forward.v",
      "inverse.v",
      PaperSetII.blindRotateDomainDimension
    )
    buffered.keyMemoryModuleName should be("memory_32x13824")
  }

  it should "derive the TFHEpp hardware datapath from one profile" in {
    TfheppHardware.arithmetic should be(ArithmeticProfile.TfheppHardware)
    TfheppHardware.forward.dataWidth should be(46)
    TfheppHardware.forward.twiddleWidth should be(42)
    TfheppHardware.forward.twiddleFractionalBits should be(40)
    TfheppHardware.inverse.dataWidth should be(51)
    TfheppHardware.inverse.twiddleWidth should be(47)
    TfheppHardware.inverse.twiddleFractionalBits should be(45)
    TfheppHardware.external.bootstrappingKey.width should be(29)
    TfheppHardware.external.multiplier should be(
      ExternalProductMultiplier.ExactGaussTwoLimbDsp
    )
    val buffered = TfheppHardware.bufferedBlindRotate(
      "forward.v",
      "inverse.v",
      TfheppHardware.blindRotateDomainDimension
    )
    buffered.keyMemoryModuleName should be("memory_32x14848")
    buffered.keyBuffer.loadLanes should be(16)
  }

  it should "resolve only named generator profiles" in {
    FptRtlProfile.named("paper-set-ii") should be(PaperSetII)
    FptRtlProfile.named("tfhepp-hardware") should be(TfheppHardware)
    an[IllegalArgumentException] should be thrownBy
      FptRtlProfile.named("unknown")
  }
}

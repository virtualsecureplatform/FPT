package fpt

final case class FixedFormat(integerBits: Int, fractionalBits: Int) {
  require(integerBits >= 1)
  require(fractionalBits >= 0)
  val width: Int = integerBits + fractionalBits
}

final case class ArithmeticProfile(
    bootstrappingKey: FixedFormat,
    forwardFft: FixedFormat,
    inverseFft: FixedFormat,
    twiddleWidthReduction: Int = 4
)

object ArithmeticProfile {
  val PaperSetI: ArithmeticProfile = ArithmeticProfile(
    FixedFormat(7, 19), FixedFormat(15, 14), FixedFormat(23, 6)
  )
  val PaperSetII: ArithmeticProfile = ArithmeticProfile(
    FixedFormat(8, 19), FixedFormat(18, 12), FixedFormat(27, 3)
  )
  val TfheppGuarded: ArithmeticProfile = ArithmeticProfile(
    FixedFormat(8, 24), FixedFormat(18, 20), FixedFormat(27, 14)
  )
  val TfheppHardware: ArithmeticProfile = ArithmeticProfile(
    FixedFormat(8, 21), FixedFormat(18, 28), FixedFormat(27, 24)
  )
}

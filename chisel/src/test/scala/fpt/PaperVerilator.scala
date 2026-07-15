package fpt

import chiseltest.simulator.VerilatorFlags

import scala.collection.mutable.ArrayBuffer

/** Verilator controls for the generated Set-II designs.
  *
  * These designs generate C++ translation units close to one GiB when output
  * splitting is disabled. Keep the threshold configurable for tool-version
  * tuning, but default to Verilator's practical large-model split size.
  */
private[fpt] object PaperVerilator {
  def flags: VerilatorFlags = {
    val split = sys.env
      .get("FPT_VERILATOR_SPLIT")
      .fold(20000)(_.toInt)
    require(split > 0, s"FPT_VERILATOR_SPLIT must be positive, got $split")

    val flags = ArrayBuffer(
      "--output-split",
      split.toString,
      "--output-split-cfuncs",
      split.toString
    )
    sys.env.get("FPT_VERILATOR_COMPILER").foreach { compiler =>
      require(compiler.nonEmpty, "FPT_VERILATOR_COMPILER must not be empty")
      flags ++= Seq("--compiler", compiler)
    }
    VerilatorFlags(flags.toSeq)
  }
}

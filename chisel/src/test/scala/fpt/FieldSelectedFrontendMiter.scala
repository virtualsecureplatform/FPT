package fpt

import chisel3._
import chisel3.reflect.DataMirror

/** Compare the opt-in frontend against the wide implementation every cycle. */
private[fpt] class FieldSelectedFrontendMiter(
    override val config: CmuxCoefficientConfig,
    override val batchContexts: Int,
    guardBits: Option[Int],
    singleAccumulator: Boolean = false
) extends BatchedCmuxCoefficientStoreBase(config, batchContexts) {
  val wide = Module(new PrecomputedWindowedBatchedCmuxCoefficientStore(
    config, batchContexts, singleAccumulator, guardBits, false
  ))
  val selected = Module(new PrecomputedWindowedBatchedCmuxCoefficientStore(
    config, batchContexts, singleAccumulator, guardBits, true
  ))
  io <> wide.io
  for ((name, port) <- selected.io.elements) {
    if (DataMirror.directionOf(port) == ActualDirection.Input) {
      port := io.elements(name)
    }
  }
  // Invalid payloads are intentionally uninitialized in both implementations.
  val qualified = Map(
    "coefficientLow" -> wide.io.pairValid,
    "coefficientHigh" -> wide.io.pairValid,
    "rowIndex" -> wide.io.pairValid,
    "pairLast" -> wide.io.pairValid,
    "drain" -> wide.io.drainValid,
    "loadDoneContext" -> wide.io.loadDone,
    "updateDoneContext" -> wide.io.updateDone,
    "drainDoneContext" -> wide.io.drainDone
  )
  for ((name, port) <- selected.io.elements) {
    if (DataMirror.directionOf(port) == ActualDirection.Output) {
      when(qualified.getOrElse(name, true.B)) {
        assert(port.asUInt === wide.io.elements(name).asUInt,
          s"selected scratch frontend diverged: $name")
      }
    }
  }
}

package fpt

import chisel3._

/** The existing exhaustive exponent/backpressure driver also checks cycle equality. */
final class CoefficientLaneTileMiter(c: CmuxCoefficientConfig, contexts: Int,
    guardBits: Option[Int]) extends BatchedCmuxCoefficientStoreBase(c, contexts) {
  def frontend(lanes: Int, candidate: Boolean) = new PrecomputedWindowedBatchedCmuxCoefficientStore(
    c, contexts, bufferedSingleAccumulator = true,
    coefficientPreprocessGuardBits = guardBits, coefficientLocality = true,
    groupedScratchControls = true, coefficientLaneTileLanes = lanes,
    windowMsbFirst = candidate && sys.env.get("FPT_U280_WINDOW_MSB_FIRST").contains("1"),
    minimalMetadataReset = candidate && sys.env.get("FPT_U280_MINIMAL_METADATA_RESET").contains("1"),
    localCoefficientQueues = candidate && sys.env.get("FPT_U280_LOCAL_COEFFICIENT_QUEUES").contains("1"))
  val candidate = Module(frontend(4, true))
  val reference = Module(frontend(4, false))
  candidate.io <> io
  for ((name, port) <- reference.io.elements
       if chisel3.reflect.DataMirror.directionOf(port) == ActualDirection.Input) {
    port := io.elements(name)
  }
  for (name <- Seq("loadReady", "loadDone", "commandReady", "transformStart",
      "pairValid", "updateReady", "updateDone", "drainStartReady", "drainValid",
      "drainDone", "contextLoaded", "contextBusy")) {
    assert(reference.io.elements(name).asUInt === candidate.io.elements(name).asUInt,
      s"coefficient tile changed $name timing")
  }
  when(io.pairValid) {
    assert(reference.io.coefficientLow.asUInt === io.coefficientLow.asUInt)
    assert(reference.io.coefficientHigh.asUInt === io.coefficientHigh.asUInt)
    assert(reference.io.rowIndex === io.rowIndex)
    assert(reference.io.pairLast === io.pairLast)
  }
  when(io.drainValid) { assert(reference.io.drain.asUInt === io.drain.asUInt) }
  when(io.loadDone) { assert(reference.io.loadDoneContext === io.loadDoneContext) }
  when(io.updateDone) { assert(reference.io.updateDoneContext === io.updateDoneContext) }
  when(io.drainDone) { assert(reference.io.drainDoneContext === io.drainDoneContext) }
}

package fpt

import chisel3._
import chisel3.util.ShiftRegister
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private[fpt] class ScratchReadoutHarness(
    selected: Boolean, lanes: Int, width: Int, normalizeOutput: Boolean = false,
    grouped: Boolean = false
)
    extends Module {
  val scratch = Module(new MultiportedCoefficientScratch(
    depth = 16, addressWidth = 4, wordWidth = lanes * 4 * width,
    readPorts = 4, banks = math.min(32, lanes),
    fieldSelected = selected, components = 2, fieldWidth = width, groupedControls = grouped
  ))
  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(4.W))
    val inputData = Input(Vec(lanes * 4, UInt(width.W)))
    val primeCapture = Input(Bool())
    val primeData = Output(Vec(lanes * 4, UInt(width.W)))
    val readEnables = Input(UInt(4.W))
    val readAddresses = Input(UInt(16.W))
    val readComponent = Input(UInt(1.W))
    val readHalves = Input(UInt(4.W))
    val outputData = Output(Vec(
      if (normalizeOutput) lanes * 5 else scratch.responseWidth / width,
      UInt(width.W)
    ))
  })
  scratch.io.writeEnable := io.writeEnable
  scratch.io.writeAddress := io.writeAddress
  scratch.io.inputData := io.inputData.asUInt
  scratch.io.primeCapture := io.primeCapture
  io.primeData := scratch.io.primeData.asTypeOf(io.primeData)
  scratch.io.readEnables := io.readEnables
  scratch.io.readAddresses := io.readAddresses
  scratch.io.readComponent := io.readComponent
  scratch.io.readHalves := io.readHalves
  if (normalizeOutput && !selected) {
    // Include the downstream selection in the reference resource/timing
    // boundary: comparing raw wide outputs to selected outputs is not fair.
    val component = ShiftRegister(io.readComponent, if (grouped) 3 else 2)
    val halves = ShiftRegister(io.readHalves, if (grouped) 3 else 2)
    val words = scratch.io.outputData.asTypeOf(
      Vec(4, Vec(lanes, Vec(2, Vec(2, UInt(width.W)))))
    )
    io.outputData := VecInit((0 until 4).flatMap { port =>
      (0 until lanes).flatMap { lane =>
        if (port == 1) (0 until 2).map(c => words(port)(lane)(c)(halves(port)))
        else Seq(words(port)(lane)(component)(halves(port)))
      }
    })
  } else {
    io.outputData := scratch.io.outputData.asTypeOf(io.outputData)
  }
  scratch.io.clock := clock
}

/** Test-scope emitter for identical isolated Vivado comparisons. */
object EmitScratchReadoutGate extends App {
  require(args.length == 2 || args.length == 3, "usage: EmitScratchReadoutGate wide|selected OUTPUT_DIR [flat|grouped]")
  require(args.length == 2 || Set("flat", "grouped").contains(args(2)))
  require(Set("wide", "selected").contains(args(0)))
  ChiselStage.emitSystemVerilogFile(
    new ScratchReadoutHarness(args(0) == "selected", 64, 24, normalizeOutput = true, grouped = args.lift(2).contains("grouped")),
    args = Array("--target-dir", args(1)),
    firtoolOpts = SynthesisEmitter.firtoolOptions
  )
  SynthesisEmitter.removeInlineFileList(
    java.nio.file.Path.of(args(1)).resolve("ScratchReadoutHarness.sv")
  )
}

class FieldSelectedScratchSpec extends AnyFlatSpec with ChiselScalatestTester
    with Matchers {
  behavior of "field-selected coefficient scratch readout"

  for ((selected, normalized) <- Seq((false, false), (false, true), (true, true));
       (lanes, width) <- Seq((2, 8), (64, 24)); grouped <- Seq(false, true)) {
    it should s"preserve read latency and prime capture: selected=$selected normalized=$normalized lanes=$lanes grouped=$grouped" in {
      test(new ScratchReadoutHarness(selected, lanes, width, normalized, grouped))
        .withAnnotations(Seq(VerilatorBackendAnnotation, PaperVerilator.flags)) { dut =>
          val random = new scala.util.Random(73)
          val wordWidth = lanes * 4 * width
          val mask = (BigInt(1) << width) - 1
          def pokeWord(port: Vec[UInt], value: BigInt): Unit =
            port.zipWithIndex.foreach { case (p, i) => p.poke(((value >> (i * width)) & mask).U) }
          def expectWord(port: Vec[UInt], value: BigInt): Unit =
            port.zipWithIndex.foreach { case (p, i) => p.expect(((value >> (i * width)) & mask).U) }
          val words = Array.fill(16)(BigInt(wordWidth, random))
          def field(word: BigInt, lane: Int, component: Int, half: Int): BigInt =
            (word >> ((lane * 4 + component * 2 + half) * width)) & mask
          def pack(values: Seq[BigInt], bits: Int): BigInt =
            values.zipWithIndex.map { case (v, i) => v << (i * bits) }.foldLeft(BigInt(0))(_ | _)
          dut.io.writeEnable.poke(false.B)
          dut.io.writeAddress.poke(0.U)
          pokeWord(dut.io.inputData, 0)
          dut.io.primeCapture.poke(false.B)
          dut.io.readEnables.poke(0.U)
          dut.io.readAddresses.poke(0.U)
          dut.io.readComponent.poke(0.U)
          dut.io.readHalves.poke(0.U)
          dut.clock.step(3)
          for (address <- 0 until 16) {
            dut.io.writeEnable.poke(true.B)
            dut.io.writeAddress.poke(address.U)
            pokeWord(dut.io.inputData, words(address))
            dut.io.primeCapture.poke((address == 5).B)
            dut.clock.step()
          }
          dut.io.writeEnable.poke(false.B)
          dut.io.primeCapture.poke(false.B)
          dut.clock.step(3)
          expectWord(dut.io.primeData, words(5))

          val pending = scala.collection.mutable.Queue.empty[BigInt]
          val readLatency = if (grouped) 3 else 2
          // Exhaust every address/field, changing the request every cycle.
          // Later requests read buffer zero while buffer one is refilled.
          for (cycle <- 0 until 160) {
            val concurrentFill = cycle >= 128
            val addresses = (0 until 4).map(p =>
              (cycle / 4 + p * 3) % (if (concurrentFill) 8 else 16))
            val component = cycle & 1
            val halves = (0 until 4).map(p => ((cycle / 2) + p) & 1)
            val expected = if (selected || normalized) {
              pack((0 until 4).flatMap { p =>
                (0 until lanes).flatMap { lane =>
                  (if (p == 1) Seq(0, 1) else Seq(component)).map(c =>
                    field(words(addresses(p)), lane, c, halves(p)))
                }
              }, width)
            } else pack(addresses.map(words(_)), wordWidth)
            dut.io.readAddresses.poke(pack(addresses.map(BigInt(_)), 4).U)
            dut.io.readComponent.poke(component.U)
            dut.io.readHalves.poke(pack(halves.map(BigInt(_)), 1).U)
            // Bubble reads remain unconditional, as in the original design.
            dut.io.readEnables.poke((if (cycle % 7 == 0) 0 else 15).U)
            dut.io.writeEnable.poke(concurrentFill.B)
            dut.io.writeAddress.poke((8 + cycle % 8).U)
            pokeWord(dut.io.inputData, BigInt(wordWidth, random))
            dut.clock.step()
            pending.enqueue(expected)
            if (pending.size >= readLatency) expectWord(dut.io.outputData, pending.dequeue())
          }
          dut.io.writeEnable.poke(false.B)
          while (pending.nonEmpty) {
            dut.clock.step()
            expectWord(dut.io.outputData, pending.dequeue())
          }
          expectWord(dut.io.primeData, words(5))
        }
    }
  }
}

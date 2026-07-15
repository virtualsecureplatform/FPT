package fpt

import chisel3._
import chisel3.util._

import scala.collection.immutable.ListMap

private final class SGenPorts(val lanes: Int, val wordWidth: Int)
    extends Record {
  val clockPort = Input(Clock())
  val resetPort = Input(Bool())
  val nextPort = Input(Bool())
  val inputPorts: IndexedSeq[UInt] =
    (0 until lanes).map(_ => Input(UInt(wordWidth.W)))
  val nextOutPort = Output(Bool())
  val outputPorts: IndexedSeq[UInt] =
    (0 until lanes).map(_ => Output(UInt(wordWidth.W)))

  override val elements: ListMap[String, Data] = ListMap(
    (Seq(
      "clk" -> clockPort,
      "reset" -> resetPort,
      "next" -> nextPort
    ) ++
      inputPorts.zipWithIndex.map { case (port, index) => s"i$index" -> port } ++
      Seq("next_out" -> nextOutPort) ++
      outputPorts.zipWithIndex.map { case (port, index) =>
        s"o$index" -> port
      }): _*
  )
}

/** The only non-Chisel RTL exception in the implementation: an
  * upstream-generated SGen cyclic FFT. All arithmetic around the BlackBox is
  * typed and controlled by Chisel.
  */
private abstract class SGenBlackBoxBase(
    val lanes: Int,
    val componentWidth: Int,
    val moduleName: String
) extends BlackBox {
  override def desiredName: String = moduleName
  val io = IO(new SGenPorts(lanes, 2 * componentWidth))
}

private final class EmbeddedSGenBlackBox(
    lanes: Int,
    componentWidth: Int,
    moduleName: String,
    val verilogPath: String
) extends SGenBlackBoxBase(lanes, componentWidth, moduleName)
    with HasBlackBoxPath {
  addPath(verilogPath)
}

/** Synthesis builds read the generated SGen source as a separate file. Leaving
  * this BlackBox unresolved prevents CIRCT's resource packager from appending
  * the source and its file list to the emitted Chisel SystemVerilog.
  */
private final class ExternalSGenBlackBox(
    lanes: Int,
    componentWidth: Int,
    moduleName: String
) extends SGenBlackBoxBase(lanes, componentWidth, moduleName)

/** Converts SGen's flat, fixed-rate interface into typed Chisel vectors.
  * `start` must be asserted one cycle before the first input beat. Once a
  * frame begins, every beat must be valid and every output beat must be
  * accepted; assertions make violations visible in simulation and formal
  * checking.
  */
final class SGenCyclicBackend(
    val config: TransformConfig,
    val moduleName: String,
    val verilogPath: String,
    val inputLeadCycles: Int = 1,
    val includeVerilogSource: Boolean = true
) extends Module {
  import TransformUtil._
  require(inputLeadCycles >= 1)
  require(!includeVerilogSource || verilogPath.nonEmpty)
  private val beatWidth = counterWidth(config.frameBeats)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val input = Input(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val output = Output(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val done = Output(Bool())
  })

  private val generated: SGenBlackBoxBase = if (includeVerilogSource) {
    Module(
      new EmbeddedSGenBlackBox(
        config.lanes,
        config.dataWidth,
        moduleName,
        verilogPath
      )
    )
  } else {
    Module(
      new ExternalSGenBlackBox(
        config.lanes,
        config.dataWidth,
        moduleName
      )
    )
  }
  generated.io.clockPort := clock
  generated.io.resetPort := reset.asBool
  generated.io.nextPort := io.start
  for (lane <- 0 until config.lanes) {
    generated.io.inputPorts(lane) := Cat(
      io.input(lane).imag.asUInt,
      io.input(lane).real.asUInt
    )
    io.output(lane).real := generated.io.outputPorts(lane)(
      config.dataWidth - 1, 0
    ).asSInt
    io.output(lane).imag := generated.io.outputPorts(lane)(
      2 * config.dataWidth - 1, config.dataWidth
    ).asSInt
  }

  if (inputLeadCycles == 1) {
    val acceptingInput = RegInit(false.B)
    val inputBeat = RegInit(0.U(beatWidth.W))
    io.inputReady := acceptingInput
    val finalInputBeat = acceptingInput && io.inputValid &&
      inputBeat === (config.frameBeats - 1).U
    when(io.start && !acceptingInput) {
      acceptingInput := true.B
      inputBeat := 0.U
    }
    when(io.start) {
      assert(
        !acceptingInput || finalInputBeat,
        "SGen start must be idle or coincide with the final input beat"
      )
    }
    when(acceptingInput) {
      assert(io.inputValid, "SGen input frames cannot contain bubbles")
      when(io.inputValid) {
        when(inputBeat === (config.frameBeats - 1).U) {
          inputBeat := 0.U
          // In a one-cycle-lead full-throughput design the next marker
          // coincides with the current frame's final input beat.
          acceptingInput := io.start
        }.otherwise {
          inputBeat := inputBeat + 1.U
        }
      }
    }
  } else {
    val leadWidth = counterWidth(inputLeadCycles)
    val waitingForInput = RegInit(false.B)
    val acceptingInput = RegInit(false.B)
    val leadCounter = RegInit(0.U(leadWidth.W))
    val inputBeat = RegInit(0.U(beatWidth.W))
    io.inputReady := acceptingInput
    when(io.start) {
      assert(
        !waitingForInput && !acceptingInput,
        "multi-cycle-lead SGen frames cannot overlap in this adapter"
      )
      waitingForInput := true.B
      leadCounter := (inputLeadCycles - 1).U
      inputBeat := 0.U
    }
    when(waitingForInput) {
      when(leadCounter === 1.U) {
        waitingForInput := false.B
        acceptingInput := true.B
      }.otherwise {
        leadCounter := leadCounter - 1.U
      }
    }
    when(acceptingInput) {
      assert(io.inputValid, "SGen input frames cannot contain bubbles")
      when(io.inputValid) {
        when(inputBeat === (config.frameBeats - 1).U) {
          inputBeat := 0.U
          acceptingInput := false.B
        }.otherwise {
          inputBeat := inputBeat + 1.U
        }
      }
    }
  }

  val outputActive = RegInit(false.B)
  val outputBeat = RegInit(0.U(beatWidth.W))
  val doneReg = RegInit(false.B)
  doneReg := false.B
  io.outputValid := generated.io.nextOutPort || outputActive
  io.done := doneReg

  when(io.outputValid) {
    assert(io.outputReady, "SGen outputs cannot be backpressured")
  }
  when(generated.io.nextOutPort) {
    if (config.frameBeats == 1) {
      outputActive := false.B
      doneReg := true.B
    } else {
      outputActive := true.B
      outputBeat := 1.U
    }
  }.elsewhen(outputActive) {
    when(outputBeat === (config.frameBeats - 1).U) {
      outputBeat := 0.U
      outputActive := false.B
      doneReg := true.B
    }.otherwise {
      outputBeat := outputBeat + 1.U
    }
  }
}

final class SGenTangentFft(
    val config: TransformConfig,
    val moduleName: String,
    val verilogPath: String,
    val inputLeadCycles: Int = 1,
    val includeVerilogSource: Boolean = true
) extends Module {
  import TransformUtil._
  private val beatWidth = counterWidth(config.frameBeats)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val pairValid = Input(Bool())
    val pairReady = Output(Bool())
    val coefficientLow = Input(Vec(config.lanes, SInt(config.dataWidth.W)))
    val coefficientHigh = Input(Vec(config.lanes, SInt(config.dataWidth.W)))
    val twistIndex = Output(Vec(config.lanes, UInt(config.logPoints.W)))
    val twist = Input(Vec(config.lanes, new GaussTwiddle(config.twiddleWidth)))
    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val output = Output(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val done = Output(Bool())
  })

  val backend = Module(
    new SGenCyclicBackend(
      config,
      moduleName,
      verilogPath,
      inputLeadCycles,
      includeVerilogSource
    )
  )
  backend.io.start := io.start
  backend.io.inputValid := io.pairValid
  io.pairReady := backend.io.inputReady
  backend.io.outputReady := io.outputReady
  io.outputValid := backend.io.outputValid
  io.output := backend.io.output
  io.done := backend.io.done

  val pairBeat = RegInit(0.U(beatWidth.W))
  when(io.start) { pairBeat := 0.U }
  for (lane <- 0 until config.lanes) {
    io.twistIndex(lane) := indexedAddress(
      pairBeat, config.lanes, lane, config.logPoints
    )
    val twist = Module(
      new GaussMultiply(
        config.dataWidth,
        config.twiddleWidth,
        config.twiddleFractionalBits
      )
    )
    twist.io.value.real := io.coefficientLow(lane)
    twist.io.value.imag := io.coefficientHigh(lane)
    twist.io.twiddle := io.twist(lane)
    backend.io.input(lane) := twist.io.result
  }
  when(io.pairValid && io.pairReady) {
    when(pairBeat === (config.frameBeats - 1).U) {
      pairBeat := 0.U
    }.otherwise {
      pairBeat := pairBeat + 1.U
    }
  }
}

final class SGenTangentIfft(
    val config: TransformConfig,
    val normalizeShift: Int,
    val moduleName: String,
    val verilogPath: String,
    val inputLeadCycles: Int = 1,
    val includeVerilogSource: Boolean = true
) extends Module {
  import TransformUtil._
  require(normalizeShift >= 0 && normalizeShift < config.dataWidth)
  private val beatWidth = counterWidth(config.frameBeats)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val input = Input(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val untwistIndex = Output(Vec(config.lanes, UInt(config.logPoints.W)))
    val untwist = Input(
      Vec(config.lanes, new GaussTwiddle(config.twiddleWidth))
    )
    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val coefficientLow = Output(Vec(config.lanes, SInt(config.dataWidth.W)))
    val coefficientHigh = Output(Vec(config.lanes, SInt(config.dataWidth.W)))
    val done = Output(Bool())
  })

  val backend = Module(
    new SGenCyclicBackend(
      config,
      moduleName,
      verilogPath,
      inputLeadCycles,
      includeVerilogSource
    )
  )
  backend.io.start := io.start
  backend.io.inputValid := io.inputValid
  io.inputReady := backend.io.inputReady
  backend.io.input := io.input
  backend.io.outputReady := io.outputReady
  io.outputValid := backend.io.outputValid
  io.done := backend.io.done

  val outputBeat = RegInit(0.U(beatWidth.W))
  when(backend.io.outputValid) {
    when(outputBeat === (config.frameBeats - 1).U) {
      outputBeat := 0.U
    }.otherwise {
      outputBeat := outputBeat + 1.U
    }
  }
  for (lane <- 0 until config.lanes) {
    io.untwistIndex(lane) := indexedAddress(
      outputBeat, config.lanes, lane, config.logPoints
    )
    val untwist = Module(
      new GaussMultiply(
        config.dataWidth,
        config.twiddleWidth,
        config.twiddleFractionalBits
      )
    )
    untwist.io.value := backend.io.output(lane)
    untwist.io.twiddle := io.untwist(lane)
    io.coefficientLow(lane) := untwist.io.result.real >> normalizeShift
    io.coefficientHigh(lane) := untwist.io.result.imag >> normalizeShift
  }
}

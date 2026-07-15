package fpt

import chisel3._
import chisel3.util._

final case class TransformConfig(
    points: Int,
    lanes: Int,
    dataWidth: Int,
    twiddleWidth: Int,
    twiddleFractionalBits: Int,
    scaleMask: BigInt = 0
) {
  require(points >= 4 && isPow2(points))
  require(lanes >= 1 && isPow2(lanes))
  require(lanes <= points / 2 && points % lanes == 0)
  require(dataWidth >= 2)
  require(twiddleWidth >= 2)

  val logPoints: Int = log2Ceil(points)
  val logLanes: Int = log2Ceil(lanes)
  val twiddleIndexWidth: Int = logPoints - 1
  val frameBeats: Int = points / lanes
  val butterfliesPerStage: Int = points / 2
  val computeCyclesPerStage: Int = butterfliesPerStage / lanes
}

private[fpt] object TransformUtil {
  def counterWidth(count: Int): Int = math.max(1, log2Ceil(count))

  def indexedAddress(base: UInt, lanes: Int, lane: Int, width: Int): UInt = {
    val padded = base.pad(width)
    val shifted = if (lanes == 1) padded else padded << log2Ceil(lanes)
    (shifted + lane.U(width.W))(width - 1, 0)
  }
}

/** A configurable multi-lane iterative cyclic FFT. It is the Chisel
  * correctness/scheduling core; the continuous-flow implementation can be
  * substituted behind the same arithmetic boundary with an SGen BlackBox.
  */
final class WideFftCore(val config: TransformConfig) extends Module {
  import TransformUtil._

  private val indexWidth = config.logPoints
  private val stageWidth = counterWidth(config.logPoints)
  private val beatWidth = counterWidth(config.frameBeats)
  private val computeCycleWidth = counterWidth(config.computeCyclesPerStage)

  val io = IO(new Bundle {
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val input = Input(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val twiddleIndex = Output(
      Vec(config.lanes, UInt(config.twiddleIndexWidth.W))
    )
    val twiddle = Input(
      Vec(config.lanes, new GaussTwiddle(config.twiddleWidth))
    )
    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val output = Output(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val busy = Output(Bool())
    val done = Output(Bool())
  })

  val load :: compute :: outputStream :: Nil = Enum(3)
  val state = RegInit(load)
  val realMemory = Reg(Vec(config.points, SInt(config.dataWidth.W)))
  val imagMemory = Reg(Vec(config.points, SInt(config.dataWidth.W)))
  val loadBeat = RegInit(0.U(beatWidth.W))
  val outputBeat = RegInit(0.U(beatWidth.W))
  val stageIndex = RegInit(0.U(stageWidth.W))
  val computeCycle = RegInit(0.U(computeCycleWidth.W))
  val doneReg = RegInit(false.B)

  val naturalInputAddress = Wire(Vec(config.lanes, UInt(indexWidth.W)))
  val naturalOutputAddress = Wire(Vec(config.lanes, UInt(indexWidth.W)))
  val evenAddress = Wire(Vec(config.lanes, UInt(indexWidth.W)))
  val oddAddress = Wire(Vec(config.lanes, UInt(indexWidth.W)))
  val selectedUpper = Wire(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
  val selectedLower = Wire(Vec(config.lanes, new ComplexSInt(config.dataWidth)))

  val scaleThisStage = VecInit(
    (0 until config.logPoints).map(bit => ((config.scaleMask >> bit) & 1) == 1).map(_.B)
  )(stageIndex)

  for (lane <- 0 until config.lanes) {
    naturalInputAddress(lane) := indexedAddress(
      loadBeat, config.lanes, lane, indexWidth
    )
    naturalOutputAddress(lane) := indexedAddress(
      outputBeat, config.lanes, lane, indexWidth
    )

    val slot = indexedAddress(
      computeCycle, config.lanes, lane, indexWidth
    )
    val addressesByStage = (0 until config.logPoints).map { stage =>
      val half = 1 << stage
      val butterfly = slot & (half - 1).U(indexWidth.W)
      val even = (((slot >> stage) << (stage + 1)) + butterfly)(
        indexWidth - 1, 0
      )
      val odd = (even + half.U)(indexWidth - 1, 0)
      val twiddle = (butterfly << (config.logPoints - stage - 1))(
        config.twiddleIndexWidth - 1, 0
      )
      (even, odd, twiddle)
    }
    evenAddress(lane) := MuxLookup(
      stageIndex,
      addressesByStage.head._1
    )(
      addressesByStage.zipWithIndex.map { case (entry, stage) =>
        stage.U -> entry._1
      }
    )
    oddAddress(lane) := MuxLookup(
      stageIndex,
      addressesByStage.head._2
    )(
      addressesByStage.zipWithIndex.map { case (entry, stage) =>
        stage.U -> entry._2
      }
    )
    io.twiddleIndex(lane) := MuxLookup(
      stageIndex,
      addressesByStage.head._3
    )(
      addressesByStage.zipWithIndex.map { case (entry, stage) =>
        stage.U -> entry._3
      }
    )

    val multiplier = Module(
      new GaussMultiply(
        config.dataWidth,
        config.twiddleWidth,
        config.twiddleFractionalBits
      )
    )
    multiplier.io.value.real := realMemory(oddAddress(lane))
    multiplier.io.value.imag := imagMemory(oddAddress(lane))
    multiplier.io.twiddle := io.twiddle(lane)

    def combine(lhs: SInt, rhs: SInt, subtract: Boolean): SInt = {
      val wide = if (subtract) lhs -& rhs else lhs +& rhs
      val selected = Mux(scaleThisStage, (wide >> 1).asSInt, wide)
      FixedPointBits.lowSigned(selected, config.dataWidth)
    }

    selectedUpper(lane).real := combine(
      realMemory(evenAddress(lane)), multiplier.io.result.real, false
    )
    selectedUpper(lane).imag := combine(
      imagMemory(evenAddress(lane)), multiplier.io.result.imag, false
    )
    selectedLower(lane).real := combine(
      realMemory(evenAddress(lane)), multiplier.io.result.real, true
    )
    selectedLower(lane).imag := combine(
      imagMemory(evenAddress(lane)), multiplier.io.result.imag, true
    )

    io.output(lane).real := realMemory(naturalOutputAddress(lane))
    io.output(lane).imag := imagMemory(naturalOutputAddress(lane))
  }

  io.inputReady := state === load
  io.outputValid := state === outputStream
  io.busy := state =/= load
  io.done := doneReg
  doneReg := false.B

  switch(state) {
    is(load) {
      when(io.inputValid) {
        for (lane <- 0 until config.lanes) {
          val address = Reverse(naturalInputAddress(lane))
          realMemory(address) := io.input(lane).real
          imagMemory(address) := io.input(lane).imag
        }
        when(loadBeat === (config.frameBeats - 1).U) {
          loadBeat := 0.U
          stageIndex := 0.U
          computeCycle := 0.U
          state := compute
        }.otherwise {
          loadBeat := loadBeat + 1.U
        }
      }
    }
    is(compute) {
      for (lane <- 0 until config.lanes) {
        realMemory(evenAddress(lane)) := selectedUpper(lane).real
        imagMemory(evenAddress(lane)) := selectedUpper(lane).imag
        realMemory(oddAddress(lane)) := selectedLower(lane).real
        imagMemory(oddAddress(lane)) := selectedLower(lane).imag
      }
      when(computeCycle === (config.computeCyclesPerStage - 1).U) {
        computeCycle := 0.U
        when(stageIndex === (config.logPoints - 1).U) {
          outputBeat := 0.U
          state := outputStream
        }.otherwise {
          stageIndex := stageIndex + 1.U
        }
      }.otherwise {
        computeCycle := computeCycle + 1.U
      }
    }
    is(outputStream) {
      when(io.outputReady) {
        when(outputBeat === (config.frameBeats - 1).U) {
          outputBeat := 0.U
          state := load
          doneReg := true.B
        }.otherwise {
          outputBeat := outputBeat + 1.U
        }
      }
    }
  }
}

final class TangentFftCore(val config: TransformConfig) extends Module {
  import TransformUtil._
  private val beatWidth = counterWidth(config.frameBeats)

  val io = IO(new Bundle {
    val pairValid = Input(Bool())
    val pairReady = Output(Bool())
    val coefficientLow = Input(Vec(config.lanes, SInt(config.dataWidth.W)))
    val coefficientHigh = Input(Vec(config.lanes, SInt(config.dataWidth.W)))
    val twistIndex = Output(Vec(config.lanes, UInt(config.logPoints.W)))
    val twist = Input(Vec(config.lanes, new GaussTwiddle(config.twiddleWidth)))
    val fftTwiddleIndex = Output(
      Vec(config.lanes, UInt(config.twiddleIndexWidth.W))
    )
    val fftTwiddle = Input(
      Vec(config.lanes, new GaussTwiddle(config.twiddleWidth))
    )
    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val output = Output(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val busy = Output(Bool())
    val done = Output(Bool())
  })

  val pairBeat = RegInit(0.U(beatWidth.W))
  val cyclic = Module(new WideFftCore(config))
  cyclic.io.inputValid := io.pairValid
  io.pairReady := cyclic.io.inputReady
  cyclic.io.twiddle := io.fftTwiddle
  io.fftTwiddleIndex := cyclic.io.twiddleIndex
  cyclic.io.outputReady := io.outputReady
  io.outputValid := cyclic.io.outputValid
  io.output := cyclic.io.output
  io.busy := cyclic.io.busy
  io.done := cyclic.io.done

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
    cyclic.io.input(lane) := twist.io.result
  }

  when(io.pairValid && io.pairReady) {
    when(pairBeat === (config.frameBeats - 1).U) {
      pairBeat := 0.U
    }.otherwise {
      pairBeat := pairBeat + 1.U
    }
  }
}

final class TangentIfftCore(
    val config: TransformConfig,
    val normalizeShift: Int
) extends Module {
  import TransformUtil._
  require(normalizeShift >= 0 && normalizeShift < config.dataWidth)
  private val beatWidth = counterWidth(config.frameBeats)

  val io = IO(new Bundle {
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())
    val input = Input(Vec(config.lanes, new ComplexSInt(config.dataWidth)))
    val fftTwiddleIndex = Output(
      Vec(config.lanes, UInt(config.twiddleIndexWidth.W))
    )
    val fftTwiddle = Input(
      Vec(config.lanes, new GaussTwiddle(config.twiddleWidth))
    )
    val untwistIndex = Output(Vec(config.lanes, UInt(config.logPoints.W)))
    val untwist = Input(
      Vec(config.lanes, new GaussTwiddle(config.twiddleWidth))
    )
    val outputValid = Output(Bool())
    val outputReady = Input(Bool())
    val coefficientLow = Output(Vec(config.lanes, SInt(config.dataWidth.W)))
    val coefficientHigh = Output(Vec(config.lanes, SInt(config.dataWidth.W)))
    val busy = Output(Bool())
    val done = Output(Bool())
  })

  val outputBeat = RegInit(0.U(beatWidth.W))
  val cyclic = Module(new WideFftCore(config))
  cyclic.io.inputValid := io.inputValid
  io.inputReady := cyclic.io.inputReady
  cyclic.io.input := io.input
  cyclic.io.twiddle := io.fftTwiddle
  io.fftTwiddleIndex := cyclic.io.twiddleIndex
  cyclic.io.outputReady := io.outputReady
  io.outputValid := cyclic.io.outputValid
  io.busy := cyclic.io.busy
  io.done := cyclic.io.done

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
    untwist.io.value := cyclic.io.output(lane)
    untwist.io.twiddle := io.untwist(lane)
    io.coefficientLow(lane) := untwist.io.result.real >> normalizeShift
    io.coefficientHigh(lane) := untwist.io.result.imag >> normalizeShift
  }

  when(cyclic.io.outputValid && io.outputReady) {
    when(outputBeat === (config.frameBeats - 1).U) {
      outputBeat := 0.U
    }.otherwise {
      outputBeat := outputBeat + 1.U
    }
  }
}

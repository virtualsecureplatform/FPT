package fpt

import chisel3._
import chisel3.util._

/** Coefficient-side contract used by the sequential CMUX engine. */
final class CmuxEngineCoefficientIO(val config: CmuxCoefficientConfig)
    extends Bundle {
  val loadStart = Input(Bool())
  val loadValid = Input(Bool())
  val loadReady = Output(Bool())
  val load = Input(
    Vec(
      config.components,
      Vec(config.inverseLanes, UInt(config.torusWidth.W))
    )
  )
  val loadDone = Output(Bool())

  val commandValid = Input(Bool())
  val commandReady = Output(Bool())
  val exponent = Input(UInt(config.exponentWidth.W))
  val transformStart = Output(Bool())
  val pairValid = Output(Bool())
  val pairReady = Input(Bool())
  val coefficientLow = Output(
    Vec(config.forwardLanes, SInt(config.forwardFormat.width.W))
  )
  val coefficientHigh = Output(
    Vec(config.forwardLanes, SInt(config.forwardFormat.width.W))
  )

  val updateStart = Input(Bool())
  val updateValid = Input(Bool())
  val updateReady = Output(Bool())
  val updateLow = Input(
    Vec(
      config.components,
      Vec(config.inverseLanes, SInt(config.inverseFormat.width.W))
    )
  )
  val updateHigh = Input(
    Vec(
      config.components,
      Vec(config.inverseLanes, SInt(config.inverseFormat.width.W))
    )
  )
  val updateDone = Output(Bool())

  val drainStart = Input(Bool())
  val drainValid = Output(Bool())
  val drainReady = Input(Bool())
  val drain = Output(
    Vec(
      config.components,
      Vec(config.inverseLanes, UInt(config.torusWidth.W))
    )
  )
  val drainDone = Output(Bool())

  val loaded = Output(Bool())
  val idle = Output(Bool())
}

abstract class CmuxEngineCoefficientFrontend(
    val coefficientConfig: CmuxCoefficientConfig
) extends Module {
  val io = IO(new CmuxEngineCoefficientIO(coefficientConfig))
}

/** Adapter that retains the original full-width barrel coefficient store. */
final class BarrelCmuxEngineCoefficientFrontend(
    config: CmuxCoefficientConfig
) extends CmuxEngineCoefficientFrontend(config) {
  private val rows = config.components * config.levels
  private val rowWidth = TransformUtil.counterWidth(rows)
  private val componentWidth = TransformUtil.counterWidth(config.components)
  private val levelWidth = TransformUtil.counterWidth(config.levels)

  val store = Module(new CmuxCoefficientStore(config))
  val row = RegInit(0.U(rowWidth.W))
  val exponentReg = RegInit(0.U(config.exponentWidth.W))
  val commandFire = io.commandValid && io.commandReady
  val finalRow = row === (rows - 1).U
  val launchFollowingRow = store.io.pairLast && !finalRow
  val followingRow = Mux(finalRow, 0.U, row + 1.U)
  val launchRow = Mux(commandFire, 0.U, followingRow)
  val rowComponents = VecInit(
    (0 until rows).map(index =>
      (index / config.levels).U(componentWidth.W)
    )
  )
  val rowLevels = VecInit(
    (0 until rows).map(index =>
      (index % config.levels).U(levelWidth.W)
    )
  )

  store.io.loadStart := io.loadStart
  store.io.loadValid := io.loadValid
  store.io.load := io.load
  io.loadReady := store.io.loadReady
  io.loadDone := store.io.loadDone

  io.commandReady := store.io.loaded && store.io.idle
  store.io.rowStart := commandFire || launchFollowingRow
  store.io.rowComponent := rowComponents(launchRow)
  store.io.rowLevel := rowLevels(launchRow)
  store.io.exponent := Mux(commandFire, io.exponent, exponentReg)
  store.io.pairReady := io.pairReady
  io.transformStart := commandFire || launchFollowingRow
  io.pairValid := store.io.pairValid
  io.coefficientLow := store.io.coefficientLow
  io.coefficientHigh := store.io.coefficientHigh

  store.io.updateStart := io.updateStart
  store.io.updateValid := io.updateValid
  store.io.updateLow := io.updateLow
  store.io.updateHigh := io.updateHigh
  io.updateReady := store.io.updateReady
  io.updateDone := store.io.updateDone

  store.io.drainStart := io.drainStart
  store.io.drainReady := io.drainReady
  io.drainValid := store.io.drainValid
  io.drain := store.io.drain
  io.drainDone := store.io.drainDone
  io.loaded := store.io.loaded
  io.idle := store.io.idle

  when(commandFire) {
    row := 0.U
    exponentReg := io.exponent
  }
  when(launchFollowingRow) {
    row := row + 1.U
  }
}

/** Adapter for the transposed, bitwise rotation/decomposition accumulator. */
final class BitwiseCmuxEngineCoefficientFrontend(
    config: CmuxCoefficientConfig,
    bitsPerCycle: Int
) extends CmuxEngineCoefficientFrontend(config) {
  val store = Module(new BitwiseCmuxForwardFrontend(config, bitsPerCycle))
  val commandFire = io.commandValid && io.commandReady

  store.io.loadStart := io.loadStart
  store.io.loadValid := io.loadValid
  store.io.load := io.load
  io.loadReady := store.io.loadReady
  io.loadDone := store.io.loadDone

  io.commandReady := store.io.loaded && store.io.idle && store.io.rotateReady
  store.io.rotateStart := commandFire
  store.io.exponent := io.exponent
  store.io.pairReady := io.pairReady
  io.transformStart := store.io.transformStart
  io.pairValid := store.io.pairValid
  io.coefficientLow := store.io.coefficientLow
  io.coefficientHigh := store.io.coefficientHigh

  store.io.updateStart := io.updateStart
  store.io.updateValid := io.updateValid
  store.io.updateLow := io.updateLow
  store.io.updateHigh := io.updateHigh
  io.updateReady := store.io.updateReady
  io.updateDone := store.io.updateDone

  store.io.drainStart := io.drainStart
  store.io.drainReady := io.drainReady
  io.drainValid := store.io.drainValid
  io.drain := store.io.drain
  io.drainDone := store.io.drainDone
  io.loaded := store.io.loaded
  io.idle := store.io.idle
}

package fpt

import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

private final class BatchedCmuxPendingQueueTimingHarness(
    requestEntries: Int = 4
) extends Module {
  val io = IO(new Bundle {
    val enqValid = Input(Bool())
    val enqData = Input(UInt(8.W))
    val enqReady = Output(Bool())
    val deqReady = Input(Bool())
    val deqValid = Output(Bool())
    val deqData = Output(UInt(8.W))
    val count = Output(UInt(3.W))
    val outputCount = Output(UInt(2.W))
    val inputBoundaryValid = Output(Bool())
    val outputBoundaryValid = Output(Bool())
    val outputEnqFire = Output(Bool())
    val outputDeqFire = Output(Bool())
    val queueDeqFire = Output(Bool())
    val shiftReady = Output(Bool())
    val enqFire = Output(Bool())
  })

  private val queue = Module(
    new BatchedCmuxPendingKeyRequestQueue(
      contextWidth = 2,
      rowWidth = 3,
      beatWidth = 2,
      inputLanes = 1,
      spectrumWidth = 8,
      requestEntries = requestEntries
    )
  )
  queue.io.enq.valid := io.enqValid
  queue.io.enq.bits := 0.U.asTypeOf(queue.io.enq.bits)
  queue.io.enq.bits.decomposition(0).real := io.enqData.asSInt
  io.enqReady := queue.io.enq.ready
  queue.io.deq.ready := io.deqReady
  io.deqValid := queue.io.deq.valid
  io.deqData := queue.io.deq.bits.decomposition(0).real.asUInt
  io.count := expose(queue.count)
  io.outputCount := expose(queue.outputCount)
  io.inputBoundaryValid := expose(queue.inputBoundaryValid)
  io.outputBoundaryValid := expose(queue.outputBoundaryValid)
  io.outputEnqFire := expose(queue.outputEnqFire)
  io.outputDeqFire := expose(queue.outputDeqFire)
  io.queueDeqFire := expose(queue.queueDeqFire)
  io.shiftReady := expose(queue.shiftReady)
  io.enqFire := expose(queue.enqFire)
}

final class BatchedCmuxEngineSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val coefficient = CmuxCoefficientConfig(
    polynomialSize = 32,
    forwardLanes = 4,
    inverseLanes = 2,
    components = 2,
    levels = 3,
    baseBits = 6,
    torusWidth = 32,
    forwardFormat = FixedFormat(18, 20),
    inverseFormat = FixedFormat(27, 14)
  )
  private val forward = TransformConfig(
    points = 16,
    lanes = 4,
    dataWidth = 38,
    twiddleWidth = 34,
    twiddleFractionalBits = 32
  )
  private val inverse = TransformConfig(
    points = 16,
    lanes = 2,
    dataWidth = 41,
    twiddleWidth = 37,
    twiddleFractionalBits = 35
  )
  private val external = ExternalProductConfig(
    points = 16,
    inputLanes = 4,
    outputLanes = 2,
    rows = 6,
    outputComponents = 2,
    spectrum = FixedFormat(18, 20),
    bootstrappingKey = FixedFormat(8, 24),
    accumulator = FixedFormat(27, 14)
  )

  private def generatedPath(name: String): String =
    Path
      .of("src", "test", "resources", "generated", name)
      .toAbsolutePath
      .normalize
      .toString

  private val engine = CmuxEngineConfig(
    coefficient,
    forward,
    inverse,
    external,
    inverseNormalizeShift = 4,
    forwardSGen = Some(
      SGenBackendConfig(
        "FptSGenForwardGuarded16x4",
        generatedPath("FptSGenForwardGuarded16x4.v"),
        integratedTangent = true
      )
    ),
    inverseSGen = Some(
      SGenBackendConfig(
        "FptSGenInverseGuarded16x2",
        generatedPath("FptSGenInverseGuarded16x2.v"),
        inputLeadCycles = 4,
        integratedTangent = true
      )
    )
  )
  private val registerConfig = BatchedCmuxEngineConfig(
    engine,
    batchContexts = 2
  )

  private def vectors(name: String): Seq[Array[BigInt]] = {
    val candidates = Seq(
      Path.of("..", "build", name),
      Path.of("..", "build-tfhepp", name),
      Path.of("build", name)
    )
    val path = candidates.find(Files.exists(_)).getOrElse(
      fail(s"Could not find $name; build the C++ RTL vectors first")
    )
    Files
      .readAllLines(path)
      .asScala
      .filter(_.trim.nonEmpty)
      .map(_.trim.split("\\s+").map(BigInt(_)))
      .toSeq
  }

  private def pokeTwiddle(target: GaussTwiddle, row: Array[BigInt]): Unit = {
    target.c.poke(row(0).S)
    target.cMinusD.poke(row(1).S)
    target.cPlusD.poke(row(2).S)
  }

  private def wrappedDifference(actual: BigInt, expected: BigInt): BigInt = {
    val modulus = BigInt(1) << coefficient.torusWidth
    val raw = (actual - expected) & (modulus - 1)
    val signed = if (raw.testBit(coefficient.torusWidth - 1)) {
      raw - modulus
    } else {
      raw
    }
    signed.abs
  }

  behavior of "the tagged batched CMUX engine"

  it should "keep a full pending queue's wide enables local" in {
    test(new BatchedCmuxPendingQueueTimingHarness) { dut =>
      dut.io.enqValid.poke(false.B)
      dut.io.enqData.poke(0.U)
      dut.io.deqReady.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      // The two output boundaries, four SRL entries, and input boundary hold
      // seven requests while the consumer is stalled.
      for (value <- 0 until 7) {
        dut.io.enqValid.poke(true.B)
        dut.io.enqData.poke(value.U)
        dut.io.enqReady.expect(true.B)
        dut.clock.step()
      }
      dut.io.enqValid.poke(false.B)
      dut.io.count.expect(4.U)
      dut.io.outputCount.expect(2.U)
      dut.io.inputBoundaryValid.expect(true.B)
      dut.io.outputBoundaryValid.expect(true.B)
      dut.io.shiftReady.expect(false.B)
      dut.io.enqFire.expect(false.B)
      dut.io.deqData.expect(0.U)

      // Releasing the consumer dequeues one output slot, but must not feed
      // that combinational readiness into either wide FIFO write enable or
      // the SRL clock enable.
      dut.io.deqReady.poke(true.B)
      dut.io.outputDeqFire.expect(true.B)
      dut.io.outputEnqFire.expect(false.B)
      dut.io.queueDeqFire.expect(false.B)
      dut.io.shiftReady.expect(false.B)
      dut.io.enqFire.expect(false.B)
      dut.clock.step()

      // The registered output occupancy exposes a slot on the next cycle.
      // Its resident second word keeps the output valid while the SRL refills
      // the other slot, preserving one dequeue per cycle.
      dut.io.outputCount.expect(1.U)
      dut.io.deqValid.expect(true.B)
      dut.io.deqData.expect(1.U)
      dut.io.outputEnqFire.expect(true.B)
      dut.io.outputDeqFire.expect(true.B)
      dut.io.queueDeqFire.expect(true.B)
      dut.clock.step()

      // The SRL occupancy change exposes its input slot one cycle later.
      dut.io.count.expect(3.U)
      dut.io.outputCount.expect(1.U)
      dut.io.inputBoundaryValid.expect(true.B)
      dut.io.shiftReady.expect(true.B)
      dut.io.enqFire.expect(true.B)

      // Drain the remaining ordered payloads at one word per cycle.
      for (value <- 2 until 7) {
        dut.io.deqValid.expect(true.B)
        dut.io.deqData.expect(value.U)
        dut.clock.step()
      }
      dut.io.deqValid.expect(false.B)
    }
  }

  it should "preserve full-rate draining through a one-entry pending slice" in {
    test(new BatchedCmuxPendingQueueTimingHarness(requestEntries = 1)) { dut =>
      dut.io.enqValid.poke(false.B)
      dut.io.enqData.poke(0.U)
      dut.io.deqReady.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      // The two output boundaries and input boundary hold three requests
      // while the consumer is stalled; the redundant middle slice is gone.
      for (value <- 0 until 3) {
        dut.io.enqValid.poke(true.B)
        dut.io.enqData.poke(value.U)
        dut.io.enqReady.expect(true.B)
        dut.clock.step()
      }
      dut.io.enqValid.poke(false.B)
      dut.io.count.expect(0.U)
      dut.io.outputCount.expect(2.U)
      dut.io.inputBoundaryValid.expect(true.B)
      dut.io.shiftReady.expect(false.B)
      dut.io.deqData.expect(0.U)

      // The conservative output FIFO first registers the freed slot.
      dut.io.deqReady.poke(true.B)
      dut.io.outputDeqFire.expect(true.B)
      dut.io.outputEnqFire.expect(false.B)
      dut.io.queueDeqFire.expect(false.B)
      dut.io.shiftReady.expect(false.B)
      dut.clock.step()

      // On the next cycle, the input boundary moves directly into the output
      // FIFO. The resident output word preserves adjacent-cycle draining.
      dut.io.outputEnqFire.expect(true.B)
      dut.io.outputDeqFire.expect(true.B)
      dut.io.queueDeqFire.expect(true.B)
      dut.io.shiftReady.expect(true.B)
      dut.io.enqFire.expect(true.B)
      for (value <- 1 until 3) {
        dut.io.deqValid.expect(true.B)
        dut.io.deqData.expect(value.U)
        dut.clock.step()
      }
      dut.io.deqValid.expect(false.B)
    }
  }

  private def exercise(
      config: BatchedCmuxEngineConfig,
      drainUsesPrefetch: Boolean
  ): Unit = {
    val rows = vectors("rtl_cmux_engine_vectors.txt")
    val initial = rows.take(coefficient.polynomialSize)
    val keyCount = external.rows * external.points
    val key = rows.slice(
      coefficient.polynomialSize,
      coefficient.polynomialSize + keyCount
    )
    val expected = rows.takeRight(coefficient.polynomialSize)
    val twiddles = vectors("rtl_cmux_engine_twiddles.txt")
    val forwardTwists = twiddles.slice(
      forward.points / 2,
      forward.points / 2 + forward.points
    )
    val inverseOffset = forward.points / 2 + forward.points
    val inverseUntwists = twiddles.drop(
      inverseOffset + inverse.points / 2
    )

    test(new BatchedCmuxEngine(config))
      .withAnnotations(
        Seq(
          VerilatorBackendAnnotation,
          VerilatorFlags(
            Seq(
              "--output-split",
              "99999999",
              "--output-split-cfuncs",
              "99999999"
            )
          )
        )
      ) { dut =>
        dut.io.loadStart.poke(false.B)
        dut.io.loadValid.poke(false.B)
        dut.io.commandValid.poke(false.B)
        dut.io.commandContext.poke(0.U)
        dut.io.exponent.poke(13.U)
        dut.io.drainStart.poke(false.B)
        dut.io.drainReady.poke(false.B)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        for (context <- 0 until config.batchContexts) {
          dut.io.loadContext.poke(context.U)
          dut.io.loadStart.poke(true.B)
          dut.clock.step()
          dut.io.loadStart.poke(false.B)
          dut.io.loadValid.poke(true.B)
          for (beat <- 0 until coefficient.polynomialBeats) {
            for (lane <- 0 until coefficient.inverseLanes) {
              val index = beat * coefficient.inverseLanes + lane
              for (component <- 0 until coefficient.components) {
                dut.io.load(component)(lane).poke(initial(index)(component).U)
              }
            }
            dut.clock.step()
          }
          dut.io.loadValid.poke(false.B)
          dut.io.loadDone.expect(true.B)
          dut.clock.step()
        }

        def driveReadOnlyInputs(): Unit = {
          for (lane <- 0 until forward.lanes) {
            val index = dut.io.forwardTwistIndex(lane).peek().litValue.toInt
            pokeTwiddle(dut.io.forwardTwist(lane), forwardTwists(index))
          }
          for (lane <- 0 until inverse.lanes) {
            val index = dut.io.inverseUntwistIndex(lane).peek().litValue.toInt
            pokeTwiddle(dut.io.inverseUntwist(lane), inverseUntwists(index))
          }
          val keyRow = dut.io.keyRow.peek().litValue.toInt
          for (lane <- 0 until external.inputLanes) {
            val point = dut.io.keyPoint(lane).peek().litValue.toInt
            val vector = key(keyRow * external.points + point)
            for (component <- 0 until external.outputComponents) {
              dut.io.bootstrappingKey(component)(lane).real.poke(
                vector(2 * component).S
              )
              dut.io.bootstrappingKey(component)(lane).imag.poke(
                vector(2 * component + 1).S
              )
            }
          }
        }

        var cycle = 0
        def step(): Unit = {
          driveReadOnlyInputs()
          dut.clock.step()
          cycle += 1
        }

        dut.io.commandContext.poke(0.U)
        dut.io.commandValid.poke(true.B)
        dut.io.commandReady.expect(true.B)
        step()
        dut.io.commandValid.poke(false.B)
        val firstAcceptance = 0
        dut.io.commandContext.poke(1.U)

        while (!dut.io.commandReady.peek().litToBoolean) {
          step()
          cycle should be <= config.commandInterval
        }
        val secondAcceptance = cycle
        secondAcceptance - firstAcceptance should be(config.commandInterval)
        dut.io.commandValid.poke(true.B)
        step()
        dut.io.commandValid.poke(false.B)

        val doneCycles = ArrayBuffer.empty[Int]
        val doneContexts = ArrayBuffer.empty[Int]
        while (doneContexts.size < config.batchContexts) {
          if (dut.io.doneValid.peek().litToBoolean) {
            doneCycles += cycle
            doneContexts += dut.io.doneContext.peek().litValue.toInt
          }
          step()
          cycle should be < 400
        }
        doneContexts.toSeq should be(Seq(0, 1))
        doneCycles(1) - doneCycles(0) should be(config.commandInterval)
        info(
          s"batched CMUX accepts/completes every ${config.commandInterval} " +
            s"cycles with first completion at cycle ${doneCycles.head}"
        )

        for (context <- 0 until config.batchContexts) {
          dut.io.drainContext.poke(context.U)
          dut.io.drainStart.poke(true.B)
          step()
          dut.io.drainStart.poke(false.B)
          dut.io.drainReady.poke(true.B)
          if (drainUsesPrefetch) {
            var drainWait = 0
            while (!dut.io.drainValid.peek().litToBoolean) {
              step()
              drainWait += 1
              drainWait should be <= coefficient.inverseBeats + 3
            }

            // The timing-cut register is elastic: a downstream stall must
            // preserve both valid and every lane of the buffered beat.
            val stalledBeat = Seq.tabulate(coefficient.components) {
              component =>
                Seq.tabulate(coefficient.inverseLanes) { lane =>
                  dut.io.drain(component)(lane).peek().litValue
                }
            }
            dut.io.drainReady.poke(false.B)
            step()
            dut.io.drainValid.expect(true.B)
            for (component <- 0 until coefficient.components) {
              for (lane <- 0 until coefficient.inverseLanes) {
                dut.io.drain(component)(lane).expect(
                  stalledBeat(component)(lane).U
                )
              }
            }
            dut.io.drainReady.poke(true.B)
          }
          var maximumError = BigInt(0)
          for (beat <- 0 until coefficient.polynomialBeats) {
            dut.io.drainValid.expect(true.B)
            for (lane <- 0 until coefficient.inverseLanes) {
              val index = beat * coefficient.inverseLanes + lane
              for (component <- 0 until coefficient.components) {
                val actual = dut.io.drain(component)(lane).peek().litValue
                maximumError = maximumError.max(
                  wrappedDifference(actual, expected(index)(component))
                )
              }
            }
            step()
          }
          maximumError should be <= (BigInt(1) << 18)
          dut.io.drainReady.poke(false.B)
          step()
        }
      }
  }

  it should "accept adjacent register contexts at the row-stream interval" in {
    exercise(registerConfig, drainUsesPrefetch = false)
  }

  it should "run the same CMUX through replicated accumulator banks" in {
    exercise(
      registerConfig.copy(
        coefficientStorage = BatchedCoefficientStorage.ReplicatedBanks
      ),
      drainUsesPrefetch = true
    )
  }

  it should "run the same CMUX through one precomputed window rotator" in {
    exercise(
      registerConfig.copy(
        engine = engine.copy(
          coefficient = coefficient.copy(windowedRotator = true)
        ),
        coefficientStorage =
          BatchedCoefficientStorage.PrecomputedWindowedReplicatedBanks
      ),
      drainUsesPrefetch = true
    )
  }

  it should "preserve the CMUX schedule through the forward SLR cut" in {
    exercise(
      registerConfig.copy(
        engine = engine.copy(
          coefficient = coefficient.copy(windowedRotator = true)
        ),
        coefficientStorage =
          BatchedCoefficientStorage.PrecomputedWindowedReplicatedBanks,
        registerForwardSlrInput = true
      ),
      drainUsesPrefetch = true
    )
  }
}

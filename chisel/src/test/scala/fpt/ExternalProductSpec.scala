package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

final class ExternalProductSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private val config = ExternalProductConfig(
    points = 16,
    inputLanes = 4,
    outputLanes = 2,
    rows = 6,
    outputComponents = 2,
    spectrum = FixedFormat(18, 20),
    bootstrappingKey = FixedFormat(8, 24),
    accumulator = FixedFormat(27, 14)
  )

  private def vectorPath(name: String): Path = {
    val candidates = Seq(
      Path.of("..", "build", name),
      Path.of("..", "build-tfhepp", name),
      Path.of("build", name)
    )
    candidates.find(Files.exists(_)).getOrElse(
      fail(s"Could not find $name; build the C++ RTL vectors first")
    )
  }

  private def vectors(name: String): Seq[Array[BigInt]] =
    Files
      .readAllLines(vectorPath(name))
      .asScala
      .filter(_.trim.nonEmpty)
      .map(_.trim.split("\\s+").map(BigInt(_)))
      .toSeq

  behavior of "the FPT External Product accumulator"

  it should "accumulate every key row and stream both TRLWE components" in {
    val rows = vectors("rtl_external_product_vectors.txt")
    rows.size should be(config.rows * config.points)

    test(new ExternalProductAccumulator(config)) { dut =>
      dut.io.start.poke(false.B)
      dut.io.inputValid.poke(false.B)
      dut.io.outputReady.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      for (row <- 0 until config.rows) {
        for (beat <- 0 until config.inputFrameBeats) {
          dut.io.inputReady.expect(true.B)
          dut.io.keyRow.expect(row.U)
          for (lane <- 0 until config.inputLanes) {
            val point = beat * config.inputLanes + lane
            val vector = rows(row * config.points + point)
            dut.io.pointIndex(lane).expect(point.U)
            dut.io.decomposition(lane).real.poke(vector(0).S)
            dut.io.decomposition(lane).imag.poke(vector(1).S)
            for (component <- 0 until config.outputComponents) {
              val offset = 2 + 2 * component
              dut.io.bootstrappingKey(component)(lane).real.poke(
                vector(offset).S
              )
              dut.io.bootstrappingKey(component)(lane).imag.poke(
                vector(offset + 1).S
              )
            }
          }
          dut.io.inputValid.poke(true.B)
          dut.clock.step()
        }
      }
      dut.io.inputValid.poke(false.B)
      dut.io.outputValid.expect(true.B)

      for (beat <- 0 until config.outputFrameBeats) {
        // Exercise the supported output backpressure without changing data.
        if (beat == 1) {
          dut.io.outputReady.poke(false.B)
          val heldReal = dut.io.output(0)(0).real.peek().litValue
          val heldImag = dut.io.output(0)(0).imag.peek().litValue
          dut.clock.step(2)
          dut.io.outputValid.expect(true.B)
          dut.io.output(0)(0).real.expect(heldReal.S)
          dut.io.output(0)(0).imag.expect(heldImag.S)
        }
        dut.io.outputReady.poke(true.B)
        for (lane <- 0 until config.outputLanes) {
          val point = beat * config.outputLanes + lane
          val vector = rows((config.rows - 1) * config.points + point)
          for (component <- 0 until config.outputComponents) {
            val offset = 2 + 2 * config.outputComponents + 2 * component
            withClue(
              s"beat=$beat point=$point component=$component real"
            ) {
              dut.io.output(component)(lane).real.expect(vector(offset).S)
            }
            dut.io.output(component)(lane).imag.expect(vector(offset + 1).S)
          }
        }
        dut.clock.step()
      }
      dut.io.done.expect(true.B)
      dut.io.busy.expect(false.B)
      dut.clock.step()
      dut.io.done.expect(false.B)
    }
  }

  it should "overlap the next accumulation with the previous PISO drain" in {
    val rows = vectors("rtl_external_product_vectors.txt")
    val transactionCount = 2

    test(
      new DoubleBufferedExternalProductAccumulator(
        config,
        tagWidth = 2,
        useSynchronousMemory = true
      )
    ) {
      dut =>
        dut.io.inputValid.poke(false.B)
        dut.io.inputFirst.poke(false.B)
        dut.io.inputTag.poke(0.U)
        dut.io.outputReady.poke(true.B)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        var outputTransaction = 0
        var outputBeat = 0
        var stalledCycles = 0

        def checkOutput(): Unit = {
          if (dut.io.outputValid.peek().litToBoolean) {
            val stall = outputTransaction == 0 && outputBeat == 1 &&
              stalledCycles < 2
            dut.io.outputReady.poke((!stall).B)
            if (stall) stalledCycles += 1
            dut.io.outputTag.expect(outputTransaction.U)
            dut.io.outputFirst.expect((outputBeat == 0).B)
            for (lane <- 0 until config.outputLanes) {
              val point = outputBeat * config.outputLanes + lane
              val vector = rows((config.rows - 1) * config.points + point)
              for (component <- 0 until config.outputComponents) {
                val offset = 2 + 2 * config.outputComponents + 2 * component
                dut.io.output(component)(lane).real.expect(vector(offset).S)
                dut.io.output(component)(lane).imag.expect(
                  vector(offset + 1).S
                )
              }
            }
            if (!stall) {
              outputBeat += 1
              if (outputBeat == config.outputFrameBeats) {
                outputBeat = 0
                outputTransaction += 1
              }
            }
          } else {
            dut.io.outputReady.poke(true.B)
          }
        }

        for (transaction <- 0 until transactionCount) {
          for (row <- 0 until config.rows) {
            for (beat <- 0 until config.inputFrameBeats) {
              val first = row == 0 && beat == 0
              dut.io.inputFirst.poke(first.B)
              dut.io.inputTag.poke(transaction.U)
              dut.io.inputReady.expect(true.B)
              dut.io.keyRow.expect(row.U)
              for (lane <- 0 until config.inputLanes) {
                val point = beat * config.inputLanes + lane
                val vector = rows(row * config.points + point)
                dut.io.pointIndex(lane).expect(point.U)
                dut.io.decomposition(lane).real.poke(vector(0).S)
                dut.io.decomposition(lane).imag.poke(vector(1).S)
                for (component <- 0 until config.outputComponents) {
                  val offset = 2 + 2 * component
                  dut.io.bootstrappingKey(component)(lane).real.poke(
                    vector(offset).S
                  )
                  dut.io.bootstrappingKey(component)(lane).imag.poke(
                    vector(offset + 1).S
                  )
                }
              }
              dut.io.inputValid.poke(true.B)
              checkOutput()
              dut.clock.step()
            }
          }
        }
        dut.io.inputValid.poke(false.B)
        dut.io.inputFirst.poke(false.B)

        var tailCycles = 0
        while (outputTransaction < transactionCount) {
          checkOutput()
          dut.clock.step()
          tailCycles += 1
          tailCycles should be < 2 * config.outputFrameBeats
        }
        stalledCycles should be(2)
        dut.io.busy.expect(false.B)
      }
  }

  it should "release a synchronous bank at serialized line rate" in {
    val lineRateConfig = config.copy(rows = 4)
    val rows = vectors("rtl_external_product_vectors.txt")
    val transactionCount = 3

    test(
      new DoubleBufferedExternalProductAccumulator(
        lineRateConfig,
        tagWidth = 2,
        serializeComponents = true,
        useSynchronousMemory = true
      )
    ) { dut =>
      dut.io.inputValid.poke(false.B)
      dut.io.inputFirst.poke(false.B)
      dut.io.inputTag.poke(0.U)
      dut.io.outputReady.poke(true.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      var outputTransaction = 0
      var outputComponent = 0
      var outputBeat = 0
      var previousOutputStart = false
      var inputStallCycles = 0

      def checkOutput(): Unit = {
        if (dut.io.outputValid.peek().litToBoolean) {
          dut.io.outputTag.expect(outputTransaction.U)
          dut.io.outputComponent.expect(outputComponent.U)
          dut.io.outputFirst.expect(
            (outputComponent == 0 && outputBeat == 0).B
          )
          dut.io.outputLast.expect(
            (outputComponent == lineRateConfig.outputComponents - 1 &&
              outputBeat == lineRateConfig.outputFrameBeats - 1).B
          )
          if (outputBeat == 0) previousOutputStart should be(true)
          for (lane <- 0 until lineRateConfig.outputLanes) {
            val point = outputBeat * lineRateConfig.outputLanes + lane
            val vector = rows((lineRateConfig.rows - 1) * config.points + point)
            val offset = 2 + 2 * config.outputComponents + 2 * outputComponent
            dut.io.output(outputComponent)(lane).real.expect(vector(offset).S)
            dut.io.output(outputComponent)(lane).imag.expect(
              vector(offset + 1).S
            )
          }
          outputBeat += 1
          if (outputBeat == lineRateConfig.outputFrameBeats) {
            outputBeat = 0
            outputComponent += 1
            if (outputComponent == lineRateConfig.outputComponents) {
              outputComponent = 0
              outputTransaction += 1
            }
          }
        }
        previousOutputStart = dut.io.outputStart.peek().litToBoolean
      }

      for (transaction <- 0 until transactionCount) {
        for (row <- 0 until lineRateConfig.rows) {
          for (beat <- 0 until lineRateConfig.inputFrameBeats) {
            dut.io.inputFirst.poke((row == 0 && beat == 0).B)
            dut.io.inputTag.poke(transaction.U)
            for (lane <- 0 until lineRateConfig.inputLanes) {
              val point = beat * lineRateConfig.inputLanes + lane
              val vector = rows(row * config.points + point)
              dut.io.decomposition(lane).real.poke(vector(0).S)
              dut.io.decomposition(lane).imag.poke(vector(1).S)
              for (component <- 0 until lineRateConfig.outputComponents) {
                val offset = 2 + 2 * component
                dut.io.bootstrappingKey(component)(lane).real.poke(
                  vector(offset).S
                )
                dut.io.bootstrappingKey(component)(lane).imag.poke(
                  vector(offset + 1).S
                )
              }
            }
            dut.io.inputValid.poke(true.B)
            while (!dut.io.inputReady.peek().litToBoolean) {
              inputStallCycles += 1
              checkOutput()
              dut.clock.step()
            }
            checkOutput()
            dut.clock.step()
          }
        }
      }
      dut.io.inputValid.poke(false.B)
      dut.io.inputFirst.poke(false.B)

      var tailCycles = 0
      while (outputTransaction < transactionCount) {
        checkOutput()
        dut.clock.step()
        tailCycles += 1
        tailCycles should be <= 2 * lineRateConfig.outputFrameBeats + 1
      }
      inputStallCycles should be(0)
      dut.io.busy.expect(false.B)
    }
  }

  it should "align pipelined U280 products with synchronous accumulation" in {
    val physicalConfig = ExternalProductConfig(
      points = 8,
      inputLanes = 2,
      outputLanes = 1,
      rows = 4,
      outputComponents = 2,
      spectrum = FixedFormat(18, 12),
      bootstrappingKey = FixedFormat(8, 19),
      accumulator = FixedFormat(27, 3),
      multiplier =
        ExternalProductMultiplier.ExactPipelinedSchoolbookDsp
    )
    val accumulatorWidth = physicalConfig.accumulator.width
    val accumulatorModulus = BigInt(1) << accumulatorWidth
    val accumulatorMask = accumulatorModulus - 1
    def wrapSigned(value: BigInt): BigInt = {
      val bits = value & accumulatorMask
      if (bits.testBit(accumulatorWidth - 1)) bits - accumulatorModulus
      else bits
    }
    val expected = Array.fill(physicalConfig.outputComponents,
      physicalConfig.points)((BigInt(0), BigInt(0)))

    test(
      new DoubleBufferedExternalProductAccumulator(
        physicalConfig,
        tagWidth = 2,
        useSynchronousMemory = true
      )
    ).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.io.inputValid.poke(false.B)
      dut.io.inputFirst.poke(false.B)
      dut.io.inputTag.poke(1.U)
      dut.io.outputReady.poke(true.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      for (row <- 0 until physicalConfig.rows) {
        for (beat <- 0 until physicalConfig.inputFrameBeats) {
          dut.io.inputFirst.poke((row == 0 && beat == 0).B)
          dut.io.inputReady.expect(true.B)
          for (lane <- 0 until physicalConfig.inputLanes) {
            val point = beat * physicalConfig.inputLanes + lane
            val aReal = BigInt(row * 101 + point * 17 - 211)
            val aImag = BigInt(row * 47 - point * 23 + 91)
            dut.io.decomposition(lane).real.poke(aReal.S)
            dut.io.decomposition(lane).imag.poke(aImag.S)
            for (component <- 0 until physicalConfig.outputComponents) {
              val bReal = BigInt(component * 73 + row * 13 - point * 5 + 19)
              val bImag = BigInt(component * -61 + row * 7 + point * 11 - 3)
              dut.io.bootstrappingKey(component)(lane).real.poke(bReal.S)
              dut.io.bootstrappingKey(component)(lane).imag.poke(bImag.S)
              val productReal = aReal * bReal - aImag * bImag
              val productImag = aReal * bImag + aImag * bReal
              val quantizedReal = wrapSigned(
                productReal >> physicalConfig.productShift
              )
              val quantizedImag = wrapSigned(
                productImag >> physicalConfig.productShift
              )
              val previous = expected(component)(point)
              expected(component)(point) =
                if (row == 0) (quantizedReal, quantizedImag)
                else (
                  wrapSigned(previous._1 + quantizedReal),
                  wrapSigned(previous._2 + quantizedImag)
                )
            }
          }
          dut.io.inputValid.poke(true.B)
          dut.clock.step()
        }
      }
      dut.io.inputValid.poke(false.B)
      dut.io.inputFirst.poke(false.B)

      var waitCycles = 0
      while (!dut.io.outputValid.peek().litToBoolean) {
        dut.clock.step()
        waitCycles += 1
        waitCycles should be <=
          PipelinedExactSchoolbookComplexMultiply.latency + 3
      }
      for (point <- 0 until physicalConfig.points) {
        dut.io.outputValid.expect(true.B)
        dut.io.outputTag.expect(1.U)
        for (component <- 0 until physicalConfig.outputComponents) {
          dut.io.output(component)(0).real.expect(
            expected(component)(point)._1.S
          )
          dut.io.output(component)(0).imag.expect(
            expected(component)(point)._2.S
          )
        }
        dut.clock.step()
      }
    }
  }

  /** Two identical accumulators, one with the physically registered drain.
    *
    * The registered variant must present exactly the reference stream one
    * cycle later on every drain pin while every accumulation-side and
    * bookkeeping pin stays cycle-identical, proving the pinned read-word
    * capture changes physical structure only.
    */
  private final class RegisteredDrainEquivalenceHarness(
      config: ExternalProductConfig,
      tagWidth: Int
  ) extends Module {
    private def instance(registeredRead: Boolean) = Module(
      new DoubleBufferedExternalProductAccumulator(
        config,
        tagWidth,
        serializeComponents = true,
        useSynchronousMemory = true,
        registeredReadOutput = registeredRead
      )
    )
    val reference = instance(false)
    val registered = instance(true)

    val io = IO(new Bundle {
      val inputValid = Input(Bool())
      val inputFirst = Input(Bool())
      val inputTag = Input(UInt(tagWidth.W))
      val decomposition = Input(
        Vec(config.inputLanes, new ComplexSInt(config.spectrum.width))
      )
      val bootstrappingKey = Input(
        Vec(
          config.outputComponents,
          Vec(
            config.inputLanes,
            new ComplexSInt(config.bootstrappingKey.width)
          )
        )
      )
      val inputReady = Output(Bool())
      val done = Output(Bool())
      val busy = Output(Bool())
    })

    for (dut <- Seq(reference, registered)) {
      dut.io.inputValid := io.inputValid
      dut.io.inputFirst := io.inputFirst
      dut.io.inputTag := io.inputTag
      dut.io.decomposition := io.decomposition
      dut.io.bootstrappingKey := io.bootstrappingKey
      dut.io.outputReady := true.B
    }
    io.inputReady := reference.io.inputReady
    io.done := reference.io.done
    io.busy := reference.io.busy

    chisel3.assert(
      registered.io.inputReady === reference.io.inputReady,
      "registered drain must not disturb accumulation readiness"
    )
    chisel3.assert(registered.io.keyRow === reference.io.keyRow)
    for (lane <- 0 until config.inputLanes) {
      chisel3.assert(registered.io.pointIndex(lane) === reference.io.pointIndex(lane))
    }
    chisel3.assert(registered.io.done === reference.io.done)
    when(reference.io.done) {
      chisel3.assert(registered.io.doneTag === reference.io.doneTag)
    }
    chisel3.assert(registered.io.busy === reference.io.busy)

    val delayedValid = RegNext(reference.io.outputValid, false.B)
    chisel3.assert(
      registered.io.outputValid === delayedValid,
      "registered drain valid must lag the reference by exactly one cycle"
    )
    chisel3.assert(
      registered.io.outputStart === RegNext(reference.io.outputStart, false.B)
    )
    val delayedFirst = RegNext(reference.io.outputFirst, false.B)
    val delayedLast = RegNext(reference.io.outputLast, false.B)
    val delayedComponent = RegNext(reference.io.outputComponent)
    val delayedTag = RegNext(reference.io.outputTag)
    val delayedOutput = RegNext(reference.io.output)
    when(delayedValid) {
      chisel3.assert(registered.io.outputFirst === delayedFirst)
      chisel3.assert(registered.io.outputLast === delayedLast)
      chisel3.assert(registered.io.outputComponent === delayedComponent)
      chisel3.assert(registered.io.outputTag === delayedTag)
      for (component <- 0 until config.outputComponents) {
        for (lane <- 0 until config.outputLanes) {
          chisel3.assert(
            registered.io.output(component)(lane).real ===
              delayedOutput(component)(lane).real,
            "registered drain data must equal the delayed reference"
          )
          chisel3.assert(
            registered.io.output(component)(lane).imag ===
              delayedOutput(component)(lane).imag,
            "registered drain data must equal the delayed reference"
          )
        }
      }
    }
  }

  it should "register the drain as a pure one-cycle delay" in {
    val physicalConfig = ExternalProductConfig(
      points = 8,
      inputLanes = 2,
      outputLanes = 1,
      rows = 4,
      outputComponents = 2,
      spectrum = FixedFormat(18, 12),
      bootstrappingKey = FixedFormat(8, 19),
      accumulator = FixedFormat(27, 3),
      multiplier =
        ExternalProductMultiplier.ExactPipelinedSchoolbookDsp
    )
    test(
      new RegisteredDrainEquivalenceHarness(physicalConfig, tagWidth = 2)
    ).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.io.inputValid.poke(false.B)
      dut.io.inputFirst.poke(false.B)
      dut.io.inputTag.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      var doneCount = 0
      var cycles = 0
      def stepChecked(): Unit = {
        if (dut.io.done.peek().litToBoolean) { doneCount += 1 }
        dut.clock.step()
        cycles += 1
        cycles should be <= 400
      }

      for (transaction <- 0 until 3) {
        for (row <- 0 until physicalConfig.rows) {
          for (beat <- 0 until physicalConfig.inputFrameBeats) {
            dut.io.inputValid.poke(true.B)
            dut.io.inputFirst.poke((row == 0 && beat == 0).B)
            dut.io.inputTag.poke((transaction % 4).U)
            for (lane <- 0 until physicalConfig.inputLanes) {
              val point = beat * physicalConfig.inputLanes + lane
              dut.io.decomposition(lane).real
                .poke((transaction * 37 + row * 101 + point * 17 - 211).S)
              dut.io.decomposition(lane).imag
                .poke((transaction * 29 + row * 47 - point * 23 + 91).S)
              for (component <- 0 until physicalConfig.outputComponents) {
                dut.io.bootstrappingKey(component)(lane).real
                  .poke((component * 73 + row * 13 - point * 5 + 19).S)
                dut.io.bootstrappingKey(component)(lane).imag
                  .poke((component * -61 + row * 7 + point * 11 - 3).S)
              }
            }
            while (!dut.io.inputReady.peek().litToBoolean) { stepChecked() }
            stepChecked()
          }
        }
        dut.io.inputValid.poke(false.B)
        dut.io.inputFirst.poke(false.B)
      }

      while (doneCount < 3) { stepChecked() }
      dut.clock.step(4)
      dut.io.busy.expect(false.B)
    }
  }
}

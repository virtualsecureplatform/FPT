package fpt

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** Opt-in nonzero numerical validation of the exact host-facing FPT
  * accelerator boundary for either supported arithmetic profile. The test
  * crosses raw-TLWE loading, corrected modulus switching, the narrow ping-pong
  * key loader, all generated tangent FTTs, the folded bitwise CMUX path, and
  * automatic sample extraction.
  */
final class PaperBufferedBlindRotateNumericalSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  private def requiredPath(environment: String, default: String): Path = {
    val path = Path
      .of(sys.env.getOrElse(environment, default))
      .toAbsolutePath
      .normalize
    require(Files.isRegularFile(path), s"missing $path")
    path
  }

  private def vectors(path: Path): Seq[Array[BigInt]] =
    Files
      .readAllLines(path)
      .asScala
      .filter(_.trim.nonEmpty)
      .map(_.trim.split("\\s+").map(BigInt(_)))
      .toSeq

  private def wrappedDifference(
      actual: BigInt,
      expected: BigInt,
      width: Int
  ): BigInt = {
    val modulus = BigInt(1) << width
    val raw = (actual - expected) & (modulus - 1)
    val signed = if (raw.testBit(width - 1)) raw - modulus else raw
    signed.abs
  }

  private def rtlProfile: FptRtlProfile = FptRtlProfile.named(
    sys.env.getOrElse("FPT_ARITHMETIC_PROFILE", "paper-set-ii")
  )

  behavior of "the physical buffered Blind Rotate accelerator"

  it should "bound a complete nonzero batch against the C++ oracle" in {
    if (!sys.env
          .get("FPT_PAPER_BUFFERED_BLIND_ROTATE_NUMERICS")
          .contains("1")) {
      cancel(
        "set FPT_PAPER_BUFFERED_BLIND_ROTATE_NUMERICS=1 to run the " +
          "physical Blind Rotate"
      )
    }

    val forwardPath = requiredPath(
      "FPT_SGEN_FORWARD",
      "../build/sgen-fpt/forward.v"
    )
    val inversePath = requiredPath(
      "FPT_SGEN_INVERSE",
      "../build/sgen-fpt/inverse.v"
    )
    val rows = vectors(
      requiredPath(
        "FPT_PAPER_BLIND_ROTATE_VECTORS",
        "../build/rtl_paper_blind_rotate_vectors.txt"
      )
    )

    val profile = rtlProfile
    val baseConfig = if (sys.env.get("FPT_U280_ROUTABLE_200_NUMERICS").contains("1")) {
      require(
        profile == PaperSetII,
        "the U280 routable numerical target supports paper-set-ii only"
      )
      PaperU280BufferedBarrelConfig(
        forwardPath.toString,
        inversePath.toString,
        domainDimension = 1,
        includeVerilogSource = true
      )
    } else {
      profile.bufferedBlindRotate(
        forwardPath.toString,
        inversePath.toString,
        domainDimension = 1,
        includeVerilogSource = true
      )
    }
    val config = baseConfig.copy(blindRotate = baseConfig.blindRotate.copy(
      preprocessedInput = sys.env.get("FPT_U280_PREPROCESSED_INPUT").contains("1"),
      bufferedOutput = sys.env.get("FPT_U280_BUFFERED_OUTPUT").contains("1")))
    val blind = config.blindRotate
    val coefficient = blind.cmux.engine.coefficient
    val external = blind.cmux.engine.externalProduct
    val contexts = blind.batchContexts
    val inputRows = rows.take(contexts)
    val keyRows = external.rows * external.points
    val key = rows.slice(contexts, contexts + keyRows)
    val expectedRows = rows.drop(contexts + keyRows)
    val outputsPerContext = coefficient.polynomialSize + 1
    val expected = expectedRows.map(_.head)

    contexts should be(profile.bitwiseBatchContexts)
    if (sys.env.get("FPT_U280_ROUTABLE_200_NUMERICS").contains("1")) {
      coefficient.forwardLanes should be(128)
      coefficient.inverseLanes should be(64)
      blind.cmux.commandInterval should be(16)
    }
    inputRows.size should be(contexts)
    inputRows.foreach(_.length should be(3))
    key.size should be(keyRows)
    key.foreach(_.length should be(2 * external.outputComponents))
    expectedRows.size should be(contexts * outputsPerContext)
    expectedRows.foreach(_.length should be(1))

    test(new NumericalBufferedBlindRotateHarness(config))
      .withAnnotations(
        Seq(
          VerilatorBackendAnnotation,
          PaperVerilator.flags
        )
      ) { dut =>
        // Always-ready runs intentionally hold inputs constant through the
        // entire output drain; retain the same finite bound as the loop.
        dut.clock.setTimeout(30000)
        dut.io.bufferedOutput.foreach(_.retire.poke(false.B))
        dut.io.preparedInput.foreach { p =>
          p.start.valid.poke(false.B); p.start.bits.poke(false.B)
          p.word.valid.poke(false.B); p.word.bits.poke(0.U)
          p.activate.valid.poke(false.B); p.activate.bits.poke(false.B)
          p.release.valid.poke(false.B); p.release.bits.poke(false.B)
        }
        dut.io.inputStart.poke(false.B)
        dut.io.inputContext.poke(0.U)
        dut.io.testVector.poke(0.U)
        dut.io.inputValid.poke(false.B)
        dut.io.inputCoefficient.poke(0.U)
        dut.io.keyLoadStart.poke(false.B)
        dut.io.keyLoadIndex.poke(0.U)
        dut.io.keyLoadValid.poke(false.B)
        dut.io.runStart.poke(false.B)
        dut.io.resultReady.poke(false.B)
        for (lane <- 0 until config.keyBuffer.loadLanes) {
          dut.io.keyLoad(lane).real.poke(0.S)
          dut.io.keyLoad(lane).imag.poke(0.S)
        }

        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        if (SGenFrameRecovery.configuredCycles > 0) dut.clock.step(SGenFrameRecovery.configuredCycles)

        dut.io.keyLoadIndex.poke(0.U)
        dut.io.keyLoadStartReady.expect(true.B)
        dut.io.keyLoadStart.poke(true.B)
        dut.clock.step()
        dut.io.keyLoadStart.poke(false.B)
        dut.io.keyLoadValid.poke(true.B)
        var initialKeyLoadCycles = 0
        for (
          word <- 0 until config.keyBuffer.wordsPerCoefficient;
          group <- 0 until config.keyBuffer.loadGroupsPerRead
        ) {
          val keyRow = word / external.inputFrameBeats
          val beat = word % external.inputFrameBeats
          for (loadLane <- 0 until config.keyBuffer.loadLanes) {
            val scalar = group * config.keyBuffer.loadLanes + loadLane
            val lane = scalar / external.outputComponents
            val component = scalar % external.outputComponents
            val point = beat * external.inputLanes + lane
            val vector = key(keyRow * external.points + point)
            dut.io.keyLoad(loadLane).real.poke(vector(2 * component).S)
            dut.io.keyLoad(loadLane).imag.poke(
              vector(2 * component + 1).S
            )
          }
          dut.io.keyLoadReady.expect(true.B)
          dut.clock.step()
          initialKeyLoadCycles += 1
        }
        dut.io.keyLoadValid.poke(false.B)
        if (config.keyBuffer.writeTileLanes > 0) {
          dut.io.keyLoadDone.expect(false.B)
          dut.clock.step()
          initialKeyLoadCycles += 1
        }
        dut.io.keyLoadDone.expect(true.B)
        dut.io.keyLoadDoneIndex.expect(0.U)
        info(s"initial key load: accepted=${config.keyBuffer.loadBeatsPerCoefficient} " +
          s"cycles=$initialKeyLoadCycles commitExtra=${initialKeyLoadCycles - config.keyBuffer.loadBeatsPerCoefficient}")
        dut.clock.step()

        if (blind.preprocessedInput) {
          val p = dut.io.preparedInput.get
          p.start.valid.poke(true.B); p.start.ready.expect(true.B)
          dut.clock.step(); p.start.valid.poke(false.B)
          for (word <- inputRows.flatMap(_.toSeq)) {
            p.word.valid.poke(true.B); p.word.bits.poke(word.U)
            p.word.ready.expect(true.B); dut.clock.step()
          }
          p.word.valid.poke(false.B)
          p.activate.valid.poke(true.B); p.activate.ready.expect(true.B)
          dut.clock.step(); p.activate.valid.poke(false.B)
          var waitCycles=0
          while(!p.initialized.peek().litToBoolean) {
            dut.clock.step(); waitCycles+=1
            waitCycles should be < contexts * (coefficient.polynomialBeats + 8) + 100
          }
          dut.clock.step()
          info(s"prepared batch accumulator initialization cycles=$waitCycles")
        } else for (context <- 0 until contexts) {
          val input = inputRows(context)
          dut.io.inputContext.poke(context.U)
          dut.io.testVector.poke(input(0).U)
          dut.io.inputStartReady.expect(true.B)
          dut.io.inputStart.poke(true.B)
          dut.clock.step()
          dut.io.inputStart.poke(false.B)
          dut.io.inputValid.poke(true.B)
          for (value <- input.drop(1)) {
            dut.io.inputReady.expect(true.B)
            dut.io.inputCoefficient.poke(value.U)
            dut.clock.step()
          }
          dut.io.inputValid.poke(false.B)
          var loadWait = 0
          while (!dut.io.inputDone.peek().litToBoolean) {
            dut.clock.step()
            loadWait += 1
            loadWait should be <= coefficient.polynomialBeats + 4
          }
          dut.io.inputDoneContext.expect(context.U)
          dut.clock.step()
        }

        dut.io.runReady.expect(true.B)
        dut.io.runStart.poke(true.B)
        dut.clock.step()
        dut.io.runStart.poke(false.B)

        val rawUnit = BigInt(1) << coefficient.torusShift
        val errors = scala.collection.mutable.Map.empty[Int, Int]
          .withDefaultValue(0)
        val observedContexts = ArrayBuffer.empty[Int]
        val observedResults = ArrayBuffer.empty[String]
        val resultDigest = java.security.MessageDigest.getInstance("SHA-256")
        var outputIndex = 0
        var exact = 0
        var withinOne = 0
        var maximumError = BigInt(0)
        var computeDoneCycle = Option.empty[Int]
        var firstResultCycle = Option.empty[Int]
        var blockedOutputCycles = 0
        var cycle = 0
        var finalDone = false
        val nextWords = if (blind.bufferedOutput) inputRows.reverse.flatMap(_.toSeq)
          else inputRows.flatMap(_.toSeq).map(x => x ^ BigInt(0x13579))
        var nextPreparationStarted = false
        var nextPreparedWords = 0
        var nextPreparedDone = false
        var captureCycle = Option.empty[Int]
        var nextActivated = false
        var nextInitialized = false
        var nextRunStarted = false
        var nextRunCycle = Option.empty[Int]
        var nextKeyStarted = false
        var nextKeyBeat = 0
        while (!finalDone) {
          dut.io.runStart.poke(false.B)
          dut.io.keyLoadStart.poke(false.B)
          dut.io.keyLoadValid.poke(false.B)
          if (blind.bufferedOutput && captureCycle.nonEmpty) {
            if (!nextKeyStarted && dut.io.keyLoadStartReady.peek().litToBoolean) {
              dut.io.keyLoadStart.poke(true.B); nextKeyStarted = true
            } else if (nextKeyStarted && nextKeyBeat < config.keyBuffer.loadBeatsPerCoefficient &&
                dut.io.keyLoadReady.peek().litToBoolean) {
              val word = nextKeyBeat / config.keyBuffer.loadGroupsPerRead
              val group = nextKeyBeat % config.keyBuffer.loadGroupsPerRead
              val keyRow = word / external.inputFrameBeats
              val beat = word % external.inputFrameBeats
              for (loadLane <- 0 until config.keyBuffer.loadLanes) {
                val scalar = group * config.keyBuffer.loadLanes + loadLane
                val lane = scalar / external.outputComponents
                val component = scalar % external.outputComponents
                val point = beat * external.inputLanes + lane
                val vector = key(keyRow * external.points + point)
                dut.io.keyLoad(loadLane).real.poke(vector(2 * component).S)
                dut.io.keyLoad(loadLane).imag.poke(vector(2 * component + 1).S)
              }
              dut.io.keyLoadValid.poke(true.B); nextKeyBeat += 1
            }
          }
          dut.io.preparedInput.foreach { p =>
            p.start.bits.poke(true.B)
            p.start.valid.poke((!nextPreparationStarted).B)
            if(!nextPreparationStarted && p.start.ready.peek().litToBoolean) {
              nextPreparationStarted = true
            }
            val valid = cycle > 0 && nextPreparedWords < nextWords.size
            p.word.valid.poke(valid.B)
            p.word.bits.poke(nextWords(math.min(nextPreparedWords, nextWords.size-1)).U)
            if(valid && p.word.ready.peek().litToBoolean) nextPreparedWords+=1
            if(p.prepared.valid.peek().litToBoolean) nextPreparedDone=true
            if (blind.bufferedOutput) {
              val captured = dut.io.bufferedOutput.get.captured.peek().litToBoolean
              p.release.valid.poke(captured.B); p.release.bits.poke(false.B)
              if (captured) captureCycle = Some(cycle)
              p.activate.bits.poke(true.B)
              val activate = captureCycle.nonEmpty && nextPreparedDone && !nextActivated
              p.activate.valid.poke(activate.B)
              if (activate && p.activate.ready.peek().litToBoolean) nextActivated = true
              if (p.initialized.peek().litToBoolean) nextInitialized = true
              if (nextInitialized && !nextRunStarted && dut.io.runReady.peek().litToBoolean) {
                dut.io.runStart.poke(true.B); nextRunStarted = true; nextRunCycle = Some(cycle)
              }
            }
          }
          val ready = (!blind.bufferedOutput || nextRunStarted) &&
            (sys.env.get("FPT_TEST_OUTPUT_ALWAYS_READY").contains("1") ||
              (cycle % 11 != 3 && cycle % 11 != 7))
          dut.io.resultReady.poke(ready.B)
          if (dut.io.computeDone.peek().litToBoolean && computeDoneCycle.isEmpty) {
            computeDoneCycle = Some(cycle)
          }
          val fire = dut.io.resultValid.peek().litToBoolean && ready
          if (dut.io.resultValid.peek().litToBoolean && !ready) blockedOutputCycles += 1
          if (fire) {
            if (firstResultCycle.isEmpty) firstResultCycle = Some(cycle)
            val beatCount = dut.io.resultCount.peek().litValue.toInt
            beatCount should be >= 1
            beatCount should be <= blind.sampleExtractLanes
            for (lane <- 0 until beatCount) {
            outputIndex should be < expected.size
            val actual = dut.io.result(lane).peek().litValue
            observedResults += actual.toString
            for (byte <- 0 until (coefficient.torusWidth + 7) / 8) {
              resultDigest.update(((actual >> (8 * byte)) & 255).toByte)
            }
            val error = wrappedDifference(
              actual,
              expected(outputIndex),
              coefficient.torusWidth
            )
            maximumError = maximumError.max(error)
            if (error == 0) exact += 1
            if (error <= rawUnit) withinOne += 1
            val errorBin = (error / rawUnit).toInt
            errors(errorBin) = errors(errorBin) + 1
            val context = outputIndex / outputsPerContext
            dut.io.resultContext.expect(context.U)
            observedContexts += dut.io.resultContext.peek().litValue.toInt
            val expectedLast = outputIndex + 1 == expected.size
            outputIndex += 1
            finalDone = expectedLast
            }
            dut.io.resultLast.expect(finalDone.B)
            dut.io.done.expect(finalDone.B)
          }
          if (!finalDone) dut.io.active.expect(true.B)
          dut.clock.step()
          cycle += 1
          assert(cycle < 30000, s"batch timeout capture=$captureCycle prepared=$nextPreparedDone activated=$nextActivated initialized=$nextInitialized keyBeats=$nextKeyBeat nextRun=$nextRunStarted outputs=$outputIndex")
        }

        outputIndex should be(expected.size)
        if (blind.preprocessedInput) {
          nextPreparedWords should be(nextWords.size)
          nextPreparedDone should be(true)
          info("PREPARED_INPUT_OVERLAP_NUMERICAL_PASS")
        }
        observedContexts.toSeq should be(
          (0 until contexts).flatMap(context =>
            Seq.fill(outputsPerContext)(context)
          )
        )
        computeDoneCycle should not be empty
        firstResultCycle.get should be > computeDoneCycle.get
        dut.io.bufferedOutput.foreach { p =>
          dut.io.active.expect(true.B)
          p.retire.poke(true.B); dut.clock.step(); p.retire.poke(false.B)
          nextRunStarted should be(true)
          val captureCycles = captureCycle.get - computeDoneCycle.get
          captureCycles should be <= 1152
          val handoffCycles = nextRunCycle.get - computeDoneCycle.get
          handoffCycles should be <= 1601 // 163100 target II minus 161499 compute cycles.
          val secondExpected = expected.grouped(outputsPerContext).toSeq.reverse.flatten
          var secondIndex = 0
          var secondCycles = 0
          dut.io.runStart.poke(false.B)
          dut.io.preparedInput.get.activate.valid.poke(false.B)
          dut.io.preparedInput.get.release.valid.poke(false.B)
          while (secondIndex < secondExpected.size && secondCycles < 30000) {
            val ready = secondCycles % 5 != 2
            dut.io.resultReady.poke(ready.B)
            if (ready && dut.io.resultValid.peek().litToBoolean) {
              val count = dut.io.resultCount.peek().litValue.toInt
              dut.io.resultContext.expect((secondIndex / outputsPerContext).U)
              for (lane <- 0 until count) {
                // Compare to the first invocation's bit-exact RTL outputs,
                // permuted by context, rather than the approximate C++ oracle.
                val reversedContext = contexts - 1 - secondIndex / outputsPerContext
                val index = reversedContext * outputsPerContext + secondIndex % outputsPerContext
                dut.io.result(lane).expect(BigInt(observedResults(index)).U)
                secondIndex += 1
              }
              dut.io.resultLast.expect((secondIndex == secondExpected.size).B)
            }
            dut.clock.step(); secondCycles += 1
          }
          secondIndex should be(secondExpected.size)
          p.retire.poke(true.B); dut.clock.step(); p.retire.poke(false.B)
          info(s"BUFFERED_OUTPUT_OVERLAP_NUMERICAL_PASS capture_cycles=$captureCycles handoff_cycles=$handoffCycles second_words=$secondIndex next_run_before_output=true")
        }
        dut.io.active.expect(false.B)
        dut.io.runReady.expect(false.B)
        val histogram = errors.toSeq.sortBy(_._1).mkString("[", ",", "]")
        val digest = resultDigest.digest().map(byte => f"${byte & 255}%02x").mkString
        info(s"batch result SHA256=$digest")
        sys.env.get("FPT_EXPECTED_BLIND_ROTATE_SHA256").foreach { expectedDigest =>
          digest should be(expectedDigest)
        }
        info(s"batch schedule: computeDone=${computeDoneCycle.get}, firstResult=${firstResultCycle.get}, completed=$cycle, blockedOutputCycles=$blockedOutputCycles")
        sys.env.get("FPT_EXPECTED_BLIND_ROTATE_CYCLES").foreach { expectedCycles =>
          cycle should be(expectedCycles.toInt)
        }
        sys.env.get("FPT_MAX_BLIND_ROTATE_CYCLES").foreach { maximumCycles =>
          cycle should be <= maximumCycles.toInt
        }
        info(
          s"physical ${profile.profileName} Blind Rotate: cycles=$cycle, " +
            s"maximum Torus error=$maximumError " +
            s"(${maximumError / rawUnit} inverse raw units), " +
            s"exact=$exact/${expected.size}, " +
            s"within one inverse raw unit=$withinOne/${expected.size}, " +
            s"error histogram=$histogram"
        )
        val maximumRawError = if (profile eq PaperSetII) 4 else 8
        maximumError should be <= maximumRawError * rawUnit
        exact * 4 should be > expected.size
        withinOne * 3 should be > expected.size * 2
        sys.env.get("FPT_BLIND_ROTATE_RESULT_FILE").foreach { output =>
          Files.write(Path.of(output), observedResults.asJava)
        }
      }
  }
}

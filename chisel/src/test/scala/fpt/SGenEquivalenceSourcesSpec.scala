package fpt

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class SGenEquivalenceSourcesSpec extends AnyFunSuite {
  def rtl(top: String, latency: Int): String = s"""// latency of $latency cycles
module ${top}Front(input clk); endmodule
module $top(input clk); ${top}Front front(.clk(clk)); endmodule
"""
  for (top <- Seq("FptSGenForward", "FptSGenReference")) {
    test(s"namespace $top and every helper/instance without changing metadata") {
      val result = SGenEquivalenceSources.namespace(rtl(top, 56), "UniqueReference")
      assert(result.contains("latency of 56 cycles"))
      assert(result.contains("module UniqueReference(input clk)"))
      assert(result.contains(s"module UniqueReference_${top}Front"))
      assert(result.contains(s"UniqueReference_${top}Front front(.clk(clk))"))
      assert(!result.contains(s"module $top("))
    }
  }
  test("same-basename inputs remain separate even with stale reference artifacts") {
    val root = Files.createTempDirectory("fpt-equivalence-sources-")
    val a = Files.createDirectory(root.resolve("a")).resolve("forward.v")
    val b = Files.createDirectory(root.resolve("b")).resolve("forward.v")
    Files.writeString(a, rtl("FptSGenForward", 56))
    Files.writeString(b, rtl("FptSGenForward", 72))
    val staging = Files.createDirectory(root.resolve("staged"))
    val stale = staging.resolve("FptSGenReference.v")
    Files.writeString(stale, rtl("FptSGenReference", 99))
    val (reference, candidate) = SGenEquivalenceSources.stage(a, b, staging)
    assert(reference.getFileName != candidate.getFileName)
    assert(Files.readString(reference).contains("latency of 56 cycles"))
    assert(Files.readString(candidate).contains("latency of 72 cycles"))
    assert(Files.readString(stale).contains("latency of 99 cycles"))
  }
  test("missing or ambiguous forward tops fail before simulation") {
    intercept[IllegalArgumentException] { SGenEquivalenceSources.namespace("module wrong; endmodule", "Unique") }
    intercept[IllegalArgumentException] {
      SGenEquivalenceSources.namespace(rtl("FptSGenForward", 56) + rtl("FptSGenReference", 72), "Unique")
    }
  }
}

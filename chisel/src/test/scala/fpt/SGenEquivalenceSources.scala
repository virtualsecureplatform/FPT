package fpt

import java.nio.file.{Files, Path}

/** ChiselTest stages BlackBox sources by basename. Never pass two forward.v
  * files or rely on an old module being discoverable in the simulator directory.
  */
private[fpt] object SGenEquivalenceSources {
  private val modulePattern = "\\bmodule\\s+(\\w+)".r

  def namespace(source: String, top: String): String = {
    val modules = modulePattern.findAllMatchIn(source).map(_.group(1)).toSeq.distinct
    val original = modules.filter(Set("FptSGenForward", "FptSGenReference"))
    require(original.size == 1, s"expected exactly one supported forward top, found $original")
    val names = modules.map(name => name ->
      (if (name == original.head) top else s"${top}_$name")).toMap
    val pattern = ("\\b(?:" + modules.map(java.util.regex.Pattern.quote).mkString("|") + ")\\b").r
    // One simultaneous replacement handles overlapping top/helper names safely.
    pattern.replaceAllIn(source, m => java.util.regex.Matcher.quoteReplacement(names(m.matched)))
  }

  def stage(reference: Path, candidate: Path, directory: Path): (Path, Path) = {
    // Read both before writing, including when a caller supplies identical paths.
    val referenceText = Files.readString(reference)
    val candidateText = Files.readString(candidate)
    Files.createDirectories(directory)
    val old = directory.resolve("FptBankedShuffleReference.v").toAbsolutePath
    val fresh = directory.resolve("FptBankedShuffleCandidate.v").toAbsolutePath
    Files.writeString(old, namespace(referenceText, "FptBankedShuffleReference"))
    Files.writeString(fresh, namespace(candidateText, "FptBankedShuffleCandidate"))
    (old, fresh)
  }
}

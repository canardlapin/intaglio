import sbt._
import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.lib.MiMaLib
import java.util.zip.ZipFile
import scala.collection.JavaConverters._

/** Exact, version-scoped review of additions, solely for forward MiMa's source approximation. */
object InteractionCompatibility {
  final case class Review(sha: String, version: String, entries: Vector[(String, String)]) {
    val filters: Seq[ProblemFilter] = entries.map {
      case ("MissingClassProblem", name)        => ProblemFilters.exclude[MissingClassProblem](name)
      case ("DirectMissingMethodProblem", name) =>
        ProblemFilters.exclude[DirectMissingMethodProblem](name)
      // A new `enum` case is a class, a companion, and a static field on the enum's
      // object. Without this kind no case can be added under review.
      case ("MissingFieldProblem", name) => ProblemFilters.exclude[MissingFieldProblem](name)
      case (kind, _)                     => sys.error(s"Unsupported additive review problem: $kind")
    }
    def keeps(problem: Problem): Boolean = filters.forall(_(problem))
  }

  def read(file: File, baseline: File): Review = {
    def lines(path: File) =
      IO.readLines(path).map(_.trim).filter(s => s.nonEmpty && !s.startsWith("#"))
    val source = lines(file)
    val metadata = source
      .filter(_.contains("="))
      .map { line =>
        val parts = line.split("=", 2)
        parts(0) -> parts(1)
      }
      .toMap
    val pinned = lines(baseline).map { line =>
      val parts = line.split("=", 2)
      parts(0) -> parts(1)
    }.toMap
    require(
      metadata == pinned,
      "Additive API review must match the exact pinned compatibility baseline"
    )
    val entries = source
      .filterNot(_.contains("="))
      .map { line =>
        val parts = line.split(" ", 2)
        require(
          parts.length == 2 && !parts(1).exists(c => c == '*' || c == '?'),
          s"Expected exact problem and symbol: $line"
        )
        parts(0) -> parts(1)
      }
      .toVector
    require(entries.distinct.size == entries.size, "Duplicate additive API review entries")
    Review(metadata("sha"), metadata("version"), entries)
  }

  def validateArtifacts(review: Review, previous: Map[ModuleID, File], current: File): Unit = {
    val currentClasses = classNames(current)
    previous.foreach { case (module, artifact) =>
      require(
        module.revision == review.version,
        "Additive API review cannot apply to another baseline version"
      )
      val oldClasses = classNames(artifact)
      review.entries.collect { case ("MissingClassProblem", name) => name }.foreach { name =>
        require(!oldClasses(name), s"Reviewed addition already exists in the baseline: $name")
        require(currentClasses(name), s"Reviewed addition is absent from current artifact: $name")
      }
    }
  }

  /** A stale review fails instead of silently accepting filters that no longer match the artifact.
    */
  def validateFindings(review: Review, forward: List[Problem]): Unit = {
    val names = forward.map(p => p.getClass.getSimpleName -> p.matchName.get).toSet
    review.entries.foreach { entry =>
      require(names(entry), s"Reviewed addition is not an actual forward finding: $entry")
    }
  }

  /** Real class-file calibration: unreviewed additions and a removed legacy method still report. */
  def calibrate(review: Review): Unit = IO.withTemporaryDirectory { directory =>
    def compile(name: String, sources: Map[String, String]): File = {
      val out = directory / name / "classes"
      IO.createDirectory(out)
      val files = sources.toVector.map { case (path, code) =>
        val file = directory / name / "src" / path
        IO.write(file, code)
        file.getAbsolutePath
      }
      val compiler = javax.tools.ToolProvider.getSystemJavaCompiler
      require(compiler != null, "Compatibility calibration requires a JDK")
      val exit = compiler.run(
        null,
        null,
        null,
        (Vector("--release", "8", "-d", out.getAbsolutePath) ++ files): _*
      )
      require(exit == 0, "Could not compile compatibility calibration fixture")
      out
    }
    val before = compile(
      "before",
      Map(
        "intaglio/Plot.java" -> "package intaglio; public final class Plot { public void legacy() {} }",
        "intaglio/GraphicsError.java" -> "package intaglio; public final class GraphicsError { public static final int EmptyPalette = 0; }"
      )
    )
    val after = compile(
      "after",
      Map(
        "intaglio/Plot.java" -> "package intaglio; public final class Plot { public void addPackagedLayer() {} public void unexpected() {} }",
        "intaglio/GraphicsError.java" -> "package intaglio; public final class GraphicsError { public static final int EmptyPalette = 0; public static final int DegenerateDivergingPalette = 1; public static final int UnreviewedCase = 2; }",
        "intaglio/interaction/DataRevision.java" -> "package intaglio.interaction; public final class DataRevision {}",
        "intaglio/interaction/Unreviewed.java" -> "package intaglio.interaction; public final class Unreviewed {}"
      )
    )
    val mima = new MiMaLib(Seq.empty)
    val backward = mima.collectProblems(before, after, Nil)
    val forward = mima.collectProblems(after, before, Nil)
    val remaining = forward.filter(review.keeps)
    require(
      backward.exists(_.matchName.contains("intaglio.Plot.legacy")),
      "Removed legacy method was not detected"
    )
    require(
      remaining.exists(_.matchName.contains("intaglio.Plot.unexpected")),
      "Unreviewed method addition was suppressed"
    )
    require(
      remaining.exists(_.matchName.contains("intaglio.interaction.Unreviewed")),
      "Unreviewed class addition was suppressed"
    )
    require(
      forward.exists(_.matchName.contains("intaglio.Plot.addPackagedLayer")),
      "Expected method addition was not detected"
    )
    require(
      forward.exists(_.matchName.contains("intaglio.interaction.DataRevision")),
      "Expected class addition was not detected"
    )
    require(
      !remaining.exists(_.matchName.contains("intaglio.Plot.addPackagedLayer")),
      "Reviewed method addition was not recognized"
    )
    require(
      !remaining.exists(_.matchName.contains("intaglio.interaction.DataRevision")),
      "Reviewed class addition was not recognized"
    )
    require(
      remaining.exists(_.matchName.contains("intaglio.GraphicsError.UnreviewedCase")),
      "Unreviewed field addition was suppressed"
    )
    require(
      forward.exists(_.matchName.contains("intaglio.GraphicsError.DegenerateDivergingPalette")),
      "Expected field addition was not detected"
    )
    require(
      !remaining.exists(_.matchName.contains("intaglio.GraphicsError.DegenerateDivergingPalette")),
      "Reviewed field addition was not recognized"
    )
  }

  private def classNames(file: File): Set[String] = {
    def className(path: String) = path.stripSuffix(".class").replace('/', '.')
    if (file.isDirectory) {
      (file ** "*.class").get.map(path => className(IO.relativize(file, path).get)).toSet
    } else {
      val zip = new ZipFile(file)
      try
        zip
          .entries()
          .asScala
          .filter(_.getName.endsWith(".class"))
          .map(e => className(e.getName))
          .toSet
      finally zip.close()
    }
  }
}

package intaglio

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Guards the boundary that keeps Intaglio self-contained and portable: every
  * production package lives under `intaglio`, the core stays platform-neutral,
  * and each backend depends only on the core. These are the properties that
  * let the library be consumed a module at a time — a portable consumer must
  * never acquire a platform renderer or toolkit transitively.
  */
class ModuleBoundarySuite extends munit.FunSuite:
  private val modules = Vector("core", "svg", "canvas", "java2d", "javafx")

  private lazy val root: Path =
    var candidate = Path.of(sys.props("user.dir")).toAbsolutePath.normalize
    while candidate != null && !Files.isRegularFile(candidate.resolve("build.sbt")) do
      candidate = candidate.getParent
    if candidate == null then fail("could not locate the repository root from user.dir")
    candidate

  test("production packages stay under the intaglio namespace") {
    val violations = productionSources.flatMap { path =>
      val declaration = Files
        .readAllLines(path)
        .asScala
        .iterator
        .map(_.trim)
        .find(_.startsWith("package "))
      declaration match
        case Some(value) if value == "package intaglio" || value.startsWith("package intaglio.") =>
          None
        case other => Some(s"${root.relativize(path)}: ${other.getOrElse("missing package declaration")}")
    }

    assertEquals(violations, Vector.empty)
  }

  test("the core stays platform-neutral") {
    // The core is the portable kernel: it compiles unchanged for the JVM and
    // Scala.js, so a platform import here would silently break one of them.
    // Backends are where `java.*` and `scala.scalajs.*` belong.
    val forbidden = """(?m)^\s*import\s+(java\.|javax\.|scala\.scalajs\.)""".r
    val coreMain = root.resolve("modules").resolve("core")
    val violations = productionSources
      .filter(_.startsWith(coreMain))
      .flatMap { path =>
        forbidden.findAllMatchIn(Files.readString(path)).map { found =>
          s"${root.relativize(path)}: ${found.matched.trim}"
        }
      }

    assertEquals(violations, Vector.empty)
  }

  test("core is dependency-free and every backend depends only on core") {
    val build = Files.readString(root.resolve("build.sbt"))
    val coreBlock = projectBlock(build, "core", "coreJS")
    assertEquals(dependencies(coreBlock), Vector.empty)
    assert(coreBlock.contains("crossProject(JSPlatform, JVMPlatform)"))

    val backends = Vector(
      ("svg", "svgJS", "crossProject(JSPlatform, JVMPlatform)"),
      ("canvas", "canvasJS", "crossProject(JSPlatform)"),
      ("java2d", "java2dJVM", "crossProject(JVMPlatform)"),
      ("javafx", "javafxJVM", "crossProject(JVMPlatform)")
    )
    backends.foreach { case (name, end, platform) =>
      val block = projectBlock(build, name, end)
      assertEquals(dependencies(block), Vector("core"), clues(name))
      assert(block.contains(platform), clues(name, platform))
    }
  }

  test("only the JavaFX backend carries a toolkit dependency") {
    val build = Files.readString(root.resolve("build.sbt"))
    Vector(
      ("core", "coreJS"),
      ("svg", "svgJS"),
      ("canvas", "canvasJS"),
      ("java2d", "java2dJVM")
    ).foreach { case (name, end) =>
      assert(!projectBlock(build, name, end).contains("openjfx"), clues(name))
    }
    val javafxBlock = projectBlock(build, "javafx", "javafxJVM")
    assert(javafxBlock.contains("openjfx"))
    assert(javafxBlock.contains("Provided"), "OpenJFX must stay Provided")
  }

  private def productionSources: Vector[Path] =
    modules.flatMap { module =>
      val moduleRoot = root.resolve("modules").resolve(module)
      if !Files.isDirectory(moduleRoot) then Vector.empty
      else
        val stream = Files.walk(moduleRoot)
        try
          stream.iterator.asScala
            .filter(path => Files.isRegularFile(path))
            .filter(_.toString.endsWith(".scala"))
            .filter(_.iterator.asScala.exists(_.toString == "main"))
            .toVector
        finally stream.close()
    }

  private def projectBlock(build: String, start: String, end: String): String =
    val from = build.indexOf(s"lazy val $start =")
    val until = build.indexOf(s"lazy val $end", from + 1)
    if from < 0 || until < 0 then fail(s"could not locate build block $start .. $end")
    build.substring(from, until)

  private def dependencies(block: String): Vector[String] =
    raw"\.dependsOn\(([^)]*)\)".r
      .findAllMatchIn(block)
      .flatMap(_.group(1).split(',').iterator.map(_.trim).filter(_.nonEmpty))
      .toVector

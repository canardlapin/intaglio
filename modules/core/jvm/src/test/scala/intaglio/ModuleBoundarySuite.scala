package intaglio

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Guards the boundary that keeps Intaglio self-contained and portable: every production package
  * lives under `intaglio`, the core stays platform-neutral, and backends depend only on core plus
  * their explicitly allowed shared support modules. These properties allow consumption a module at
  * a time — a portable consumer must never acquire a platform renderer or toolkit transitively.
  */
class ModuleBoundarySuite extends munit.FunSuite:
  private val modules =
    Vector("core", "jvm-text", "interaction", "laws", "svg", "canvas", "java2d", "pdf", "javafx")

  private lazy val root: Path =
    // A source consumer's non-forked tests share its user.dir. The suite's own
    // class location belongs to Intaglio, even when sbt was launched elsewhere.
    val location = Path.of(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    var candidate = location.toAbsolutePath.normalize
    while candidate != null && !Files.isRegularFile(candidate.resolve("build.sbt")) do
      candidate = candidate.getParent
    if candidate == null then
      fail("could not locate the owning source repository from the test class location")
    candidate

  test("the boundary source root owns this suite independently of the consumer working directory") {
    assert(
      Files.isRegularFile(
        root.resolve(
          "modules/core/jvm/src/test/scala/intaglio/ModuleBoundarySuite.scala"
        )
      )
    )
    assert(Files.readString(root.resolve("build.sbt")).contains("name := \"intaglio-core\""))
  }

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
        case other =>
          Some(s"${root.relativize(path)}: ${other.getOrElse("missing package declaration")}")
    }

    assertEquals(violations, Vector.empty)
  }

  test("the core and interaction runtime stay platform-neutral") {
    // The core is the portable kernel: it compiles unchanged for the JVM and
    // Scala.js, so a platform import here would silently break one of them.
    // Backends are where `java.*` and `scala.scalajs.*` belong.
    val forbidden = """(?m)^\s*import\s+(java\.|javax\.|scala\.scalajs\.)""".r
    val portable = Vector("core", "interaction").map(root.resolve("modules").resolve(_))
    val violations = productionSources
      .filter(path => portable.exists(path.startsWith))
      .flatMap { path =>
        forbidden.findAllMatchIn(Files.readString(path)).map { found =>
          s"${root.relativize(path)}: ${found.matched.trim}"
        }
      }

    assertEquals(violations, Vector.empty)
  }

  test("core is dependency-free; backend dependencies are deliberate") {
    val build = Files.readString(root.resolve("build.sbt"))
    val coreBlock = projectBlock(build, "core", "coreJS")
    assertEquals(dependencies(coreBlock), Vector.empty)
    assert(coreBlock.contains("crossProject(JSPlatform, JVMPlatform)"))

    val jvmTextBlock = projectBlock(build, "jvmText", "jvmTextJVM")
    assertEquals(dependencies(jvmTextBlock), Vector("core"))
    assert(jvmTextBlock.contains("crossProject(JVMPlatform)"))

    val backends = Vector(
      ("svg", "svgJS", "crossProject(JSPlatform, JVMPlatform)"),
      ("canvas", "canvasJS", "crossProject(JSPlatform)"),
      ("java2d", "java2dJVM", "crossProject(JVMPlatform)"),
      ("pdf", "pdfJVM", "crossProject(JVMPlatform)"),
      ("javafx", "javafxJVM", "crossProject(JVMPlatform)")
    )
    backends.foreach { case (name, end, platform) =>
      val block = projectBlock(build, name, end)
      val expected = name match
        case "javafx"         => Vector("core", "interaction")
        case "java2d" | "pdf" => Vector("core", "jvmText")
        case _                => Vector("core")
      assertEquals(dependencies(block), expected, clues(name))
      assert(block.contains(platform), clues(name, platform))
    }
  }

  test("the published laws module depends only on public core") {
    val build = Files.readString(root.resolve("build.sbt"))
    val lawsBlock = projectBlock(build, "laws", "lawsJS")
    assertEquals(dependencies(lawsBlock), Vector("core"))
    assert(lawsBlock.contains("crossProject(JSPlatform, JVMPlatform)"))

    val lawsMain =
      root.resolve("modules").resolve("laws").resolve("shared").resolve("src").resolve("main")
    val testFrameworkImport = """(?m)^\s*import\s+(munit\.|org\.scalatest\.|weaver\.)""".r
    val violations = productionSources
      .filter(_.startsWith(lawsMain))
      .flatMap { path =>
        testFrameworkImport.findAllMatchIn(Files.readString(path)).map { found =>
          s"${root.relativize(path)}: ${found.matched.trim}"
        }
      }

    assertEquals(violations, Vector.empty)
  }

  test("the optional interaction module depends only on core on both platforms") {
    val build = Files.readString(root.resolve("build.sbt"))
    val block = projectBlock(build, "interaction", "interactionJS")
    assertEquals(dependencies(block), Vector("core"))
    assert(block.contains("crossProject(JSPlatform, JVMPlatform)"))
    assert(!block.contains("libraryDependencies"))
  }

  test("only the JavaFX backend carries a toolkit dependency") {
    val build = Files.readString(root.resolve("build.sbt"))
    Vector(
      ("core", "coreJS"),
      ("interaction", "interactionJS"),
      ("laws", "lawsJS"),
      ("svg", "svgJS"),
      ("canvas", "canvasJS"),
      ("jvmText", "jvmTextJVM"),
      ("java2d", "java2dJVM"),
      ("pdf", "pdfJVM")
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

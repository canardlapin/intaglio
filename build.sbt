import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbtcrossproject.CrossPlugin.autoImport.*
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.*

ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.4.2"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")

/** Before the first published release, `tools/check-compatibility.sh` supplies an exact local
  * baseline and asks for the strongest check. Normal 0.x.0 development remains an explicitly
  * breaking boundary until that baseline exists in a repository.
  */
ThisBuild / versionPolicyIntention := {
  if (sys.env.contains("INTAGLIO_COMPAT_BASELINE_VERSION"))
    Compatibility.BinaryAndSourceCompatible
  else
    Compatibility.None
}
ThisBuild / versionPolicyIgnoredInternalDependencyVersions :=
  Some("^\\d+\\.\\d+\\.\\d+-SNAPSHOT$".r)

ThisBuild / homepage := Some(url("https://github.com/canardlapin/intaglio"))
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/canardlapin/intaglio"),
    "scm:git:git@github.com:canardlapin/intaglio.git"
  )
)
ThisBuild / developers := List(
  Developer(
    "canardlapin",
    "canardlapin",
    "307091466+canardlapin@users.noreply.github.com",
    url("https://github.com/canardlapin")
  )
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xmax-inlines:64"
  ),
  Test / fork := false,
  libraryDependencies += "org.scalameta" %%% "munit" % "1.2.1" % Test,
  mimaReportSignatureProblems := true,
  mimaPreviousArtifacts ++= sys.env
    .get("INTAGLIO_COMPAT_BASELINE_VERSION")
    .toSet
    .map(baseline => organization.value %%% name.value % baseline),
  tastyMiMaPreviousArtifacts ++= sys.env
    .get("INTAGLIO_COMPAT_BASELINE_VERSION")
    .toSet
    .map(baseline => organization.value %%% name.value % baseline),
  tastyMiMaConfig ~= { previous =>
    import java.util.Arrays.asList
    import tastymima.intf.{ProblemKind, ProblemMatcher}
    previous.withMoreProblemFilters(
      asList(
        ProblemMatcher.make(ProblemKind.InternalError, "intaglio.PackedStatPlan.Aux"),
        ProblemMatcher.make(ProblemKind.InternalError, "intaglio.StatResult.Aux")
      )
    )
  }
)

lazy val jsSettingsBase = Seq(
  scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
  // MiMa reads JVM class files. Shared Scala.js APIs are checked through their
  // JVM twins, while TASTy-MiMa still runs on every Scala.js artifact.
  versionPolicyCheck / skip := true,
  versionCheck / skip := true
)

/** OpenJFX publishes per-platform artifacts, so the classifier is resolved from
  * the building machine. It stays `Provided`: a consumer picks its own runtime.
  */
lazy val javafxPlatformClassifier: String = {
  val os = sys.props.getOrElse("os.name", "").toLowerCase
  val arch = sys.props.getOrElse("os.arch", "").toLowerCase
  val base =
    if (os.contains("mac")) "mac"
    else if (os.contains("win")) "win"
    else "linux"
  if (arch.contains("aarch64") && base != "win") base + "-aarch64" else base
}

lazy val core =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/core"))
    .settings(commonSettings)
    .settings(
      name := "intaglio-core",
      description := "Renderer-neutral grammar-of-graphics core for Scala 3, cross-compiled to JVM and Scala.js."
    )
    .jsSettings(jsSettingsBase)

lazy val coreJS  = core.js
lazy val coreJVM = core.jvm

lazy val laws =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/laws"))
    .dependsOn(core)
    .settings(commonSettings)
    .settings(
      name := "intaglio-laws",
      description := "Framework-neutral extension law kits for Intaglio ecosystem authors."
    )
    .jsSettings(jsSettingsBase)

lazy val lawsJS  = laws.js
lazy val lawsJVM = laws.jvm

lazy val svg =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/svg"))
    .dependsOn(core)
    .settings(commonSettings)
    .settings(
      name := "intaglio-svg",
      description := "SVG renderer for Intaglio scenes."
    )
    .jsSettings(jsSettingsBase)

lazy val svgJS  = svg.js
lazy val svgJVM = svg.jvm

lazy val notebook =
  crossProject(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/notebook"))
    .dependsOn(core, svg)
    .settings(commonSettings)
    .settings(
      name := "intaglio-notebook",
      description := "Optional Jupyter MIME-bundle display adapter for Intaglio plots."
    )

lazy val notebookJVM = notebook.jvm

lazy val performance =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/performance"))
    .dependsOn(core, svg)
    .settings(commonSettings)
    .settings(
      name := "intaglio-performance-gates",
      description := "Deterministic cross-platform performance regression workloads for Intaglio.",
      publish / skip := true
    )
    .jsSettings(jsSettingsBase)

lazy val performanceJS  = performance.js
lazy val performanceJVM = performance.jvm

lazy val canvas =
  crossProject(JSPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/canvas"))
    .dependsOn(core)
    .settings(commonSettings)
    .settings(
      name := "intaglio-canvas",
      description := "HTML Canvas renderer for Intaglio scenes (Scala.js)."
    )
    .jsSettings(jsSettingsBase)

lazy val canvasJS = canvas.js

lazy val java2d =
  crossProject(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/java2d"))
    .dependsOn(core)
    .settings(commonSettings)
    .settings(
      name := "intaglio-java2d",
      description := "Java2D renderer for Intaglio scenes (JVM).",
      libraryDependencies += "org.apache.pdfbox" % "pdfbox" % "3.0.8" % Test,
      tastyMiMaConfig ~= { previous =>
        import java.util.Arrays.asList
        import tastymima.intf.{ProblemKind, ProblemMatcher}
        previous.withMoreProblemFilters(
          asList(
            ProblemMatcher.make(
              ProblemKind.InternalError,
              "intaglio.java2d.Java2DRenderingHints.configure"
            ),
            ProblemMatcher.make(ProblemKind.InternalError, "intaglio.java2d.Java2DColor.awt"),
            ProblemMatcher.make(ProblemKind.InternalError, "intaglio.java2d.Java2DRenderer.render"),
            ProblemMatcher.make(ProblemKind.InternalError, "intaglio.java2d.Java2DRenderer.draw"),
            ProblemMatcher.make(
              ProblemKind.InternalError,
              "intaglio.java2d.Java2DRenderer.drawProfile"
            ),
            ProblemMatcher.make(
              ProblemKind.InternalError,
              "intaglio.java2d.Java2DRenderer.renderImage"
            ),
            ProblemMatcher.make(
              ProblemKind.InternalError,
              "intaglio.java2d.Java2DFontResolver.resolve"
            ),
            ProblemMatcher.make(
              ProblemKind.InternalError,
              "intaglio.java2d.Java2DFontResolver.fixed"
            )
          )
        )
      }
    )

lazy val java2dJVM = java2d.jvm

lazy val pdf =
  crossProject(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/pdf"))
    .dependsOn(core)
    .settings(commonSettings)
    .settings(
      name := "intaglio-pdf",
      description := "Publication-quality PDF renderer for Intaglio scenes (JVM).",
      libraryDependencies += "org.apache.pdfbox" % "pdfbox" % "3.0.8",
      // PDF render-back assertions exercise AWT image code. Isolate them from
      // the sbt UI process and make their intended headless environment explicit.
      Test / fork := true,
      Test / javaOptions += "-Djava.awt.headless=true"
    )

lazy val pdfJVM = pdf.jvm

lazy val javafx =
  crossProject(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/javafx"))
    .dependsOn(core)
    .settings(commonSettings)
    .settings(
      name := "intaglio-javafx",
      description := "JavaFX renderer for Intaglio scenes (JVM).",
      libraryDependencies ++= Seq(
        "org.openjfx" % "javafx-base" % "21.0.5" % Provided classifier javafxPlatformClassifier,
        "org.openjfx" % "javafx-graphics" % "21.0.5" % Provided classifier javafxPlatformClassifier
      ),
      tastyMiMaConfig ~= { previous =>
        import java.util.Arrays.asList
        import tastymima.intf.{ProblemKind, ProblemMatcher}
        previous.withMoreProblemFilters(
          asList(
            ProblemMatcher.make(
              ProblemKind.InternalError,
              "intaglio.javafx.JavaFxCanvasContext.<init>"
            )
          )
        )
      }
    )

lazy val javafxJVM = javafx.jvm

lazy val root =
  project
    .in(file("."))
    .aggregate(
      coreJS,
      coreJVM,
      lawsJS,
      lawsJVM,
      svgJS,
      svgJVM,
      notebookJVM,
      performanceJS,
      performanceJVM,
      canvasJS,
      java2dJVM,
      pdfJVM,
      javafxJVM
    )
    .settings(
      name := "intaglio",
      publish / skip := true
    )

addCommandAlias(
  "compileAll",
  ";coreJVM/compile;coreJS/compile;lawsJVM/compile;lawsJS/compile;svgJVM/compile;svgJS/compile;notebookJVM/compile;performanceJVM/compile;performanceJS/compile;canvasJS/compile;java2dJVM/compile;pdfJVM/compile;javafxJVM/compile"
)

addCommandAlias(
  "testAll",
  ";coreJVM/test;coreJS/test;lawsJVM/test;lawsJS/test;svgJVM/test;svgJS/test;notebookJVM/test;performanceJVM/test;performanceJS/test;canvasJS/test;java2dJVM/test;pdfJVM/test;javafxJVM/test"
)

addCommandAlias(
  "compatibilityCheck",
  ";versionPolicyCheck;coreJVM/tastyMiMaReportIssues;coreJS/tastyMiMaReportIssues;lawsJVM/tastyMiMaReportIssues;lawsJS/tastyMiMaReportIssues;svgJVM/tastyMiMaReportIssues;svgJS/tastyMiMaReportIssues;notebookJVM/tastyMiMaReportIssues;canvasJS/tastyMiMaReportIssues;java2dJVM/tastyMiMaReportIssues;pdfJVM/tastyMiMaReportIssues;javafxJVM/tastyMiMaReportIssues"
)

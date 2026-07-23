import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbtcrossproject.CrossPlugin.autoImport.*
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.*

ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.4.2"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")

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
  libraryDependencies += "org.scalameta" %%% "munit" % "1.2.1" % Test
)

lazy val jsSettingsBase = Seq(
  scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv()
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
      description := "Java2D renderer for Intaglio scenes (JVM)."
    )

lazy val java2dJVM = java2d.jvm

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
      )
    )

lazy val javafxJVM = javafx.jvm

lazy val root =
  project
    .in(file("."))
    .aggregate(
      coreJS,
      coreJVM,
      svgJS,
      svgJVM,
      canvasJS,
      java2dJVM,
      javafxJVM
    )
    .settings(
      name := "intaglio",
      publish / skip := true
    )

addCommandAlias(
  "compileAll",
  ";coreJVM/compile;coreJS/compile;svgJVM/compile;svgJS/compile;canvasJS/compile;java2dJVM/compile;javafxJVM/compile"
)

addCommandAlias(
  "testAll",
  ";coreJVM/test;coreJS/test;svgJVM/test;svgJS/test;canvasJS/test;java2dJVM/test;javafxJVM/test"
)

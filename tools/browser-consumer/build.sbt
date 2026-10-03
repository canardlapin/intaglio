// An external consumer of the published intaglio-browser artifact. It is deliberately not part of
// the intaglio build: it resolves `intaglio-browser` by coordinate only, at the exact version that
// tools/check-browser-consumer.sh published from a clean checkout, and fails if that version is
// absent. Never point it at sibling sources.
val intaglioVersion = sys.props.getOrElse(
  "intaglio.version",
  sys.error("pass -Dintaglio.version=<the version tools/check-browser-consumer.sh published>")
)

lazy val consumer = project
  .in(file("."))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "intaglio-browser-consumer",
    scalaVersion := "3.3.8",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror"),
    libraryDependencies += "io.github.canardlapin" %%% "intaglio-browser" % intaglioVersion,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.NoModule)),
    publish / skip := true
  )

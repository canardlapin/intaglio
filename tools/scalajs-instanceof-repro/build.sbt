// Standalone reduction of intaglio bd-01M41R5BTCMQ5R0NPGAZ0S504K. Not part of the intaglio build.
enablePlugins(ScalaJSPlugin)
scalaVersion := "3.3.8"
crossScalaVersions := Seq("3.3.8", "2.13.18")
scalaJSUseMainModuleInitializer := true
// Readable output, so the emitted instance test can be inspected; it does not change behaviour.
scalaJSLinkerConfig ~= (_.withPrettyPrint(true))

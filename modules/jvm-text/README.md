# intaglio-jvm-text

Internal JVM text shaping shared by Intaglio's Java2D and PDF backends. It
positions native AWT glyphs after shaping, preserving combining sequences,
contextual-script words, and bidirectional runs during letter spacing.

Applications obtain this module transitively through `intaglio-java2d` or
`intaglio-pdf`; its implementation is package-scoped. The portable core and
Scala.js modules have no dependency on it. JavaFX uses its own native text engine.

Run its independent glyph-positioning regressions with:

```sh
sbt jvmTextJVM/test
```

This new implementation module has no historical artifact baseline. Existing
backend APIs retain their compatibility checks. See [letter spacing](../../docs/letter-spacing.md)
for supported behavior and native-engine limits.

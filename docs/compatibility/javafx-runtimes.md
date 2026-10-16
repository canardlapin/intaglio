# JavaFX runtime qualification

`intaglio-javafx` keeps OpenJFX as a `Provided` dependency, so an application
selects the platform runtime it ships. The executable `javafxExample` uses the
same build-time version while compiling and testing the example.

## Qualification targets

The default runtime is OpenJFX `21.0.5`. CI is configured to qualify these exact runtime/JDK
pairs on Ubuntu with the platform classifier selected by the build:

| OpenJFX runtime | JDK | status |
| --- | --- | --- |
| `21.0.5` | `21` | default target |
| `27` | `25` | feature-runtime target |

These are qualification points, not evidence for every version between them.
[OpenJFX 27 requires JDK 25 or later](https://openjfx.io/openjfx-docs/index.html);
[OpenJFX 21 supports JDK 17 and 21](https://docs.oracle.com/en/java/java-components/javafx/21/javafx-users-guide/supported-jdk-versions.html). The ordinary JVM/Scala.js matrix continues to test
the library on JDK 17, 21, and 25, but JavaFX tests are omitted on JDK 17
because the retained headless Monocle `21.0.2` test dependency requires JDK 21.

The CI court runs both `javafxJVM/test` and `javafxExample/test` for each
configured pair. Its headless results exercise Monocle and software Prism; they
do not establish desktop rendering behavior on every operating system.

## Running a target locally

Set the JDK shown above before starting sbt. The build reads
`intaglio.javafx.version` while resolving dependencies, so pass it to the sbt
launcher:

```sh
# JDK 21
sbt -Dintaglio.javafx.version=21.0.5 javafxJVM/test javafxExample/test

# JDK 25 or later
sbt -Dintaglio.javafx.version=27 javafxJVM/test javafxExample/test
```

Do not change the Monocle dependency only to select a JavaFX runtime. It stays
at `21.0.2` until a compatibility failure demonstrates that a replacement is
needed and that replacement receives its own qualification.

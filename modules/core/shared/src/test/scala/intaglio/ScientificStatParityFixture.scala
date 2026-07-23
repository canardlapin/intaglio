package intaglio

/** Small reference values generated with R 4.x and the ggplot2 statistical
  * contracts vendored under `vendor/ggplot2`. Keeping the fixture literal
  * makes the same oracle run on the JVM and Scala.js.
  */
object ScientificStatParityFixture:
  val histogramValues: Vector[Double] =
    Vector(1.0, 2.0, 3.0)

  val histogramBreaks: Vector[Double] =
    Vector(0.0, 1.5, 5.0)

  val histogramCounts: Vector[Double] =
    Vector(1.0, 2.0)

  final case class SummaryObservation(x: Double, y: Double)

  val summaryValues: Vector[SummaryObservation] =
    Vector(
      SummaryObservation(1.0, 0.0),
      SummaryObservation(1.0, 2.0),
      SummaryObservation(2.0, 1.0),
      SummaryObservation(2.0, 3.0),
      SummaryObservation(3.0, 2.0),
      SummaryObservation(3.0, 4.0)
    )

  val summaryMeans: Vector[Double] =
    Vector(1.0, 2.0, 3.0)

  val summaryLower: Vector[Double] =
    Vector(0.0, 1.0, 2.0)

  val summaryUpper: Vector[Double] =
    Vector(2.0, 3.0, 4.0)

  val densityValues: Vector[Double] =
    Vector(0.0, 1.0, 2.0, 3.0, 4.0)

  val densityGrid: Vector[Double] =
    Vector(0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0)

  /** `approx(density(0:4, bw = 1, n = 512, from = 0, to = 4),
    * xout = seq(0, 4, 0.5))$y`.
    */
  val density: Vector[Double] =
    Vector(
      0.13988897946365283,
      0.17040478922152438,
      0.18825987501975672,
      0.19613687188289636,
      0.19817060212962401,
      0.19613687188289633,
      0.18825987501975666,
      0.17040478922152436,
      0.13988897946365286
    )

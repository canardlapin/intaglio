package intaglio

/** Reference fixture from R 4.x `table(mtcars$carb)` and ggplot2
  * `ggplot_build(ggplot(mtcars, aes(carb)) + geom_bar())`.
  *
  * ggplot2's own reference asserts x = 1,2,3,4,6,8 and y = 7,10,3,10,1,1 in
  * `vendor/ggplot2/tests/testthat/test-stat-count.R`. Its categorical positions are internally
  * one-based and `geom_bar()` uses a default width of 0.9. Intaglio deliberately uses zero-based
  * centers and an explicit `Band(width = 1 - padding)` value; the layouts are affine-equivalent
  * while the Scala API keeps interval semantics inspectable and typed.
  */
object StatCountParityFixture:
  val carb: Vector[String] =
    Vector(
      "4",
      "4",
      "1",
      "1",
      "2",
      "1",
      "4",
      "2",
      "2",
      "4",
      "4",
      "3",
      "3",
      "3",
      "4",
      "4",
      "4",
      "1",
      "2",
      "1",
      "1",
      "2",
      "2",
      "4",
      "2",
      "1",
      "2",
      "2",
      "4",
      "6",
      "8",
      "2"
    )

  val levels: Vector[String] =
    Vector("1", "2", "3", "4", "6", "8")

  val counts: Vector[Double] =
    Vector(7.0, 10.0, 3.0, 10.0, 1.0, 1.0)

  val proportions: Vector[Double] =
    counts.map(_ / carb.length.toDouble)

package intaglio

import scala.util.control.NonFatal

/** Deterministic, bounded fuzz regression over public construction and callback boundaries. Each
  * failure reports a replayable seed and category; the suite deliberately has no wall-clock or
  * platform randomness.
  */
class FuzzRegressionSuite extends munit.FunSuite:
  private final case class Row(index: Int, x: Double, y: Double)

  test("malformed scene values stay inside checked construction and lowering boundaries") {
    seeds.foreach { seed =>
      noLeak("scene", seed) {
        val rng = FuzzRng(seed)
        val x = scalar(rng, seed.toInt)
        val y = scalar(rng, (seed >>> 8).toInt)
        val extent = scalar(rng, (seed >>> 16).toInt)
        val alpha = scalar(rng, (seed >>> 24).toInt)

        Point.npc(x, y)
        Size.npc(extent, y)
        Rgba(rng.nextInt(512) - 128, rng.nextInt(512) - 128, rng.nextInt(512) - 128, alpha)
        GraphicParams.checked(lineWidth = extent, alpha = alpha)
        Viewport.checked(angleDegrees = x)
        PatternRecipe.angledHatch(x, extent, y)
        PatternRecipe.stipple(extent, y)
        Grob.text("fuzz", Point.npcUnsafe(0.5, 0.5), rotationDegrees = x)
        Grob.pointBatch(
          Vector(Point.npcUnsafe(0.5, 0.5)),
          sizes = BatchColumn.Values(Vector.empty)
        )

        val px = rng.between(-1.0, 2.0)
        val py = rng.between(-1.0, 2.0)
        val radius = rng.between(0.0, 0.25)
        val scene = Grob
          .circle(
            Point.npcUnsafe(px, py),
            ExtentExpr.npcUnsafe(radius),
            gp = GraphicParams.unsafe(
              stroke = Some(Rgba.unsafe(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))),
              fill = Some(Rgba.unsafe(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)))
            )
          )
          .map(grob => Scene(Vector(grob)))
        scene.foreach(value => DeviceScene.fromScene(value, DeviceContext.unsafe(320.0, 240.0)))
      }
    }
  }

  test("mapping failures never escape plot compilation") {
    seeds.foreach { seed =>
      noLeak("mapping", seed) {
        val rows = Vector.tabulate(24)(index => Row(index, index.toDouble, (index % 7).toDouble))
        val throwAt = math.floorMod(seed.toInt, rows.length)
        val rejectAt = math.floorMod((seed >>> 32).toInt, rows.length)
        val x = RowMapping.throwing[Row, Double] { row =>
          if row.index == throwAt then throw new IllegalStateException(s"x-$seed") else row.x
        }
        val y = RowMapping.checked[Row, Double] { row =>
          if row.index == rejectAt then Left(MappingFailure.Rejected(s"y-$seed"))
          else Right(row.y)
        }

        plot(rows).aes(x, y).geomPoint().resolve
      }
    }
  }

  test("transform callbacks and special values return typed failures") {
    val values = specialValues ++ seeds.take(128).map(seed => FuzzRng(seed).between(-1.0e6, 1.0e6))
    values.foreach { value =>
      noLeak("transform", java.lang.Double.doubleToLongBits(value)) {
        Transform.identity.transform(value)
        Transform.identity.inverse(value)
        Transform.reverse.transform(value)
        Transform.log10.transform(value)
        Transform.sqrt.transform(value)
      }
    }

    val throwingForward = Transform(
      "fuzz-forward",
      _ => throw new IllegalStateException("forward boom"),
      identity
    ).orThrow
    val throwingInverse = Transform(
      "fuzz-inverse",
      identity,
      _ => throw new IllegalArgumentException("inverse boom")
    ).orThrow

    assert(throwingForward.transform(1.0).left.toOption.exists {
      case GraphicsError.TransformEvaluationFailed(
            "fuzz-forward",
            "forward",
            _,
            "forward boom"
          ) =>
        true
      case _ => false
    })
    assert(throwingInverse.inverse(1.0).left.toOption.exists {
      case GraphicsError.TransformEvaluationFailed(
            "fuzz-inverse",
            "inverse",
            _,
            "inverse boom"
          ) =>
        true
      case _ => false
    })
  }

  test("break generation is bounded and custom failures remain typed") {
    seeds.foreach { seed =>
      noLeak("breaks", seed) {
        val rng = FuzzRng(seed)
        val lower = rng.between(-1.0e6, 1.0e6)
        val upper = lower + rng.between(0.0, 1.0e5)
        val range = Interval.unsafe(lower, upper)
        val count = rng.nextInt(Breaks.MaximumOutputSize + 256) - 128
        val width = scalar(rng, count)
        val candidates = Vector(
          Breaks.count(count),
          Breaks.pretty(count),
          Breaks.width(width)
        )
        candidates.foreach(
          _.flatMap(_.generate(range)).foreach(values =>
            assert(values.length <= Breaks.MaximumOutputSize, s"seed=$seed")
          )
        )
      }
    }

    val throwing = new Breaks:
      override def apply(range: Interval): Vector[Double] =
        throw new IllegalStateException(s"cannot break ${range.lower}")
    val invalid = new Breaks:
      override def apply(range: Interval): Vector[Double] =
        Vector(range.lower, Double.NaN, range.upper)
    val range = Interval.unsafe(-1.0, 1.0)

    assert(throwing.generate(range).left.toOption.exists {
      case GraphicsError.BreakGenerationFailed("custom", _, detail) =>
        detail.startsWith("cannot break -1")
      case _ => false
    })
    assert(invalid.generate(range).left.toOption.exists {
      case GraphicsError.NonFiniteBreak("custom", value) => value.isNaN
      case _                                             => false
    })
  }

  test("layout requests and metric callback failures stay inside the solver boundary") {
    seeds.foreach { seed =>
      noLeak("layout", seed) {
        val rng = FuzzRng(seed)
        val policy = LayoutPolicy(
          referenceDevice = DeviceContext.unsafe(
            rng.between(80.0, 1200.0),
            rng.between(80.0, 900.0)
          ),
          outerMarginPt = rng.between(0.0, 120.0),
          legendGapPt = rng.between(0.0, 40.0),
          panelGapPt = rng.between(0.0, 30.0)
        )
        val labels = Vector.tabulate(rng.nextInt(12))(index => s"tick-$index-${seed & 0xffL}")
        val request = PlotLayoutRequest(
          axes = Map(
            AxisSide.Bottom -> AxisRequest(labels, Some("fuzz x")),
            AxisSide.Left -> AxisRequest(labels.reverse, Some("fuzz y"))
          ),
          labels = PlotLabels(title = Some(s"seed $seed"))
        )

        PlotLayoutSolver.solve(policy, request)
      }
    }

    val throwingMetrics = new TextMetrics:
      override def widthPt(text: String, fontSizePt: Double): Double =
        throw new IllegalStateException(s"width $text")
      override def heightPt(fontSizePt: Double): Double =
        throw new IllegalArgumentException(s"height $fontSizePt")
    val result = PlotLayoutSolver.solve(
      LayoutPolicy(metrics = throwingMetrics),
      PlotLayoutRequest(axes = Map(AxisSide.Bottom -> AxisRequest(Vector("tick"))))
    )

    assert(result.left.toOption.exists {
      case GraphicsError.LayoutMeasurementFailed(_, detail) => detail.startsWith("height ")
      case _                                                => false
    })
  }

  private val seeds: Vector[Long] =
    Vector.tabulate(256)(index => 0x9e3779b97f4a7c15L ^ index.toLong * 0x632be59bd9b4e019L)

  private val specialValues =
    Vector(
      Double.NaN,
      Double.NegativeInfinity,
      Double.PositiveInfinity,
      -Double.MaxValue,
      -1.0,
      -0.0,
      0.0,
      java.lang.Double.MIN_VALUE,
      1.0,
      Double.MaxValue
    )

  private def scalar(rng: FuzzRng, selector: Int): Double =
    if (selector & 3) == 0 then specialValues(math.floorMod(selector, specialValues.length))
    else rng.between(-1.0e6, 1.0e6)

  private def noLeak(category: String, seed: Long)(body: => Unit): Unit =
    try body
    catch
      case NonFatal(error) =>
        fail(
          s"$category fuzz leaked ${error.getClass.getName} at seed $seed: ${Option(error.getMessage).getOrElse("no message")}",
          error
        )

  private final class FuzzRng private (private var state: Long):
    def nextLong(): Long =
      state += 0x9e3779b97f4a7c15L
      var value = state
      value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
      value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
      value ^ (value >>> 31)

    def nextInt(bound: Int): Int =
      require(bound > 0)
      ((nextLong() & Long.MaxValue) % bound.toLong).toInt

    def between(lower: Double, upper: Double): Double =
      val unit = (nextLong() >>> 11).toDouble / (1L << 53).toDouble
      lower + (upper - lower) * unit

  private object FuzzRng:
    def apply(seed: Long): FuzzRng = new FuzzRng(seed)

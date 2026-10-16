package intaglio

/** Regressions from the ephys4s consumer reports, GitHub #15–18 and #21. */
class ConsumerReportsSuite extends munit.FunSuite:
  private def ok[A](value: Either[IntaglioError, A]): A = value.fold(e => fail(e.message), identity)
  private case class P(x: Double, y: Double)
  private val field = ScalarField2D.unsafe(
    RegularGridAxis.cellCenteredUnsafe(0, 3, 3),
    RegularGridAxis.cellCenteredUnsafe(0, 2, 2),
    Vector(-2.6, 0, 1, 2, 3, 4.6)
  )

  private def marks(elements: Vector[DeviceElement]): Vector[DevicePrimitive] = elements.flatMap {
    case DeviceElement.Mark(mark)               => Vector(mark)
    case DeviceElement.Group(_, _, _, children) => marks(children)
    case DeviceElement.Annotated(_, children)   => marks(children)
  }

  private def name(mark: DevicePrimitive): Option[GraphicsName] = mark match
    case p: DevicePrimitive.Disc            => p.name
    case p: DevicePrimitive.PointBatch      => p.name
    case p: DevicePrimitive.Polyline        => p.name
    case p: DevicePrimitive.CompoundPolygon => p.name
    case p: DevicePrimitive.RectShape       => p.name
    case p: DevicePrimitive.TextRun         => p.name
    case p: DevicePrimitive.Image           => p.name

  test("field DSL accepts a fixed diverging fill scale for raster and heatmap") {
    val scale = ok(ContinuousScale.diverging("dB", 13, labeler = Labeler.typographic))
    val raster = ok(plot(field).geomRaster(scale = Some(scale)).resolve)
    val heatmap = ok(plot(field).geomHeatmap(scale = Some(scale)).resolve)
    for trained <- Vector(raster, heatmap) do
      val fill = trained.layers.head.trainedScales.find(_.key == Aesthetic.Fill).get
      assertEquals(fill.descriptor.domain, scale.descriptor.domain)
      val bar = trained.guides.collectFirst { case ResolvedGuide(b: GuideSpec.Colorbar, _) =>
        b
      }.get
      assertEquals(bar.ticks.head.label, "−13")
      assertEquals(bar.ticks.last.label, "13")
      assert(bar.ticks.exists(t => t.value == 0.5 && t.label == "0"))
    val image = raster.layers.head.grobs.head.asInstanceOf[Grob.Image].image
    field.cells.foreach { cell =>
      assertEquals(
        image.pixelUnsafe(cell.xIndex, 1 - cell.yIndex),
        Rgba32.fromRgba(scale.mapValue(cell.value).get)
      )
    }
    assertEquals(raster.guides, heatmap.guides)
  }

  test("typographic labeler covers signs and exponents without changing ASCII defaults") {
    val values = Vector(-0.4, -10.0, -1e-7, 0.4, 10.0, 1e-7)
    assertEquals(Labeler.typographic(values), Vector("−0.4", "−10", "−1e−7", "0.4", "10", "1e−7"))
    assertEquals(Labeler.default(values).take(3), Vector("-0.4", "-10", "-1e-7"))
  }

  test("hidden axes leave no primitives or strips and preserve derived colorbar content") {
    val ctx = RenderContext.unsafe(width = 300, height = 360)
    val scale = ok(ContinuousScale.diverging("dB", 5))
    def plan(guides: GuidePolicy) = ok(
      plot(field)
        .geomRaster(scale = Some(scale))
        .guides(guides)
        .resolve(ctx)
    )
    val ordinary = plan(GuidePolicy.Derived())
    val hidden = plan(GuidePolicy.Derived().withoutAxes(Set(AxisSide.Bottom, AxisSide.Left)))
    val bare = plan(GuidePolicy.NoGuides)
    val hiddenMarks = marks(ok(DeviceScene.fromScene(hidden.scene, ctx)).elements)
    assert(!hiddenMarks.exists(m => name(m).exists(_.value.contains("-axis-"))))
    def bar(plan: TrainedPlot) = plan.guides.collectFirst {
      case g @ ResolvedGuide(_: GuideSpec.Colorbar, _) => g
    }.get
    assertEquals(bar(hidden).spec, bar(ordinary).spec)
    // The legend viewport grows when the bottom axis strip is reclaimed; its top stays fixed.
    // The guide's actual content remains byte-identical within that viewport.
    assertEquals(
      bar(hidden).grob.asInstanceOf[Grob.Group].children,
      bar(ordinary).grob.asInstanceOf[Grob.Group].children
    )
    def frame(plan: TrainedPlot) = plan.layout.get.frame
    assertEquals(frame(hidden).origin.x, frame(bare).origin.x)
    assertEquals(frame(hidden).origin.y, frame(bare).origin.y)
    assert(hiddenMarks.exists(m => name(m).exists(_.value.endsWith("-colorbar-ramp"))))
  }

  test("derived continuous axes fit measured labels from 100 to 400 pixels") {
    for (lo, hi) <- Vector((0.0, 150.0), (-0.4, 0.4)); width <- 100 to 400 by 20 do
      val ctx = RenderContext.unsafe(width = width, height = width)
      val plan = ok(
        plot(Vector(P(lo, lo), P(hi, hi)))
          .aes(_.x, _.y)
          .geomPoint()
          .compilerOptions(
            PlotCompilerOptions(
              guides = GuidePolicy.Derived(),
              theme = Theme.minimal,
              expansion = RangeExpansion.none
            )
          )
          .renderPlan(ctx)
      )
      val text = marks(ok(plan.deviceScene).elements).collect { case t: DevicePrimitive.TextRun =>
        t
      }
      for (name, horizontal) <- Vector(("x-axis-label", true), ("y-axis-label", false)) do
        val labels = text
          .filter(_.name.exists(_.value.startsWith(name)))
          .sortBy(t => if horizontal then t.x else t.y)
        assert(labels.nonEmpty, s"$width $name")
        labels.sliding(2).filter(_.length == 2).foreach { pair =>
          def extent(t: DevicePrimitive.TextRun): Double =
            if horizontal then TextMetrics.estimate.widthPt(t.label, t.fontSizePx)
            else TextMetrics.estimate.heightPt(t.fontSizePx)
          val a = pair(0); val b = pair(1)
          val distance = if horizontal then b.x - a.x else b.y - a.y
          assert(
            distance + 1e-7 >= (extent(a) + extent(b)) / 2 + a.fontSizePx * 0.25,
            s"width=$width $name ${a.label}/${b.label}: $distance"
          )
        }
  }

  test("measured spacing retains wide tick sets and explicit ticks opt out") {
    val ticks =
      Vector(0.0, 20, 40, 60, 80, 100, 120, 140).map(v => AxisTick.unsafe(v, v.toInt.toString))
    assertEquals(GuideTickSpacing.fit(ticks, _.value * 3, _.label.length * 7.0, 5), ticks)
    val spec = GuideSpec.Axis(AxisSide.Bottom, ticks = Some(ticks))
    val lowered =
      ok(GuideSpec.lower(spec, PanelLayout.unit(Interval.unsafe(0, 150), Interval.unsafe(0, 1))))
    assertEquals(lowered.spec, spec)
  }

  test("short colorbars thin labels, retain fixed endpoints and use one seamless image") {
    val scale = ok(ContinuousScale.diverging("dB", 13))
    for height <- Vector(34.0, 45, 60, 120) do
      val ctx = RenderContext.unsafe(width = 400, height = 360)
      val plan = ok(
        plot(field)
          .geomRaster(scale = Some(scale))
          .compilerOptions(
            PlotCompilerOptions(
              guides = GuidePolicy.Derived(),
              policy = Some(LayoutPolicy(colorbarHeightPt = height)),
              theme = Theme.minimal
            )
          )
          .renderPlan(ctx)
      )
      val all = marks(ok(plan.deviceScene).elements)
      val labels = all
        .collect {
          case t: DevicePrimitive.TextRun if t.name.exists(_.value.contains("-colorbar-label-")) =>
            t
        }
        .sortBy(_.y)
      assertEquals(labels.head.label, "13")
      assertEquals(labels.last.label, "-13")
      labels.sliding(2).filter(_.size == 2).foreach { pair =>
        assert(pair(1).y - pair(0).y >= pair(0).fontSizePx * 1.5 - 1e-7)
      }
      assertEquals(all.count(m => name(m).exists(_.value.endsWith("-colorbar-ramp"))), 1)
      assert(!all.exists(m => name(m).exists(_.value.contains("-swatch-"))))
  }

  test("invalid colorbar extents and measurement callbacks remain typed failures") {
    val invalid = GuideSpec.Colorbar(
      None,
      Vector(Rgba.Black),
      Vector(AxisTick.unsafe(0.5, "0")),
      barHeight = ExtentExpr.unsafe(
        LengthExpr.Sub(LengthExpr.npcUnsafe(0.0), LengthExpr(Length.pointsUnsafe(1.0)))
      )
    )
    val layout = PanelLayout.unit(Interval.unsafe(0, 1), Interval.unsafe(0, 1))
    assert(GuideSpec.lower(invalid, layout).isLeft)
    val broken = new TextMetrics:
      def widthPt(text: String, size: Double): Double =
        throw new IllegalStateException("width unavailable")
      def heightPt(size: Double): Double = throw new IllegalStateException("height unavailable")
    val valid = invalid.copy(
      barHeight = ExtentExpr.pointsUnsafe(34),
      ticks = Vector(AxisTick.unsafe(0.0, "0"), AxisTick.unsafe(1.0, "1"))
    )
    assert(GuideSpec.lower(valid, layout, policy = LayoutPolicy(metrics = broken)).isLeft)
  }

  test("fixed colorbar endpoints propagate a transform failure without throwing") {
    var armed = false
    val transform = ok(
      Transform(
        "endpoint-failure",
        value =>
          if armed && math.abs(value) == 13 then
            throw new IllegalStateException("endpoint unavailable")
          else value,
        value => value,
        breaks = new Breaks { def apply(range: Interval): Vector[Double] = Vector(0.0) }
      )
    )
    val fill = ok(
      ContinuousScale.fixed("fixed", Vector(-13.0, 13.0), Palette.constant(Rgba.Black), transform)
    )
    val program = plot(field).geomRaster(scale = Some(fill))
    armed = true
    val error = program.resolve.left.toOption.getOrElse(fail("expected typed transform error"))
    assert(error.isInstanceOf[GraphicsError.TransformEvaluationFailed])
  }

  test("hidden physical sides propagate through facets and flipped coordinates") {
    val fill = ok(ContinuousScale.diverging("dB", 5))
    val hidden = GuidePolicy.Derived().withoutAxes(Set(AxisSide.Bottom, AxisSide.Left))
    val facets = ok(
      plot(field)
        .geomRaster(scale = Some(fill))
        .facetWrap(_.yIndex.toString)
        .guides(hidden)
        .resolve
    )
    assert(!facets.guides.exists(_.spec.isInstanceOf[GuideSpec.Axis]))
    assert(facets.guides.exists(_.spec.isInstanceOf[GuideSpec.Colorbar]))
    val flipped = ok(
      plot(field)
        .geomRaster(scale = Some(fill))
        .coord(Coord.Flipped())
        .guides(GuidePolicy.Derived().withoutAxes(Set(AxisSide.Bottom)))
        .resolve
    )
    val sides = flipped.guides.collect { case ResolvedGuide(a: GuideSpec.Axis, _) => a.side }
    assertEquals(sides, Vector(AxisSide.Left))
  }

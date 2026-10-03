package intaglio.browser

import intaglio.*
import intaglio.interaction.*

class WidgetTargetStyleSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)
  private val context = RenderContext.unsafe(400, 300)
  private val space = ok(KeySpace("styles", KeyCodec.text))
  private val green = Rgba.unsafe(15, 150, 120)
  private def view[R](builder: PlotBuilder[R, ?])(key: R => String): SvgWidgetView[String] =
    val plan = ok(
      InteractionCompiler.compile(
        ok(builder.build).plot,
        space,
        ok(DataRevision("d1")),
        SemanticId.unsafe("styles"),
        ok(PlanRevision("p1")),
        PlotCompilerOptions.lean.copy(renderContext = Some(context))
      )(key)
    )
    ok(SvgWidgetView.compile(plan, context, "styles"))
  private def descendants(g: Grob): Vector[Grob] = Vector(g) ++ g.children.flatMap(descendants)
  private def grobs(scene: Scene) = scene.grobs.flatMap(descendants)
  private def ids(v: SvgWidgetView[String]) =
    v.plans.flatMap(_.groups).flatMap(g => (0 until g.size).map(i => ok(g.at(i)).id))
  private val points = view(
    plot(Vector(("a", 1.0, 2.0), ("b", 2.0, 1.0)))
      .aes(_._2, _._3)
      .fill(Rgba.White)
      .geomPoint()
  )(_._1)

  test("target paint retains a point batch and changes only the chosen index") {
    val before = grobs(points.scene).collectFirst { case b: Grob.PointBatch => b }.get
    val after = grobs(
      ok(
        WidgetTargetStyle.paint(
          points,
          Map(
            ids(points).head -> WidgetTargetStyle(
              fill = Some(green),
              stroke = Some(green),
              opacity = Some(0.5)
            )
          )
        )
      )
    ).collectFirst { case b: Grob.PointBatch => b }.get
    assertEquals(after.points, before.points)
    assertEquals(after.graphicParams.valueAt(0).stroke, Some(green))
    assertEquals(after.graphicParams.valueAt(0).alpha, 0.5)
    assertEquals(after.graphicParams.valueAt(0).fill, Some(green))
    assertEquals(after.graphicParams.valueAt(1), before.graphicParams.valueAt(1))
    assertEquals(before.graphicParams.valueAt(0).fill, Some(Rgba.White))
  }
  test("repainting retains identities and clearing returns the original view") {
    val styled =
      ok(points.withTargetStyles(Map(ids(points).head -> WidgetTargetStyle(fill = Some(green)))))
    assertEquals(ids(styled), ids(points))
    assertEquals(styled.revision, points.revision)
    assertEquals(styled.panelFrame, points.panelFrame)
    assertEquals(ok(points.withTargetStyles(Map.empty)), points)
    assertNotEquals(styled.markup, points.markup)
  }
  test("unknown targets and invalid opacity are rejected before scene mutation") {
    val unknown = ok(
      ok(
        TargetSeries(
          SemanticId.unsafe("other"),
          ok(PlanRevision("p1")),
          SemanticId.unsafe("other-targets"),
          1
        )
      ).at(0)
    )
    assert(WidgetTargetStyle.paint(points, Map(unknown -> WidgetTargetStyle())).isLeft)
    Vector(Double.NaN, Double.PositiveInfinity, -0.1, 1.1).foreach { opacity =>
      assert(
        WidgetTargetStyle
          .paint(points, Map(ids(points).head -> WidgetTargetStyle(opacity = Some(opacity))))
          .isLeft
      )
    }
  }
  test("raster styling changes one visual cell and rejects stroke without altering the source") {
    val axis = RegularGridAxis.cellCenteredUnsafe(0, 2, 2)
    val field = ok(ScalarField2D.tabulate(axis, axis)(_ + _))
    val raster = view(plot(field).geomRaster())(c => s"${c.xIndex}-${c.yIndex}")
    val original = grobs(raster.scene).collectFirst { case i: Grob.Image => i.image }.get
    val painted = grobs(
      ok(
        WidgetTargetStyle.paint(
          raster,
          Map(ids(raster).head -> WidgetTargetStyle(fill = Some(green)))
        )
      )
    ).collectFirst { case i: Grob.Image => i.image }.get
    assertEquals(painted.pixel(0, 0), Right(Rgba32.fromRgba(green)))
    assertEquals(painted.pixel(1, 0), original.pixel(1, 0))
    assertEquals(painted.pixel(0, 1), original.pixel(0, 1))
    assert(
      WidgetTargetStyle
        .paint(raster, Map(ids(raster).head -> WidgetTargetStyle(stroke = Some(green))))
        .isLeft
    )
  }
  test("a styled raster target repaints the cell the picker reports for it, and only that cell") {
    // A 3 x 2 grid and an off-diagonal cell: a transposed or flipped index cannot pass.
    val field = ok(
      ScalarField2D.tabulate(
        RegularGridAxis.cellCenteredUnsafe(0, 3, 3),
        RegularGridAxis.cellCenteredUnsafe(0, 2, 2)
      )(_ * 10 + _)
    )
    val raster = view(plot(field).geomRaster())(c => s"${c.xIndex}-${c.yIndex}")
    val target = raster.plans
      .flatMap(_.groups)
      .flatMap(g => (0 until g.size).map(i => ok(g.at(i))))
      .find(_.rasterCell.exists(c => c.column == 2 && c.row == 0))
      .getOrElse(fail("no cell (2, 0)"))
    // Where the target is drawn comes from picking geometry, not from the paint's index arithmetic.
    val ring = raster.picking.outline(target.id, 0).fold(e => fail(e.message), identity).rings.head
    val (cx, cy) = (ring.map(_.x).sum / ring.size, ring.map(_.y).sum / ring.size)
    def deviceImages(elements: Vector[DeviceElement]): Vector[DevicePrimitive.Image] =
      elements.flatMap {
        case DeviceElement.Group(_, _, _, children)           => deviceImages(children)
        case DeviceElement.Annotated(_, children)             => deviceImages(children)
        case DeviceElement.Mark(image: DevicePrimitive.Image) => Vector(image)
        case _                                                => Vector.empty
      }
    val placed = deviceImages(ok(DeviceScene.fromScene(raster.scene, context)).elements).head
    val column = ((cx - placed.x) / (placed.width / placed.image.width)).toInt
    val row = ((cy - placed.y) / (placed.height / placed.image.height)).toInt
    val original = grobs(raster.scene).collectFirst { case i: Grob.Image => i.image }.get
    val painted = grobs(
      ok(WidgetTargetStyle.paint(raster, Map(target.id -> WidgetTargetStyle(fill = Some(green)))))
    ).collectFirst { case i: Grob.Image => i.image }.get
    for
      x <- 0 until original.width
      y <- 0 until original.height
    do
      val expected =
        if (x, y) == (column, row) then Rgba32.fromRgba(green) else ok(original.pixel(x, y))
      assertEquals(ok(painted.pixel(x, y)), expected, s"pixel ($x, $y)")
  }
  test("a raster cell's fill alpha and opacity multiply its own pixel alpha") {
    val axis = RegularGridAxis.cellCenteredUnsafe(0, 2, 2)
    val field = ok(ScalarField2D.tabulate(axis, axis)(_ + _))
    val raster = view(plot(field).geomRaster())(c => s"${c.xIndex}-${c.yIndex}")
    val image = (v: SvgWidgetView[String]) =>
      grobs(v.scene).collectFirst { case i: Grob.Image => i.image }.get
    val translucent = Rgba.unsafe(15, 150, 120, 0.5)
    val styled = ok(
      raster.withTargetStyles(
        Map(ids(raster).head -> WidgetTargetStyle(fill = Some(translucent), opacity = Some(0.5)))
      )
    )
    val before = ok(image(raster).pixel(0, 0)).alpha
    val after = ok(image(styled).pixel(0, 0))
    assertEquals((after.red, after.green, after.blue), (15, 150, 120))
    assertEquals(
      after.alpha,
      math.round(before * (Rgba32.fromRgba(translucent).alpha / 255.0) * 0.5).toInt
    )
  }
  test("one invalid entry rejects the whole request, valid entries included") {
    val unknown = ok(
      ok(
        TargetSeries(
          SemanticId.unsafe("other"),
          ok(PlanRevision("p1")),
          SemanticId.unsafe("other-targets"),
          1
        )
      ).at(0)
    )
    val mixed = Map(
      ids(points).head -> WidgetTargetStyle(fill = Some(green)),
      ids(points)(1) -> WidgetTargetStyle(opacity = Some(2.0)),
      unknown -> WidgetTargetStyle(fill = Some(green))
    )
    assert(WidgetTargetStyle.paint(points, mixed).isLeft)
    assert(points.withTargetStyles(mixed).isLeft)
    // The error is the same however the map happens to iterate.
    val errors = mixed.toVector.permutations
      .map(entries => WidgetTargetStyle.paint(points, entries.toMap).left.map(_.message))
      .toSet
    assertEquals(errors.size, 1, errors)
  }

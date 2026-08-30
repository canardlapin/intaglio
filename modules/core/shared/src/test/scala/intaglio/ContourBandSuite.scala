package intaglio

class ContourBandSuite extends munit.FunSuite:
  test("a radial band retains one outer ring and one explicit hole") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(-2.0, 2.0, 81)
    val field = ScalarField2D.tabulate(axis, axis)((x, y) => x * x + y * y).toOption.get
    val bands = ContourBandSet
      .extract(field, ContourBreaks.atUnsafe(Vector(0.25, 1.0)))
      .fold(error => fail(error.message), identity)
    val band = bands.bands.head

    assertEquals(bands.bands.length, 1)
    assertEquals(band.regions.length, 1)
    assertEquals(band.regions.head.holes.length, 1)
    assertEquals(band.regions.head.outer.winding, RingWinding.CounterClockwise)
    assertEquals(band.regions.head.holes.head.winding, RingWinding.Clockwise)
    assertEqualsDouble(math.abs(band.regions.head.outer.signedArea), math.Pi, 0.01)
    assertEqualsDouble(math.abs(band.regions.head.holes.head.signedArea), math.Pi * 0.25, 0.01)
  }

  test("bands partition a planar domain without area loss or overlap") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(-1.0, 1.0, 17)
    val field = ScalarField2D.tabulate(axis, axis)(_ + _).toOption.get
    val bands = ContourBandSet
      .extract(field, ContourBreaks.atUnsafe(Vector(-2.0, 0.0, 2.0)))
      .fold(error => fail(error.message), identity)
    val area = bands.bands.flatMap(_.fragments).map(_.area).sum

    assertEquals(bands.bands.length, 2)
    assertEqualsDouble(area, 4.0, 1e-12)
  }

  test("band topology is deterministic under translation") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(-1.5, 1.5, 25)
    val shiftedAxis = RegularGridAxis.vertexCenteredUnsafe(8.5, 11.5, 25)
    val source = ScalarField2D.tabulate(axis, axis)((x, y) => x * x + y * y).toOption.get
    val shifted = ScalarField2D
      .tabulate(shiftedAxis, shiftedAxis)((x, y) =>
        (x - 10.0) * (x - 10.0) + (y - 10.0) * (y - 10.0)
      )
      .toOption
      .get
    val breaks = ContourBreaks.atUnsafe(Vector(0.25, 1.0))
    val left = ContourBandSet.extract(source, breaks).toOption.get.bands.head.regions.head
    val right = ContourBandSet.extract(shifted, breaks).toOption.get.bands.head.regions.head

    assertEquals(left.outer.points.length, right.outer.points.length)
    left.outer.points.zip(right.outer.points).foreach { case (original, translated) =>
      assertEqualsDouble(translated.x - original.x, 10.0, 1e-13)
      assertEqualsDouble(translated.y - original.y, 10.0, 1e-13)
    }
    assertEquals(left.holes.map(_.points.length), right.holes.map(_.points.length))
  }

  test("filled bands lower through capability-gated generic polygons") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(-2.0, 2.0, 81)
    val field = ScalarField2D.tabulate(axis, axis)((x, y) => x * x + y * y).toOption.get
    val bands =
      ContourBandSet.extract(field, ContourBreaks.atUnsafe(Vector(0.25, 1.0))).toOption.get
    val trained =
      plot(bands).geomFilledContour().resolve.fold(error => fail(error.message), identity)

    assertEquals(trained.layers.map(_.geom), Vector(Geom.Polygon))
    assert(trained.layers.head.grobs.nonEmpty)
    assert(trained.layers.head.grobs.forall(_.isInstanceOf[Grob.CompoundPolygon]))
    assert(trained.layers.head.grobs.exists {
      case Grob.CompoundPolygon(rings, _, _, _) => rings.length > 1
      case _                                    => false
    })
    assert(trained.guides.exists(_.grob.name.exists(_.value == "level-colorbar")))
  }

  test("break and grid failures remain typed") {
    assert(ContourBreaks.at(Vector(0.0)).isLeft)
    assert(ContourBreaks.at(Vector(0.0, 0.0)).isLeft)
    val one = RegularGridAxis.cellCenteredUnsafe(0.0, 1.0, 1)
    val field = ScalarField2D.unsafe(one, one, Vector(0.0))
    assertEquals(
      ContourBandSet.extract(field, ContourBreaks.atUnsafe(Vector(0.0, 1.0))).left.toOption,
      Some(GraphicsError.ContourGridTooSmall(1, 1))
    )
  }

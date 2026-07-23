package intaglio

class ContourSuite extends munit.FunSuite:
  test("a planar field produces one analytic contour path") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(-1.0, 1.0, 5)
    val field = ScalarField2D.tabulate(axis, axis)(_ + _).fold(error => fail(error.message), identity)
    val contours = ContourSet
      .extract(field, ContourLevels.atUnsafe(Vector(0.25)))
      .fold(error => fail(error.message), identity)

    assertEquals(contours.lines.length, 1)
    assertEquals(contours.lines.head.paths.length, 1)
    val path = contours.lines.head.paths.head
    assert(path.points.length >= 2)
    path.points.foreach(point => assertEqualsDouble(point.x + point.y, 0.25, 1e-14))
    val endpoints = Vector(path.points.head, path.points.last)
    assert(endpoints.forall(point => math.abs(point.x) == 1.0 || math.abs(point.y) == 1.0))
  }

  test("a radial field produces a closed, nearly circular path") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(-2.0, 2.0, 41)
    val field = ScalarField2D.tabulate(axis, axis)((x, y) => x * x + y * y).fold(error => fail(error.message), identity)
    val contours = ContourSet
      .extract(field, ContourLevels.atUnsafe(Vector(1.0)))
      .fold(error => fail(error.message), identity)
    val path = contours.lines.head.paths.head

    assertEquals(contours.lines.head.paths.length, 1)
    assert(path.isClosed)
    path.points.foreach { point =>
      assertEqualsDouble(point.x * point.x + point.y * point.y, 1.0, 0.003)
    }
  }

  test("ambiguous saddles obey the explicit tie policy") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(0.0, 1.0, 2)
    val field = ScalarField2D.unsafe(axis, axis, Vector(1.0, -1.0, -1.0, 1.0))
    val levels = ContourLevels.atUnsafe(Vector(0.0))
    val above = ContourSet.extract(field, ContourConfig(levels, SaddleTiePolicy.ConnectAbove)).toOption.get
    val below = ContourSet.extract(field, ContourConfig(levels, SaddleTiePolicy.ConnectBelow)).toOption.get

    assertEquals(above.lines.head.paths.length, 2)
    assertEquals(below.lines.head.paths.length, 2)
    assertNotEquals(above.lines.head.paths.map(_.points), below.lines.head.paths.map(_.points))
  }

  test("contour geometry is translation invariant") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(-1.0, 1.0, 7)
    val shiftedAxis = RegularGridAxis.vertexCenteredUnsafe(9.0, 11.0, 7)
    val source = ScalarField2D.tabulate(axis, axis)((x, y) => x * x + y).toOption.get
    val shifted = ScalarField2D.tabulate(shiftedAxis, shiftedAxis)((x, y) => (x - 10.0) * (x - 10.0) + y - 10.0).toOption.get
    val levels = ContourLevels.atUnsafe(Vector(0.5))
    val left = ContourSet.extract(source, levels).toOption.get.vertices
    val right = ContourSet.extract(shifted, levels).toOption.get.vertices

    assertEquals(left.length, right.length)
    left.zip(right).foreach { case (original, translated) =>
      assertEqualsDouble(translated.x - original.x, 10.0, 1e-14)
      assertEqualsDouble(translated.y - original.y, 10.0, 1e-14)
    }
  }

  test("levels, grids, and plotting capabilities are checked") {
    assert(ContourLevels.at(Vector.empty).isLeft)
    assert(ContourLevels.at(Vector(1.0, 1.0)).isLeft)
    assert(ContourLevels.between(0.0, 1.0, 0).isLeft)

    val one = RegularGridAxis.cellCenteredUnsafe(0.0, 1.0, 1)
    val tiny = ScalarField2D.unsafe(one, one, Vector(1.0))
    assertEquals(
      ContourSet.extract(tiny, ContourLevels.atUnsafe(Vector(0.5))).left.toOption,
      Some(GraphicsError.ContourGridTooSmall(1, 1))
    )

    val axis = RegularGridAxis.vertexCenteredUnsafe(0.0, 1.0, 2)
    val field = ScalarField2D.unsafe(axis, axis, Vector(0.0, 1.0, 1.0, 2.0))
    val contours = ContourSet.extract(field, ContourLevels.atUnsafe(Vector(0.5))).toOption.get
    val trained = plot(contours).geomContour().resolve.fold(error => fail(error.message), identity)
    assert(trained.layers.head.grobs.forall(_.isInstanceOf[Grob.Lines]))
  }

package intaglio

class ContourSuite extends munit.FunSuite:
  test("a planar field produces one analytic contour path") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(-1.0, 1.0, 5)
    val field =
      ScalarField2D.tabulate(axis, axis)(_ + _).fold(error => fail(error.message), identity)
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
    val field = ScalarField2D
      .tabulate(axis, axis)((x, y) => x * x + y * y)
      .fold(error => fail(error.message), identity)
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
    val above =
      ContourSet.extract(field, ContourConfig(levels, SaddleTiePolicy.ConnectAbove)).toOption.get
    val below =
      ContourSet.extract(field, ContourConfig(levels, SaddleTiePolicy.ConnectBelow)).toOption.get

    assertEquals(above.lines.head.paths.length, 2)
    assertEquals(below.lines.head.paths.length, 2)
    assertNotEquals(above.lines.head.paths.map(_.points), below.lines.head.paths.map(_.points))
  }

  test("all non-ambiguous marching-square cases match an independent edge table") {
    val expected = Vector(
      1 -> Set(Vector(0, 3)),
      2 -> Set(Vector(0, 1)),
      3 -> Set(Vector(1, 3)),
      4 -> Set(Vector(1, 2)),
      6 -> Set(Vector(0, 2)),
      7 -> Set(Vector(2, 3)),
      8 -> Set(Vector(2, 3)),
      9 -> Set(Vector(0, 2)),
      11 -> Set(Vector(1, 2)),
      12 -> Set(Vector(1, 3)),
      13 -> Set(Vector(0, 1)),
      14 -> Set(Vector(0, 3))
    )

    expected.foreach { case (code, pairs) =>
      assertEquals(edgePairs(cell(code), SaddleTiePolicy.ConnectAbove), pairs, s"case $code")
      assertEquals(edgePairs(cell(code), SaddleTiePolicy.ConnectBelow), pairs, s"case $code")
    }
  }

  test("ambiguous case fixtures pin both asymptotic-decider tie outcomes") {
    assertEquals(
      edgePairs(cell(5), SaddleTiePolicy.ConnectAbove),
      Set(Vector(0, 1), Vector(2, 3))
    )
    assertEquals(
      edgePairs(cell(5), SaddleTiePolicy.ConnectBelow),
      Set(Vector(0, 3), Vector(1, 2))
    )
    assertEquals(
      edgePairs(cell(10), SaddleTiePolicy.ConnectAbove),
      Set(Vector(0, 3), Vector(1, 2))
    )
    assertEquals(
      edgePairs(cell(10), SaddleTiePolicy.ConnectBelow),
      Set(Vector(0, 1), Vector(2, 3))
    )
  }

  test("ambiguous topology is invariant under positive affine value transforms") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(0.0, 1.0, 2)
    (1 to 128).foreach { index =>
      val positiveA = 0.75 + index.toDouble * 0.013
      val positiveB = 1.1 + (index % 11).toDouble * 0.071
      val negativeA = -(0.9 + (index % 7).toDouble * 0.083)
      val negativeB = -(1.25 + (index % 13).toDouble * 0.047)
      val raw =
        if index % 2 == 0 then Vector(positiveA, negativeA, negativeB, positiveB)
        else Vector(negativeA, positiveA, positiveB, negativeB)
      val scale = 1.5 + (index % 5).toDouble
      val offset = 1.0e9 + index.toDouble * 16.0
      val transformed = raw.map(value => offset + scale * value)
      val originalField = ScalarField2D.unsafe(axis, axis, raw)
      val transformedField = ScalarField2D.unsafe(axis, axis, transformed)

      assertEquals(
        edgePairs(originalField, 0.0, SaddleTiePolicy.ConnectAbove),
        edgePairs(transformedField, offset, SaddleTiePolicy.ConnectAbove),
        s"fixture $index"
      )
    }
  }

  test("the asymptotic decider is stable for extreme finite corner magnitudes") {
    val normalized = ScalarField2D.unsafe(unitAxis, unitAxis, Vector(1.0, -1.0, -0.5, 0.8))
    val extreme =
      ScalarField2D.unsafe(unitAxis, unitAxis, Vector(1.0e300, -1.0e300, -5.0e299, 8.0e299))

    Vector(SaddleTiePolicy.ConnectAbove, SaddleTiePolicy.ConnectBelow).foreach { policy =>
      assertEquals(edgePairs(extreme, policy), edgePairs(normalized, policy))
    }
  }

  test("contour geometry is translation invariant") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(-1.0, 1.0, 7)
    val shiftedAxis = RegularGridAxis.vertexCenteredUnsafe(9.0, 11.0, 7)
    val source = ScalarField2D.tabulate(axis, axis)((x, y) => x * x + y).toOption.get
    val shifted = ScalarField2D
      .tabulate(shiftedAxis, shiftedAxis)((x, y) => (x - 10.0) * (x - 10.0) + y - 10.0)
      .toOption
      .get
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

  private val unitAxis = RegularGridAxis.vertexCenteredUnsafe(0.0, 1.0, 2)

  private def cell(code: Int): ScalarField2D =
    val value = (bit: Int) => if (code & bit) == 0 then -1.0 else 1.0
    ScalarField2D.unsafe(unitAxis, unitAxis, Vector(value(1), value(2), value(8), value(4)))

  private def edgePairs(
      field: ScalarField2D,
      policy: SaddleTiePolicy
  ): Set[Vector[Int]] =
    edgePairs(field, 0.0, policy)

  private def edgePairs(
      field: ScalarField2D,
      level: Double,
      policy: SaddleTiePolicy
  ): Set[Vector[Int]] =
    MarchingSquares
      .paths(field, ContourLevel.unsafe(level), policy)
      .map { path =>
        assertEquals(path.points.length, 2)
        path.points.map(edgeOf).sorted
      }
      .toSet

  private def edgeOf(point: FieldPoint): Int =
    if point.y == 0.0 then 0
    else if point.x == 1.0 then 1
    else if point.y == 1.0 then 2
    else if point.x == 0.0 then 3
    else fail(s"point $point is not on a unit-cell edge")

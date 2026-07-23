package intaglio

class ScalarFieldSuite extends munit.FunSuite:
  test("regular axes make sampling semantics explicit") {
    val cells = RegularGridAxis.cellCenteredUnsafe(0.0, 4.0, 2)
    val vertices = RegularGridAxis.vertexCenteredUnsafe(0.0, 4.0, 3)

    assertEquals(cells.coordinate(0), Some(1.0))
    assertEquals(cells.coordinate(1), Some(3.0))
    assertEquals(cells.tileBounds(1), Some(Interval.unsafe(2.0, 4.0)))
    assertEquals(vertices.coordinate(1), Some(2.0))
    assertEquals(vertices.tileBounds(0), Some(Interval.unsafe(-1.0, 1.0)))
    assertEquals(vertices.coordinate(3), None)
  }

  test("scalar fields are immutable x-fastest row-major values") {
    val x = RegularGridAxis.cellCenteredUnsafe(0.0, 2.0, 2)
    val y = RegularGridAxis.cellCenteredUnsafe(10.0, 12.0, 2)
    val source = Array(1.0, 2.0, 3.0, 4.0)
    val field = ScalarField2D(x, y, source).fold(error => fail(error.message), identity)
    source(0) = 99.0

    assertEquals(field.value(0, 0), Right(1.0))
    assertEquals(field.value(1, 0), Right(2.0))
    assertEquals(field.value(0, 1), Right(3.0))
    assertEquals(field.cells.map(_.value), Vector(1.0, 2.0, 3.0, 4.0))
    assertEquals(field.cells.map(_.x), Vector(0.5, 1.5, 0.5, 1.5))
    assertEquals(field.cells.map(_.y), Vector(10.5, 10.5, 11.5, 11.5))
  }

  test("tabulation and value mapping preserve geometry") {
    val x = RegularGridAxis.vertexCenteredUnsafe(-1.0, 1.0, 3)
    val y = RegularGridAxis.vertexCenteredUnsafe(-2.0, 2.0, 3)
    val field = ScalarField2D.tabulate(x, y)(_ + _).fold(error => fail(error.message), identity)
    val squared = field.mapValues(value => value * value).fold(error => fail(error.message), identity)

    assertEquals(field.samples, Vector(-3.0, -2.0, -1.0, -1.0, 0.0, 1.0, 1.0, 2.0, 3.0))
    assertEquals(squared.xAxis, field.xAxis)
    assertEquals(squared.yAxis, field.yAxis)
    assertEquals(squared.samples, field.samples.map(value => value * value))
  }

  test("construction rejects invalid geometry, shape, values, and access") {
    assertEquals(
      RegularGridAxis.cellCentered(0.0, 1.0, 0).left.toOption,
      Some(GraphicsError.InvalidGridSize("CellCentered", 1, 0))
    )
    assertEquals(
      RegularGridAxis.vertexCentered(0.0, 1.0, 1).left.toOption,
      Some(GraphicsError.InvalidGridSize("VertexCentered", 2, 1))
    )
    assertEquals(
      RegularGridAxis.cellCentered(1.0, 1.0, 2).left.toOption,
      Some(GraphicsError.InvalidGridDomain(1.0, 1.0))
    )

    val axis = RegularGridAxis.cellCenteredUnsafe(0.0, 1.0, 1)
    assertEquals(
      ScalarField2D(axis, axis, Vector.empty).left.toOption,
      Some(GraphicsError.ScalarFieldValueCountMismatch(1, 0))
    )
    ScalarField2D(axis, axis, Vector(Double.NaN)).left.toOption match
      case Some(GraphicsError.NonFiniteScalarFieldValue(0, value)) => assert(value.isNaN)
      case other => fail(s"expected non-finite field error, found $other")
    val field = ScalarField2D.unsafe(axis, axis, Vector(1.0))
    assertEquals(
      field.value(1, 0).left.toOption,
      Some(GraphicsError.ScalarFieldIndexOutsideBounds(1, 0, 1, 1))
    )
  }

package intaglio

class RasterGeomSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)
  private val field = ScalarField2D.unsafe(
    RegularGridAxis.cellCenteredUnsafe(-3, 3, 3),
    RegularGridAxis.cellCenteredUnsafe(10, 14, 2),
    Vector(1, 2, 4, 8, 16, 32).map(_.toDouble)
  )
  private def image(plot: TrainedPlot): Grob.Image =
    assertEquals(plot.layers.head.grobs.size, 1)
    plot.layers.head.grobs.head.asInstanceOf[Grob.Image]

  test("non-square raster matches heatmap pixels, extent and legend under identity and log fill") {
    Vector(Transform.identity, Transform.log10).foreach { transform =>
      val tiled = ok(plot(field).geomHeatmap(transform = transform).resolve)
      val raster = ok(plot(field).geomRaster(transform = transform).resolve)
      val img = image(raster)
      assertEquals((img.image.width, img.image.height), (3, 2))
      field.cells.zipWithIndex.foreach { (cell, i) =>
        val tile = tiled.layers.head.grobs(i).asInstanceOf[Grob.Rect]
        assertEquals(
          img.image.pixelUnsafe(cell.xIndex, 1 - cell.yIndex),
          Rgba32.fromRgba(tile.gp.fill.get)
        )
      }
      assertEquals(raster.layout, tiled.layout)
      assertEquals(raster.guides, tiled.guides)
      val device = ok(DeviceScene.fromScene(raster.scene, RenderContext.unsafe()))
      def marks(elements: Vector[DeviceElement]): Vector[DevicePrimitive] = elements.flatMap {
        case DeviceElement.Mark(mark)               => Vector(mark)
        case DeviceElement.Group(_, _, _, children) => marks(children)
        case DeviceElement.Annotated(_, children)   => marks(children)
      }
      assertEquals(marks(device.elements).count(_.isInstanceOf[DevicePrimitive.Image]), 1)
    }
  }

  test("missing mask and NaN retain grid slots, use declared colour and do not train to zero") {
    val missing = Rgba.unsafe(255, 0, 255, 0.5)
    val raster = ok(plot(field).geomRaster(missing = _.value == 1, missingColor = missing).resolve)
    assertEquals(image(raster).image.pixelUnsafe(0, 1), Rgba32.fromRgba(missing))
    assertNotEquals(image(raster).image.pixelUnsafe(0, 1), image(raster).image.pixelUnsafe(1, 1))
    val rows = field.cells.map(c => if c.value == 1 then c.copy(value = Double.NaN) else c)
    val nan = ok(plot(rows).aes(_.x, _.y).geomRaster(missingColor = missing).resolve)
    assertEquals(image(nan).image, image(raster).image)
    assertEquals(nan.guides, raster.guides)
    assertEquals(raster.droppedRows.size, 0)
    val transparent = image(ok(plot(field).geomRaster(missing = _.value == 1).resolve))
    assertEquals(transparent.image.pixelUnsafe(0, 1).alpha, 0)
  }

  test("alpha and interpolation are explicit and invalid alpha is checked") {
    val normal = image(ok(plot(field).geomRaster().resolve))
    val faded = image(
      ok(plot(field).geomRaster(alpha = 0.25, interpolation = RasterInterpolation.Smooth).resolve)
    )
    assertEquals(faded.interpolation, RasterInterpolation.Smooth)
    assertEquals(normal.interpolation, RasterInterpolation.Nearest)
    assertEquals(faded.image.pixelUnsafe(0, 0).alpha, 64)
    assert(plot(field).geomRaster(alpha = Double.NaN).build.isLeft)
  }

  test("facets share the same fill domain and colours as heatmaps") {
    val raster = ok(plot(field).geomRaster().facetWrap(c => c.yIndex.toString).resolve)
    val tiled = ok(plot(field).geomHeatmap().facetWrap(c => c.yIndex.toString).resolve)
    assertEquals(raster.guides, tiled.guides)
    raster.facetPanels.zip(tiled.facetPanels).foreach { (r, t) =>
      val img = r.layers.head.grobs.head.asInstanceOf[Grob.Image]
      assertEquals(img.image.height, 1)
      t.layers.head.grobs.zipWithIndex.foreach { (g, x) =>
        assertEquals(
          img.image.pixelUnsafe(x, 0),
          Rgba32.fromRgba(g.asInstanceOf[Grob.Rect].gp.fill.get)
        )
      }
    }
  }

  test("vertex-centered samples cover the same half-step expanded extent as tiles") {
    val vertices = ScalarField2D.unsafe(
      RegularGridAxis.vertexCenteredUnsafe(-2, 2, 3),
      RegularGridAxis.vertexCenteredUnsafe(0, 6, 2),
      field.samples
    )
    assertEquals(
      ok(plot(vertices).geomRaster().resolve).layout,
      ok(plot(vertices).geomHeatmap().resolve).layout
    )
  }

  test("incomplete or irregular grids fail explicitly") {
    assert(plot(field.cells.drop(1)).aes(_.x, _.y).geomRaster().resolve.isLeft)
    val irregular = field.cells.updated(0, field.cells.head.copy(width = 0.4))
    assert(plot(irregular).aes(_.x, _.y).geomRaster().resolve.isLeft)
  }

  test("coordinate flip transposes raster pixels and extent like the tile path") {
    val plain = image(ok(plot(field).geomRaster().resolve))
    val flipped = ok(plot(field).geomRaster().coord(Coord.Flipped()).resolve)
    val tiles = ok(plot(field).geomHeatmap().coord(Coord.Flipped()).resolve)
    assertEquals(flipped.layout, tiles.layout)
    val img = image(flipped).image
    assertEquals((img.width, img.height), (2, 3))
    for y <- 0 until 3; x <- 0 until 2 do
      assertEquals(img.pixelUnsafe(x, y), plain.image.pixelUnsafe(2 - y, 1 - x))
  }

  test("fixed fill limits censor to missing and squish identically to tiles") {
    val palette = Theme.default.palettes.continuousPalette
    for oob <- Vector(OobPolicy.Censor, OobPolicy.Squish) do
      val scale = ok(ContinuousScale.fixed("fixed", Vector(2.0, 16.0), palette, oob = oob))
      val mapping =
        AesSpec.empty[ScalarCell].updated(Aesthetic.Fill, AesValue.scaled(_.value, scale))
      val r = ok(
        Plot(field.cells).addLayer(
          Layer.raster[ScalarCell](_.x, _.y, _.width, _.height, mapping = mapping)
        )
      )
      val t = ok(
        Plot(field.cells).addLayer(
          Layer.tile[ScalarCell](_.x, _.y, _.width, _.height, mapping = mapping)
        )
      )
      val raster = image(ok(PlotCompiler.resolve(r)))
      val tiled = ok(PlotCompiler.resolve(t))
      tiled.layers.head.rows.zip(tiled.layers.head.grobs).foreach { (row, grob) =>
        val cell = row.source.asInstanceOf[ScalarCell]
        assertEquals(
          raster.image.pixelUnsafe(cell.xIndex, 1 - cell.yIndex),
          Rgba32.fromRgba(grob.asInstanceOf[Grob.Rect].gp.fill.get)
        )
      }
      if oob == OobPolicy.Censor then
        assertEquals(raster.image.pixelUnsafe(0, 1).alpha, 0)
        assertEquals(raster.image.pixelUnsafe(2, 0).alpha, 0)
  }

  test("large coordinate origins do not hide irregular cell spacing") {
    val rows = field.cells.map(c => c.copy(x = c.x + 1e12))
    assert(plot(rows).aes(_.x, _.y).geomRaster().resolve.isRight)
    val bad = rows.updated(0, rows.head.copy(x = rows.head.x + 0.125))
    assert(plot(bad).aes(_.x, _.y).geomRaster().resolve.isLeft)
  }

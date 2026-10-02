package intaglio

/** One raster, two palettes: task columns on a diverging-style ramp, nuisance columns on a muted
  * grey ramp. Each class trains its own domain, maps through its own palette, and gets its own
  * colorbar; judged on the drawn image's pixels and the derived guides.
  */
class ClassedRasterSuite extends munit.FunSuite:
  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  private val blue = Rgba.unsafe(30, 60, 200)
  private val red = Rgba.unsafe(200, 40, 30)
  private val dark = Rgba.unsafe(60, 60, 60)
  private val light = Rgba.unsafe(220, 220, 220)

  private val classes = Vector(
    ColorClass.unsafe("task", Palette.gradient(blue, red)),
    ColorClass.unsafe("nuisance", Palette.gradient(dark, light))
  )

  /** 4 columns x 3 rows; columns 0-1 are task, 2-3 nuisance. Task values span -3..3, nuisance
    * values span 100..112, so a single shared domain would wash one class out entirely.
    */
  private val field = ok(
    for
      x <- RegularGridAxis.cellCentered(0, 4, 4)
      y <- RegularGridAxis.cellCentered(0, 3, 3)
      field <- ScalarField2D.tabulate(x, y) { (x, y) =>
        if x < 2 then (x - 1.0) * 3.0 + (y - 1.5) else 100.0 + x * 2.0 + y * 2.0
      }
    yield field
  )

  private def classOf(cell: ScalarCell): Int = if cell.xIndex < 2 then 0 else 1

  private val context = RenderContext.unsafe(width = 520, height = 640)

  private def builder =
    plot(field).geomRasterByClass(classOf, classes, name = "design")

  private def image(plan: RenderPlan): RasterImage =
    def find(element: DeviceElement): Option[RasterImage] =
      element match
        case DeviceElement.Mark(i: DevicePrimitive.Image) => Some(i.image)
        case DeviceElement.Mark(_)                        => None
        case DeviceElement.Group(_, _, _, children)       => children.view.flatMap(find).headOption
        case DeviceElement.Annotated(_, children)         => children.view.flatMap(find).headOption
    ok(plan.deviceScene).elements.view.flatMap(find).headOption.getOrElse(fail("no raster image"))

  private def pixel(raster: RasterImage, x: Int, y: Int): Rgba32 =
    raster.pixel(x, y).getOrElse(fail(s"no pixel at ($x, $y)"))

  test("each class maps its own extremes to its own palette's endpoints") {
    val raster = image(ok(builder.renderPlan(context)))
    assertEquals((raster.dimensions.width, raster.dimensions.height), (4, 3))
    val pixels = for y <- 0 until 3; x <- 0 until 4 yield (x, y) -> pixel(raster, x, y)
    val task = pixels.collect { case ((x, _), p) if x < 2 => p }.toSet
    val nuisance = pixels.collect { case ((x, _), p) if x >= 2 => p }.toSet
    // Both palettes reach both of their endpoints: neither class is compressed by the other.
    assert(task.contains(Rgba32.fromRgba(blue)) && task.contains(Rgba32.fromRgba(red)), task)
    assert(
      nuisance.contains(Rgba32.fromRgba(dark)) && nuisance.contains(Rgba32.fromRgba(light)),
      nuisance
    )
    // Grey cells are grey; task cells are not.
    assert(nuisance.forall(p => p.red == p.green && p.green == p.blue), nuisance)
    assert(task.forall(p => !(p.red == p.green && p.green == p.blue)), task)
  }

  test("each class with values gets its own colorbar, titled by its class") {
    val trained = ok(builder.resolve(context))
    val colorbars = trained.guides.map(_.spec).collect { case c: GuideSpec.Colorbar => c }
    assertEquals(colorbars.flatMap(_.title), Vector("task", "nuisance"))
    assertEquals(
      colorbars.flatMap(_.name).map(_.value),
      Vector("design-class-0-colorbar", "design-class-1-colorbar")
    )
    // Each class's bar is exactly the bar an ordinary raster of that class's columns alone draws.
    def ordinary(columns: Range, palette: Palette[Rgba]): GuideSpec.Colorbar =
      val sub = ok(
        for
          x <- RegularGridAxis.cellCentered(columns.start, columns.end + 1, columns.length)
          y <- RegularGridAxis.cellCentered(0, 3, 3)
          sub <- ScalarField2D.tabulate(x, y)((xc, yc) =>
            field.cells.find(c => c.x == xc && c.y == yc).fold(Double.NaN)(_.value)
          )
        yield sub
      )
      ok(plot(sub).geomRaster(palette, name = "design").resolve(context)).guides
        .map(_.spec)
        .collectFirst { case c: GuideSpec.Colorbar => c }
        .getOrElse(fail("no ordinary colorbar"))
    val task = ordinary(0 to 1, Palette.gradient(blue, red))
    val nuisance = ordinary(2 to 3, Palette.gradient(dark, light))
    assertEquals(colorbars(0).colors, task.colors)
    assertEquals(colorbars(0).ticks, task.ticks)
    assertEquals(colorbars(1).colors, nuisance.colors)
    assertEquals(colorbars(1).ticks, nuisance.ticks)
  }

  test("a class with no cells draws nothing and has no colorbar") {
    val trained = ok(
      plot(field).geomRasterByClass(_ => 0, classes, name = "design").resolve(context)
    )
    val titles = trained.guides.map(_.spec).collect { case c: GuideSpec.Colorbar => c.title }
    assertEquals(titles.flatten, Vector("task"))
  }

  test("masked cells and unknown classes take the missing colour") {
    val missing = Rgba.unsafe(255, 0, 255)
    val raster = image(
      ok(
        plot(field)
          .geomRasterByClass(
            cell => if cell.yIndex == 0 && cell.xIndex == 3 then 7 else classOf(cell),
            classes,
            missingColor = missing,
            missing = cell => cell.xIndex == 0 && cell.yIndex == 0
          )
          .renderPlan(context)
      )
    )
    // Image rows are top-first: grid row 0 is the bottom image row.
    assertEquals(pixel(raster, 0, 2), Rgba32.fromRgba(missing))
    assertEquals(pixel(raster, 3, 2), Rgba32.fromRgba(missing))
    assertNotEquals(pixel(raster, 1, 2), Rgba32.fromRgba(missing))
  }

  test("class specifications are checked") {
    assert(ClassedColorScaleSpec("design", Vector.empty).isLeft)
    assert(ClassedColorScaleSpec("design", Vector(classes(0), classes(0))).isLeft)
  }

  test("rendering is deterministic") {
    assertEquals(image(ok(builder.renderPlan(context))), image(ok(builder.renderPlan(context))))
  }

package intaglio

/** Numerical court for the prefix separation table published on [[DiscretePalette.okabeIto]], and
  * for the default theme palette it deliberately does not replace.
  *
  * Same observer model and metric as [[DivergingPaletteEvidenceSuite]]: Brettel, Viénot and Mollon
  * (1997) through [[ColorVision]], CIE76 through [[ColorSeparation]].
  */
class DiscretePaletteEvidenceSuite extends munit.FunSuite:

  private val colors = DiscretePalette.okabeItoColors

  private def floorOf(palette: Vector[Rgba], size: Int): Double =
    val prefix = palette.take(size)
    ColorVision.values.toVector.flatMap(ColorSeparation.closest(prefix, _)).map(_.deltaE76).min

  private def prefixFloor(size: Int): Double =
    floorOf(colors, size)

  private def assertPinned(measured: Double, documented: Double, what: String): Unit =
    assert(
      math.abs(measured - documented) <= 0.05,
      s"$what: documented $documented, measured $measured"
    )

  test("the published prefix floors hold"):
    val documented = Vector(79.2, 66.1, 23.5, 19.4, 17.0, 11.2, 10.9)

    for (floor, index) <- documented.zipWithIndex do
      assertPinned(prefixFloor(index + 2), floor, s"prefix of ${index + 2}")

  test("six series clear the series floor and the seventh does not"):
    assert(prefixFloor(6) >= ColorSeparation.SeriesFloor)
    assert(prefixFloor(7) >= ColorSeparation.SeriesFloor)
    assert(prefixFloor(8) >= ColorSeparation.SeriesFloor)
    // Seven and eight clear it only barely; the doc comment says so rather than claiming headroom.
    assert(prefixFloor(7) < prefixFloor(6))
    assert(prefixFloor(8) < prefixFloor(7))

  test("no admissible reordering of these colors has a better prefix floor"):
    val documented = (2 to colors.length).map(prefixFloor).toVector

    // Separations depend only on the pair, so measure the 28 pairs once per observer and let the
    // search over all 40 320 orderings be array lookups.
    val visions = ColorVision.values.toVector
    val separation =
      Array.tabulate(visions.length, colors.length, colors.length)((vision, left, right) =>
        ColorSeparation.between(visions(vision), colors(left), colors(right))
      )

    def floorOfOrder(order: Vector[Int], size: Int): Double =
      (for
        vision <- visions.indices
        left <- 0 until size
        right <- (left + 1) until size
      yield separation(vision)(order(left))(order(right))).min

    def keyOf(order: Vector[Int]): Vector[Double] =
      (2 to order.length).map(floorOfOrder(order, _)).toVector

    // The published constraints: black leads, and nothing before the sixth may be so light that a
    // thin series stroke loses contrast against a light panel.
    def admissible(order: Vector[Int]): Boolean =
      order.head == 0 && order
        .take(5)
        .forall(index => ColorSeparation.lightness(colors(index)) <= 80.0)

    val ordering = math.Ordering.Implicits.seqOrdering[Vector, Double]
    val best =
      colors.indices.toVector.permutations
        .filter(admissible)
        .map(keyOf)
        .reduce((left, right) => if ordering.gt(right, left) then right else left)

    assertEquals(ordering.compare(documented, best), 0, clues(documented, best))

  test("the first five keep lightness contrast against a light panel"):
    val bright = colors.take(5).filter(color => ColorSeparation.lightness(color) > 80.0)

    assertEquals(bright, Vector.empty[Rgba], "a low-contrast color moved into the first five")
    assert(ColorSeparation.lightness(colors(5)) > 80.0, "yellow is expected sixth, not earlier")

  test("the palette refuses a ninth level rather than reusing a color"):
    assertEquals(DiscretePalette.okabeIto.capacity, Some(8))
    assertEquals(DiscretePalette.okabeIto.overflowPolicy, PaletteOverflowPolicy.Reject)
    assertEquals(
      DiscretePalette.okabeIto.validateDomain("color", 9).left.toOption,
      Some(GraphicsError.DiscretePaletteOverflow("color", 9, 8))
    )
    assertEquals(DiscretePalette.okabeIto.validateDomain("color", 8), Right(()))
    assertEquals(DiscretePalette.okabeIto(0, 8), Rgba.unsafe(0, 0, 0))

  test("no two colors in the palette are exactly equal"):
    assertEquals(colors.distinct.length, colors.length)

  test("the default theme palette is the measured one, and the palette it replaced was not"):
    val default = Theme.defaultPalettes.discrete

    assertEquals(default, colors.take(6))

    val documented = Vector(79.2, 66.1, 23.5, 19.4, 17.0)
    for (floor, index) <- documented.zipWithIndex do
      assertPinned(
        floorOf(default, index + 2),
        floor,
        s"default theme palette prefix of ${index + 2}"
      )

    assert(floorOf(default, 6) >= ColorSeparation.SeriesFloor)

    // The first six tab10 colours, which were the default until this change. Kept here as a
    // literal so the reason for replacing them stays measured rather than remembered: protanopia
    // brings tab10's orange and green to 5.6 from three series on.
    val tab10 = Vector(
      Rgba.unsafe(31, 119, 180),
      Rgba.unsafe(255, 127, 14),
      Rgba.unsafe(44, 160, 44),
      Rgba.unsafe(214, 39, 40),
      Rgba.unsafe(148, 103, 189),
      Rgba.unsafe(140, 86, 75)
    )
    assertPinned(floorOf(tab10, 3), 5.6, "replaced tab10 palette prefix of 3")
    assertPinned(floorOf(tab10, 6), 5.6, "replaced tab10 palette prefix of 6")
    assert(floorOf(tab10, 3) < ColorSeparation.SeriesFloor)
    assert(floorOf(default, 6) > floorOf(tab10, 6) * 2.5)

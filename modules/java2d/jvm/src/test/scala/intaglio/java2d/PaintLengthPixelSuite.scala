package intaglio.java2d

import intaglio.*
import java.awt.image.BufferedImage

/** Pixel measurements of dash and hatch geometry at 1x (96 ppi) and 2x (192 ppi). A layout-unit
  * rhythm must cover twice as many device pixels at 2x, so its physical size is unchanged; an
  * explicit device-pixel rhythm must not.
  */
class PaintLengthPixelSuite extends munit.FunSuite:
  private def raster(grob: Grob, scale: Int): BufferedImage =
    val options = Java2DOptions.unsafe(
      width = 200 * scale,
      height = 60 * scale,
      pixelsPerInch = 96.0 * scale,
      deviceScale = scale.toDouble
    )
    val program = Java2DRenderer
      .compile(Scene(Vector(grob)), options)
      .fold(e => fail(e.message), identity)
    val image = new BufferedImage(200 * scale, 60 * scale, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try Java2DRenderer.draw(program, graphics)
    finally graphics.dispose()
    image

  /** Lengths of the interior painted runs along a row or column, ignoring the clipped first and
    * last runs.
    */
  private def runs(painted: Vector[Boolean]): Vector[Int] =
    val lengths = Vector.newBuilder[Int]
    var current = 0
    painted.foreach { on =>
      if on then current += 1
      else if current > 0 then
        lengths += current
        current = 0
    }
    if current > 0 then lengths += current
    lengths.result().drop(1).dropRight(1)

  private def rowRuns(image: BufferedImage, y: Int): Vector[Int] =
    runs((0 until image.getWidth).toVector.map(x => (image.getRGB(x, y) >>> 24) > 128))

  private def columnStarts(image: BufferedImage, x: Int): Vector[Int] =
    val painted = (0 until image.getHeight).toVector.map(y => (image.getRGB(x, y) >>> 24) > 128)
    painted.indices.filter(y => painted(y) && (y == 0 || !painted(y - 1))).toVector

  private def line(lineType: LineType) =
    Grob
      .lines(
        Vector(Point.npcUnsafe(0.0, 0.5), Point.npcUnsafe(1.0, 0.5)),
        gp = GraphicParams
          .unsafe(stroke = Some(Rgba.Black), lineType = lineType)
          .withStrokeWidth(StrokeWidth.pointsUnsafe(3.0))
      )
      .orThrow

  test("a dashed line has the same physical dash at 1x and 2x") {
    val one = rowRuns(raster(line(LineType.Dashed), 1), 30)
    val two = rowRuns(raster(line(LineType.Dashed), 2), 60)
    assert(one.nonEmpty && two.nonEmpty)
    assert(one.forall(run => math.abs(run - 6) <= 1), s"1x dashes $one")
    assert(two.forall(run => math.abs(run - 12) <= 1), s"2x dashes $two")
    // One device pixel of tolerance at the finer density: 6/96 in either way.
    assert(
      math.abs(one.sum.toDouble / one.length / 96.0 - two.sum.toDouble / two.length / 192.0)
        <= 1.0 / 192.0
    )
  }

  test("an explicit device-pixel dash keeps its device length at 2x") {
    val literal = LineType.Custom(DashPattern.Dashed.withUnit(PaintLengthUnit.DevicePixel))
    val two = rowRuns(raster(line(literal), 2), 60)
    assert(two.nonEmpty && two.forall(run => math.abs(run - 6) <= 1), s"2x literal dashes $two")
  }

  test("a hatched fill has the same physical spacing at 1x and 2x") {
    val paint = PatternPaint(
      PatternRecipe.parallelRules(RuleOrientation.Horizontal, 12.0, 2.0).orThrow,
      Rgba.Black
    )
    val fill = Grob
      .rect(
        Point.npcUnsafe(0.5, 0.5),
        Size.npcUnsafe(1.0, 1.0),
        gp = GraphicParams.unsafe(stroke = None).withPatternFill(paint)
      )
      .orThrow
    // The first rule may be cut by the fill's top edge, so periods start at the second rule.
    def periods(starts: Vector[Int]) =
      starts.drop(1).sliding(2).collect { case Vector(a, b) => b - a }
    val one = periods(columnStarts(raster(fill, 1), 100)).toVector
    val two = periods(columnStarts(raster(fill, 2), 200)).toVector
    assert(one.size >= 3 && one.forall(_ == 12), s"1x rule periods $one")
    assert(two.size >= 3 && two.forall(_ == 24), s"2x rule periods $two")
  }

package intaglio.java2d

import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths}
import javax.imageio.ImageIO
import intaglio.*

class GoldenRegressionSuite extends munit.FunSuite:
  test("pinned font bytes and renderer settings satisfy the Java2D perceptual golden") {
    val expected = GoldenFixture.expected
    val actual = GoldenFixture.render()
    val full = PixelDifference.compare(expected, actual)
    val geometry =
      PixelDifference.compare(expected, actual, minimumY = GoldenFixture.textBandHeight)
    val problems = Vector(
      Option.when(full.changedFraction > GoldenFixture.fullThreshold.changedFraction)(
        s"full changed fraction ${full.changedFraction} > ${GoldenFixture.fullThreshold.changedFraction}"
      ),
      Option.when(full.meanChannelError > GoldenFixture.fullThreshold.meanChannelError)(
        s"full mean channel error ${full.meanChannelError} > ${GoldenFixture.fullThreshold.meanChannelError}"
      ),
      Option.when(geometry.changedFraction > GoldenFixture.geometryThreshold.changedFraction)(
        s"geometry changed fraction ${geometry.changedFraction} > ${GoldenFixture.geometryThreshold.changedFraction}"
      ),
      Option.when(geometry.meanChannelError > GoldenFixture.geometryThreshold.meanChannelError)(
        s"geometry mean channel error ${geometry.meanChannelError} > ${GoldenFixture.geometryThreshold.meanChannelError}"
      )
    ).flatten

    if problems.nonEmpty then
      val artifacts = GoldenArtifacts.write(expected, actual)
      fail(
        s"${problems.mkString("; ")}; max channel error=${full.maximumChannelError}; review $artifacts"
      )
  }

  test("the perceptual thresholds reject a material non-text regression") {
    val expected = GoldenFixture.expected
    val changed = copy(expected)
    var y = 80
    while y < 100 do
      var x = 80
      while x < 100 do
        changed.setRGB(x, y, 0xff000000)
        x += 1
      y += 1
    val difference =
      PixelDifference.compare(expected, changed, minimumY = GoldenFixture.textBandHeight)

    assert(difference.changedFraction > GoldenFixture.geometryThreshold.changedFraction)
  }

  test("fixed font resolution does not consult the host family registry") {
    val font = GoldenFixture.font
    val resolved = Java2DFontResolver.fixed(font).resolve(Some("missing-host-family"), 17.0)

    assertEquals(resolved.getFontName, font.getFontName)
    assertEqualsDouble(resolved.getSize2D.toDouble, 17.0, 0.0)
  }

  private def copy(source: BufferedImage): BufferedImage =
    val out = new BufferedImage(source.getWidth, source.getHeight, BufferedImage.TYPE_INT_ARGB)
    out.setRGB(
      0,
      0,
      source.getWidth,
      source.getHeight,
      source.getRGB(0, 0, source.getWidth, source.getHeight, null, 0, source.getWidth),
      0,
      source.getWidth
    )
    out

private[java2d] final case class PerceptualThreshold(
    changedFraction: Double,
    meanChannelError: Double
)

private[java2d] final case class PixelDifference(
    comparedPixels: Int,
    changedPixels: Int,
    totalChannelError: Long,
    maximumChannelError: Int
):
  def changedFraction: Double = changedPixels.toDouble / comparedPixels.toDouble
  def meanChannelError: Double = totalChannelError.toDouble / (comparedPixels.toDouble * 4.0)

private[java2d] object PixelDifference:
  def compare(
      expected: BufferedImage,
      actual: BufferedImage,
      minimumY: Int = 0
  ): PixelDifference =
    require(expected.getWidth == actual.getWidth && expected.getHeight == actual.getHeight)
    require(minimumY >= 0 && minimumY < expected.getHeight)
    var compared = 0
    var changed = 0
    var totalError = 0L
    var maximumError = 0
    var y = minimumY
    while y < expected.getHeight do
      var x = 0
      while x < expected.getWidth do
        val left = expected.getRGB(x, y)
        val right = actual.getRGB(x, y)
        if left != right then changed += 1
        var shift = 0
        while shift <= 24 do
          val error = math.abs(((left >>> shift) & 0xff) - ((right >>> shift) & 0xff))
          totalError += error.toLong
          if error > maximumError then maximumError = error
          shift += 8
        compared += 1
        x += 1
      y += 1
    PixelDifference(compared, changed, totalError, maximumError)

private[java2d] object GoldenFixture:
  val width = 360
  val height = 240
  val textBandHeight = 48
  val resource = "/intaglio/java2d/golden/conformance.png"
  val repositoryPath: Path = Paths.get(
    "modules/java2d/jvm/src/test/resources/intaglio/java2d/golden/conformance.png"
  )
  val fullThreshold = PerceptualThreshold(changedFraction = 0.02, meanChannelError = 0.5)
  val geometryThreshold = PerceptualThreshold(changedFraction = 0.001, meanChannelError = 0.03)

  lazy val font: Font =
    val path = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    val input = Option(getClass.getResourceAsStream(path)).getOrElse(
      throw new IllegalStateException(s"missing pinned golden font $path")
    )
    try Font.createFont(Font.TRUETYPE_FONT, input)
    finally input.close()

  lazy val expected: BufferedImage =
    val input = Option(getClass.getResourceAsStream(resource)).getOrElse(
      throw new IllegalStateException(
        s"missing golden $resource; run tools/update-java2d-goldens.sh and review the artifact"
      )
    )
    try Option(ImageIO.read(input)).getOrElse(throw new IllegalStateException(s"invalid $resource"))
    finally input.close()

  val exportOptions: Java2DExportOptions = Java2DExportOptions(
    background = Java2DBackground.Solid(Rgba.White),
    renderingHints = Java2DRenderingHints(
      geometry = Java2DAntialiasing.Disabled,
      text = Java2DAntialiasing.Disabled
    )
  )

  def render(): BufferedImage =
    val context = RenderContext.unsafe(
      width,
      height,
      pixelsPerInch = 96.0,
      fontRegistry = FontRegistry(_ => Some("Pinned Golden Sans"))
    )
    Java2DRenderer
      .renderImage(
        RenderPlan(scene, context),
        exportOptions,
        Java2DFontResolver.fixed(font)
      )
      .orThrow

  private lazy val scene: Scene =
    val navy = Rgba.unsafe(27, 56, 96)
    val blue = Rgba.unsafe(57, 106, 177)
    val orange = Rgba.unsafe(218, 124, 48)
    val pale = Rgba.unsafe(240, 244, 249)
    val hatch = PatternPaint(
      PatternRecipe.crossHatch(45.0, 8.0, 1.0).orThrow,
      navy,
      Some(Rgba.unsafe(225, 233, 242))
    )
    val panel = Grob
      .rect(
        Point.npcUnsafe(0.5, 0.43),
        Size.npcUnsafe(0.86, 0.62),
        gp = GraphicParams.unsafe(stroke = Some(navy), fill = Some(pale), lineWidth = 2.0)
      )
      .orThrow
    val patterned = Grob
      .rect(
        Point.npcUnsafe(0.24, 0.43),
        Size.npcUnsafe(0.24, 0.3),
        gp = GraphicParams.unsafe(stroke = Some(navy), lineWidth = 1.0).withPatternFill(hatch)
      )
      .orThrow
    val line = Grob
      .lines(
        Vector(
          Point.npcUnsafe(0.1, 0.18),
          Point.npcUnsafe(0.36, 0.62),
          Point.npcUnsafe(0.62, 0.35),
          Point.npcUnsafe(0.88, 0.72)
        ),
        gp = GraphicParams.unsafe(stroke = Some(blue), lineWidth = 3.0)
      )
      .orThrow
    val points = Grob
      .pointBatch(
        Vector(
          Point.npcUnsafe(0.36, 0.62),
          Point.npcUnsafe(0.62, 0.35),
          Point.npcUnsafe(0.78, 0.58),
          Point.npcUnsafe(0.88, 0.72)
        ),
        sizes = BatchColumn.Values(Vector.fill(4)(ExtentExpr.pointsUnsafe(7.0))),
        shapes = BatchColumn.Values(
          Vector(PointShape.Circle, PointShape.Square, PointShape.Triangle, PointShape.Cross)
        ),
        graphicParams = BatchColumn.Values(
          Vector.fill(4)(
            GraphicParams.unsafe(stroke = Some(navy), fill = Some(orange), lineWidth = 2.0)
          )
        )
      )
      .orThrow
    val title = Grob
      .text(
        "Intaglio golden court",
        Point.npcUnsafe(0.5, 0.91),
        anchor = Anchor.Center,
        gp = GraphicParams.unsafe(
          stroke = None,
          fill = Some(navy),
          fontFamily = Some("Pinned Golden Sans"),
          fontSize = Length.pointsUnsafe(16.0)
        )
      )
      .orThrow
    Scene(Vector(panel, patterned, line, points, title))

private[java2d] object GoldenArtifacts:
  def write(expected: BufferedImage, actual: BufferedImage): Path =
    val output = Paths.get("target/golden-failures/java2d")
    Files.createDirectories(output)
    ImageIO.write(expected, "png", output.resolve("expected.png").toFile)
    ImageIO.write(actual, "png", output.resolve("actual.png").toFile)
    ImageIO.write(difference(expected, actual), "png", output.resolve("difference.png").toFile)
    output

  private def difference(expected: BufferedImage, actual: BufferedImage): BufferedImage =
    val out = new BufferedImage(expected.getWidth, expected.getHeight, BufferedImage.TYPE_INT_ARGB)
    var y = 0
    while y < expected.getHeight do
      var x = 0
      while x < expected.getWidth do
        val left = expected.getRGB(x, y)
        val right = actual.getRGB(x, y)
        val pixel =
          if left == right then
            val gray = (((left >>> 16) & 0xff) + ((left >>> 8) & 0xff) + (left & 0xff)) / 3
            (0x30 << 24) | (gray << 16) | (gray << 8) | gray
          else 0xffff0033
        out.setRGB(x, y, pixel)
        x += 1
      y += 1
    out

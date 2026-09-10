package intaglio.java2d

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths}
import javax.imageio.ImageIO

class FeatureVisualRegressionSuite extends munit.FunSuite:
  test("recent visual features satisfy pinned perceptual goldens") {
    FeatureVisualFixtures.cases.foreach { example =>
      val expected = FeatureVisualFixtures.expected(example)
      val actual = FeatureVisualFixtures.render(example)
      val full = FeaturePixelDifference.compare(expected, actual)
      val geometry =
        FeaturePixelDifference.compare(expected, actual, Some(example.geometryBounds))
      val problems = Vector(
        Option.when(full.changedFraction > FeatureVisualFixtures.fullThreshold.changedFraction)(
          s"full changed fraction ${full.changedFraction} > ${FeatureVisualFixtures.fullThreshold.changedFraction}"
        ),
        Option.when(full.meanChannelError > FeatureVisualFixtures.fullThreshold.meanChannelError)(
          s"full mean channel error ${full.meanChannelError} > ${FeatureVisualFixtures.fullThreshold.meanChannelError}"
        ),
        Option.when(geometry.changedFraction > example.geometryThreshold.changedFraction)(
          s"panel changed fraction ${geometry.changedFraction} > ${example.geometryThreshold.changedFraction}"
        ),
        Option.when(geometry.meanChannelError > example.geometryThreshold.meanChannelError)(
          s"panel mean channel error ${geometry.meanChannelError} > ${example.geometryThreshold.meanChannelError}"
        )
      ).flatten

      if problems.nonEmpty then
        val artifacts = FeatureGoldenArtifacts.write(example.name, expected, actual)
        fail(
          s"${example.name}: ${problems.mkString("; ")}; max channel error=${full.maximumChannelError}; review $artifacts"
        )
    }
  }

  test("every feature fixture is visible and visually distinct") {
    val signatures = FeatureVisualFixtures.cases.map { example =>
      val image = FeatureVisualFixtures.render(example)
      val nonWhite = countNonWhite(image)
      assert(nonWhite > 2000, s"${example.name} rendered only $nonWhite non-white pixels")
      image.getRGB(0, 0, image.getWidth, image.getHeight, null, 0, image.getWidth).toVector
    }
    assertEquals(signatures.distinct.length, FeatureVisualFixtures.cases.length)
  }

  test("the feature threshold rejects a material panel regression") {
    val expected = FeatureVisualFixtures.expected(FeatureVisualFixtures.cases.head)
    val changed = copy(expected)
    var y = 120
    while y < 220 do
      var x = 180
      while x < 280 do
        changed.setRGB(x, y, 0xff000000)
        x += 1
      y += 1
    val difference = FeaturePixelDifference.compare(expected, changed)

    assert(difference.changedFraction > FeatureVisualFixtures.fullThreshold.changedFraction)
  }

  /** Every case's own geometry threshold must still reject a material change inside its own
    * compared region. Two cases carry a relaxed threshold because their region is mostly text and
    * AWT rasterizes glyphs differently on each host; this test is what keeps that relaxation from
    * becoming a threshold that no longer judges anything.
    */
  test("every case's geometry threshold rejects a material change in its own region") {
    FeatureVisualFixtures.cases.foreach { example =>
      val expected = FeatureVisualFixtures.expected(example)
      val changed = copy(expected)
      val bounds = example.geometryBounds
      // A tenth of each axis of the compared region, centred in it: small enough that a threshold
      // meant to absorb glyph noise cannot swallow it by being generous, large enough that any
      // usable threshold must catch it.
      val width = (bounds.maximumXExclusive - bounds.minimumX) / 10
      val height = (bounds.maximumYExclusive - bounds.minimumY) / 10
      val startX = bounds.minimumX + (bounds.maximumXExclusive - bounds.minimumX - width) / 2
      val startY = bounds.minimumY + (bounds.maximumYExclusive - bounds.minimumY - height) / 2
      var y = startY
      while y < startY + height do
        var x = startX
        while x < startX + width do
          changed.setRGB(x, y, 0xff000000)
          x += 1
        y += 1
      val difference =
        FeaturePixelDifference.compare(expected, changed, Some(bounds))

      assert(
        difference.changedFraction > example.geometryThreshold.changedFraction ||
          difference.meanChannelError > example.geometryThreshold.meanChannelError,
        s"${example.name}: a ${width}x$height black block inside its own region produced " +
          s"changed fraction ${difference.changedFraction} and mean channel error " +
          s"${difference.meanChannelError}, neither above its threshold " +
          s"${example.geometryThreshold}"
      )
    }
  }

  private def countNonWhite(image: BufferedImage): Int =
    var count = 0
    var y = 0
    while y < image.getHeight do
      var x = 0
      while x < image.getWidth do
        if image.getRGB(x, y) != 0xffffffff then count += 1
        x += 1
      y += 1
    count

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

private[java2d] object FeaturePixelDifference:
  def compare(
      expected: BufferedImage,
      actual: BufferedImage,
      bounds: Option[PixelBounds] = None
  ): PixelDifference =
    require(expected.getWidth == actual.getWidth && expected.getHeight == actual.getHeight)
    val region = bounds.getOrElse(PixelBounds(0, 0, expected.getWidth, expected.getHeight))
    require(region.minimumX >= 0 && region.minimumY >= 0)
    require(region.maximumXExclusive <= expected.getWidth)
    require(region.maximumYExclusive <= expected.getHeight)
    require(
      region.minimumX < region.maximumXExclusive && region.minimumY < region.maximumYExclusive
    )
    var compared = 0
    var changed = 0
    var totalError = 0L
    var maximumError = 0
    var y = region.minimumY
    while y < region.maximumYExclusive do
      var x = region.minimumX
      while x < region.maximumXExclusive do
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

private[java2d] object FeatureGoldenArtifacts:
  def write(name: String, expected: BufferedImage, actual: BufferedImage): Path =
    val output = Paths.get("target/golden-failures/java2d/features").resolve(name)
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

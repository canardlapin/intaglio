package intaglio

import java.awt.Font
import java.awt.font.{FontRenderContext, TextLayout}
import java.awt.geom.AffineTransform

/** Native-layout witnesses for the tracking adapter. These deliberately compare against AWT's
  * paragraph shaper rather than reconstructing its cluster or bidi policy in the assertions.
  */
class TrackedGlyphsSuite extends munit.FunSuite:
  private val font = new Font("Serif", Font.PLAIN, 36)
  private val frc = new FontRenderContext(new AffineTransform(), true, true)

  private def native(text: String) =
    font.layoutGlyphVector(frc, text.toCharArray, 0, text.length, Font.LAYOUT_LEFT_TO_RIGHT)

  private def positions(vector: java.awt.font.GlyphVector): Vector[(Int, Double, Double)] =
    (0 until vector.getNumGlyphs).toVector.map { index =>
      val point = vector.getGlyphPosition(index)
      (vector.getGlyphCharIndex(index), point.getX, point.getY)
    }

  private def assertSamePositions(
      actual: java.awt.font.GlyphVector,
      expected: java.awt.font.GlyphVector,
      delta: Double = 1e-8
  ): Unit =
    assertEquals(actual.getNumGlyphs, expected.getNumGlyphs)
    positions(actual).zip(positions(expected)).foreach {
      case ((actualIndex, actualX, actualY), (expectedIndex, expectedX, expectedY)) =>
        assertEquals(actualIndex, expectedIndex)
        assertEqualsDouble(actualX, expectedX, delta)
        assertEqualsDouble(actualY, expectedY, delta)
    }

  test(
    "combining and stacked marks retain native within-cluster positions while following clusters move"
  ) {
    Vector("x\u0301x", "x\u0301\u0323x").foreach { text =>
      val zero = TrackedGlyphs.layout(text, font, frc, 0)
      val positive = TrackedGlyphs.layout(text, font, frc, 5.5)
      val negative = TrackedGlyphs.layout(text, font, frc, -3.25)
      val reference = native(text)
      assertEquals(zero.runs.size, 1)
      assertSamePositions(zero.runs.head, reference)

      val firstCluster = TextClusters.boundaries(text)(1)
      positions(reference).zip(positions(positive.runs.head)).foreach {
        case ((index, x, y), (_, trackedX, trackedY)) =>
          val expectedShift = if index < firstCluster then 0.0 else 5.5
          assertEqualsDouble(trackedX, x + expectedShift, 1e-8)
          assertEqualsDouble(trackedY, y, 1e-8)
      }
      positions(reference).zip(positions(negative.runs.head)).foreach {
        case ((index, x, _), (_, trackedX, _)) =>
          assertEqualsDouble(trackedX, x + (if index < firstCluster then 0.0 else -3.25), 1e-8)
      }
      assertEqualsDouble(
        positive.advance,
        reference.getGlyphPosition(reference.getNumGlyphs).getX + 5.5,
        1e-8
      )
      assertEqualsDouble(
        negative.advance,
        reference.getGlyphPosition(reference.getNumGlyphs).getX - 3.25,
        1e-8
      )
    }
  }

  test("zero tracking preserves native paragraph layout for Hebrew and mixed bidi text") {
    Vector("אבג", "abc אבג 123").foreach { text =>
      val tracked = TrackedGlyphs.layout(text, font, frc, 0)
      val reference = new TextLayout(text, font, frc)
      assertEqualsDouble(tracked.advance, reference.getAdvance, 1e-7)
      val actual = tracked.outline.getBounds2D
      val expected = reference.getOutline(null).getBounds2D
      assertEqualsDouble(actual.getMinX, expected.getMinX, 1e-7)
      assertEqualsDouble(actual.getMinY, expected.getMinY, 1e-7)
      assertEqualsDouble(actual.getWidth, expected.getWidth, 1e-7)
      assertEqualsDouble(actual.getHeight, expected.getHeight, 1e-7)
    }
  }

  test("Arabic is one atomic shaping word: tracking cannot change its advance or outline") {
    val arabic = "السلام"
    val zero = TrackedGlyphs.layout(arabic, font, frc, 0)
    val spaced = TrackedGlyphs.layout(arabic, font, frc, 12)
    assertEquals(TextTracking.units(arabic).size, 1)
    assertEqualsDouble(spaced.advance, zero.advance, 1e-8)
    val actual = spaced.outline.getBounds2D
    val expected = zero.outline.getBounds2D
    assertEqualsDouble(actual.getMinX, expected.getMinX, 1e-8)
    assertEqualsDouble(actual.getMinY, expected.getMinY, 1e-8)
    assertEqualsDouble(actual.getWidth, expected.getWidth, 1e-8)
    assertEqualsDouble(actual.getHeight, expected.getHeight, 1e-8)
  }

  test("surrogate-pair and emoji-ZWJ clusters are not split and receive one inter-cluster gap") {
    val text = "A\uD83D\uDC69\u200D\uD83D\uDCBBB"
    val units = TextTracking.units(text)
    assertEquals(units.map(unit => text.substring(unit.start, unit.end)), Vector("A", "👩‍💻", "B"))
    val zero = TrackedGlyphs.layout(text, font, frc, 0)
    val tracked = TrackedGlyphs.layout(text, font, frc, 4)
    assertEqualsDouble(tracked.advance - zero.advance, 8.0, 1e-8)
    assert(tracked.runs.nonEmpty)
  }

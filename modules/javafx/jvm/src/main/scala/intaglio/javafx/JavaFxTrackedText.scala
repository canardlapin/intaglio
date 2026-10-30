package intaglio.javafx

import intaglio.{FontWeight, HJust, TextTracking, VJust}
import javafx.geometry.Rectangle2D
import javafx.scene.SnapshotParameters
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.{Color, Paint}
import javafx.scene.shape.{Rectangle, Shape}
import javafx.scene.text.{Text, TextBoundsType, TextFlow}
import javafx.scene.transform.Scale

/** A native paragraph with explicit safe shaping spans. The paragraph is shaped and bidi-ordered by
  * TextFlow before translations are applied. Snapshotting the completed paragraph preserves
  * mirrored punctuation, registered faces, fallback glyphs, and color emoji; substrings are never
  * sent through a second shaping pass.
  */
private[javafx] final class JavaFxTrackedText private (
    val flow: TextFlow,
    val advance: Double,
    val height: Double,
    val ascent: Double,
    val bounds: JavaFxTextBox,
    val ink: Option[JavaFxTextBox]
):
  def anchor(horizontal: HJust, vertical: VJust): (Double, Double) =
    val left = horizontal match
      case HJust.Left   => 0.0
      case HJust.Center => -advance / 2.0
      case HJust.Right  => -advance
    val top = vertical match
      case VJust.Top    => 0.0
      case VJust.Center => -height / 2.0
      case VJust.Bottom => -height
    (left, top)

  def anchoredBounds(horizontal: HJust, vertical: VJust): JavaFxTextBox =
    val (left, top) = anchor(horizontal, vertical)
    bounds.copy(left = bounds.left + left, top = bounds.top + top)

  def draw(
      context: GraphicsContext,
      x: Double,
      y: Double,
      horizontal: HJust,
      vertical: VJust
  ): Unit =
    if ink.isEmpty then return
    val transform = context.getTransform
    val scale = math.max(
      1.0,
      math.max(
        math.hypot(transform.getMxx, transform.getMyx),
        math.hypot(transform.getMxy, transform.getMyy)
      )
    )
    val padding = 1.0 / scale
    val left = bounds.left - padding
    val top = bounds.top - padding
    val width = math.ceil((bounds.width + 2 * padding) * scale)
    val height = math.ceil((bounds.height + 2 * padding) * scale)
    require(
      width <= 4096 && height <= 4096,
      "JavaFX tracked text exceeds the 4096-pixel snapshot limit"
    )
    val parameters = new SnapshotParameters()
    parameters.setFill(Color.TRANSPARENT)
    parameters.setTransform(new Scale(scale, scale))
    parameters.setViewport(new Rectangle2D(left * scale, top * scale, width, height))
    val image = flow.snapshot(parameters, null)
    val (dx, dy) = anchor(horizontal, vertical)
    context.drawImage(image, x + dx + left, y + dy + top, width / scale, height / scale)

private[javafx] object JavaFxTrackedText:
  def layout(
      label: String,
      family: Option[String],
      size: Double,
      weight: Option[FontWeight],
      spacing: Double,
      fill: Paint = Color.BLACK
  ): JavaFxTrackedText = synchronized {
    require(spacing.isFinite, "Letter spacing must be finite")
    val units = TextTracking.units(label)
    val font = JavaFxTextMetrics.font(family, size, weight)
    val nodes = units.map { unit =>
      val text = new Text(label.substring(unit.start, unit.end))
      text.setFont(font)
      text.setFill(fill)
      text
    }
    val flow = new TextFlow()
    nodes.foreach(flow.getChildren.add(_))
    val naturalWidth = flow.prefWidth(-1)
    val lineHeight = if label.isEmpty then
      new Text("Mg") { setFont(font) }.getLayoutBounds.getHeight
    else flow.prefHeight(naturalWidth + 1)
    flow.resize(naturalWidth + 1, lineHeight)
    flow.layout()
    var seen = 0
    nodes.indices.sortBy(i => nodes(i).getLayoutX).foreach { index =>
      nodes(index).setTranslateX(seen * spacing)
      if units(index).spaced then seen += 1
    }
    val advance = naturalWidth + math.max(0, seen - 1) * spacing
    require(advance.isFinite && advance >= 0, "Letter spacing produces an invalid text advance")
    var left = 0.0
    var top = 0.0
    var right = advance
    var bottom = lineHeight
    var ink: Option[(Double, Double, Double, Double)] = None
    def includeInk(x: Double, y: Double, maxX: Double, maxY: Double): Unit =
      ink = Some(ink.fold((x, y, maxX, maxY)) { old =>
        (math.min(old._1, x), math.min(old._2, y), math.max(old._3, maxX), math.max(old._4, maxY))
      })
    nodes.zip(units).foreach { case (node, unit) =>
      // Text's logical box alone misses accents and italic overhang. Public shape extraction
      // supplies vector ink; the logical box also covers bitmap/color glyphs without outlines.
      val outline = Shape.subtract(node, new Rectangle(0, 0, 0, 0)).getBoundsInLocal
      if !outline.isEmpty then
        includeInk(outline.getMinX, outline.getMinY, outline.getMaxX, outline.getMaxY)
      else
        // Bitmap/color glyphs have no vector outline. Their native visual metrics still differ
        // from a line box; spaces correctly produce an empty visual box.
        val visualNode = new Text(node.getText)
        visualNode.setFont(node.getFont)
        visualNode.setBoundsType(TextBoundsType.VISUAL)
        val visual = visualNode.getLayoutBounds
        if !visual.isEmpty && visual.getWidth > 0 && visual.getHeight > 0 then
          val x = node.getLayoutX + node.getTranslateX
          val y = flow.getBaselineOffset
          includeInk(x + visual.getMinX, y + visual.getMinY, x + visual.getMaxX, y + visual.getMaxY)
      val logical = node.getBoundsInParent
      val boxes = if unit.spaced then Seq(outline, logical) else Seq(outline)
      boxes.filterNot(_.isEmpty).foreach { box =>
        left = math.min(left, box.getMinX)
        top = math.min(top, box.getMinY)
        right = math.max(right, box.getMaxX)
        bottom = math.max(bottom, box.getMaxY)
      }
    }
    new JavaFxTrackedText(
      flow,
      advance,
      lineHeight,
      flow.getBaselineOffset,
      JavaFxTextBox(left, top, right - left, bottom - top),
      ink.map { case (x, y, maxX, maxY) => JavaFxTextBox(x, y, maxX - x, maxY - y) }
    )
  }

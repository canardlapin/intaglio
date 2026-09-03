package intaglio

/** Behavioral family a conformance case exercises. */
enum ConformanceGroup:
  case Primitive
  case PatternFill
  case Layout
  case Guide
  case CompiledPlot

enum RenderPrimitiveKind:
  case Disc
  case Polyline
  case Polygon
  case Rectangle
  case Text
  case Image

/** Backend-neutral facts that must be observable in renderer output. Unlike a marker-only smoke
  * check, these requirements pin primitive choice, styles, text placement, and group effects
  * without prescribing an output format.
  */
enum RenderRequirement:
  case Primitive(name: GraphicsName, kind: RenderPrimitiveKind)
  case Group(name: GraphicsName, clipped: Boolean, rotated: Boolean)
  case Style(
      name: GraphicsName,
      stroke: Option[Rgba],
      fill: Option[Rgba],
      lineWidth: Double,
      lineType: LineType,
      lineCap: LineCap,
      lineJoin: LineJoin,
      alpha: Double
  )
  case PatternFill(name: GraphicsName, paint: PatternPaint, alpha: Double)
  case Text(name: GraphicsName, horizontal: HJust, vertical: VJust, rotated: Boolean)
  case TextStyle(
      name: GraphicsName,
      color: Rgba,
      fontSizePx: Double,
      fontFamily: Option[String],
      alpha: Double
  )
  case Image(
      name: GraphicsName,
      dimensions: RasterDimensions,
      interpolation: RasterInterpolation,
      alpha: Double
  )

  def description: String =
    this match
      case Primitive(name, kind) =>
        s"primitive '${name.value}' as $kind"
      case Group(name, clipped, rotated) =>
        s"group '${name.value}' with clipped=$clipped and rotated=$rotated"
      case Style(name, _, _, lineWidth, lineType, lineCap, lineJoin, alpha) =>
        s"style '${name.value}' with lineWidth=$lineWidth, lineType=$lineType, lineCap=$lineCap, lineJoin=$lineJoin, alpha=$alpha"
      case PatternFill(name, paint, alpha) =>
        s"pattern fill '${name.value}' with recipe=${paint.recipe}, ink=${paint.ink}, background=${paint.background}, alpha=$alpha"
      case Text(name, horizontal, vertical, rotated) =>
        s"text '${name.value}' with anchor=($horizontal,$vertical) and rotated=$rotated"
      case TextStyle(name, color, fontSizePx, fontFamily, alpha) =>
        s"text style '${name.value}' with color=$color, fontSizePx=$fontSizePx, fontFamily=$fontFamily, alpha=$alpha"
      case Image(name, dimensions, interpolation, alpha) =>
        s"image '${name.value}' with ${dimensions.width}x${dimensions.height} pixels, interpolation=$interpolation, alpha=$alpha"

/** One renderer conformance case: a scene, the family it exercises, and the named grobs whose
  * markers must survive into backend output.
  */
final case class ConformanceCase(
    name: GraphicsName,
    group: ConformanceGroup,
    scene: Scene,
    markers: Vector[GraphicsName],
    requirements: Vector[RenderRequirement] = Vector.empty
)

/** Adapter a backend implements to run the conformance contract. `render` must use
  * [[RendererConformance.targetContext]] (or its published dimensions and density) because some
  * requirements observe resolved physical units. `Out` must have value equality for the determinism
  * check.
  */
trait RendererHarness[Out]:
  def render(scene: Scene): Either[String, Out]
  def containsMarker(out: Out, name: GraphicsName): Boolean
  def satisfies(out: Out, requirement: RenderRequirement): Boolean =
    false

  /** Backend-specific well-formedness check on the rendered output; return a problem description to
    * fail the case.
    */
  def validate(out: Out): Option[String] =
    None

/** The renderer conformance contract: canonical scenes grouped by
  * primitive/layout/guide/compiled-plot behavior, plus a portable checker that any backend runs
  * without encoding plot semantics.
  */
object RendererConformance:
  final case class Violation(caseName: String, group: ConformanceGroup, problem: String)

  /** Canonical target for target-bound requirements such as point stroke widths and font sizes. */
  val targetWidth: Int = 240
  val targetHeight: Int = 160
  val targetPixelsPerInch: Double = 96.0

  val targetDevice: DeviceContext =
    DeviceContext.unsafe(targetWidth.toDouble, targetHeight.toDouble, targetPixelsPerInch)

  def targetContext(fontRegistry: FontRegistry = FontRegistry.passthrough): RenderContext =
    RenderContext.unsafe(
      targetWidth,
      targetHeight,
      pixelsPerInch = targetPixelsPerInch,
      fontRegistry = fontRegistry
    )

  /** Run every conformance case through a backend harness. An empty result means the backend
    * renders each case successfully, deterministically, with every marker present and its own
    * validation passing.
    */
  def check[Out](harness: RendererHarness[Out]): Either[GraphicsError, Vector[Violation]] =
    cases.map { all =>
      all.flatMap(conformanceCase => checkCase(harness, conformanceCase))
    }

  private def checkCase[Out](
      harness: RendererHarness[Out],
      conformanceCase: ConformanceCase
  ): Vector[Violation] =
    def violation(problem: String): Violation =
      Violation(conformanceCase.name.value, conformanceCase.group, problem)
    harness.render(conformanceCase.scene) match
      case Left(error) =>
        Vector(violation(s"render failed: $error"))
      case Right(first) =>
        harness.render(conformanceCase.scene) match
          case Left(error) =>
            Vector(violation(s"second render failed: $error"))
          case Right(second) =>
            val determinism =
              if first == second then Vector.empty
              else Vector(violation("rendering is not deterministic"))
            val markers =
              conformanceCase.markers.filterNot(harness.containsMarker(first, _)).map { missing =>
                violation(s"missing marker '${missing.value}'")
              }
            val requirements =
              conformanceCase.requirements.filterNot(harness.satisfies(first, _)).map { missing =>
                violation(s"missing semantic requirement: ${missing.description}")
              }
            val validation = harness.validate(first).map(violation).toVector
            determinism ++ markers ++ requirements ++ validation

  def cases: Either[GraphicsError, Vector[ConformanceCase]] =
    for
      point <- pointCase
      line <- lineCase
      shapes <- shapeCase
      annotated <- annotatedCase
      rectAndCircle <- rectCircleCase
      patternFills <- patternFillCase
      text <- textCase
      image <- imageCase
      clipped <- clippedViewportCase
      rotated <- rotatedViewportCase
      clippedAndRotated <- clippedRotatedViewportCase
      rasterOriented <- yDownViewportCase
      axis <- axisCase
      legend <- legendCase
      colorbar <- colorbarCase
      scaled <- scaledPlotCase
      mixedLayers <- mixedLayerPlotCase
      solved <- solvedPlotCase
      scatter <- scatterComparisonCase
      groupedLine <- groupedLineComparisonCase
      histogram <- histogramComparisonCase
      density <- densityComparisonCase
      summary <- summaryComparisonCase
      ribbon <- ribbonComparisonCase
      tiles <- tileComparisonCase
      heatmap <- heatmapComparisonCase
      bin2d <- bin2DComparisonCase
      kde2d <- kde2DComparisonCase
      contour <- contourComparisonCase
      filledContour <- filledContourComparisonCase
      faceted <- facetedPlotCase
      counted <- countPlotCase
      bandPosition <- bandPositionCase
      dodged <- dodgedPositionCase
      stacked <- stackedPositionCase
      jittered <- jitteredPositionCase
      scientific <- scientificStatsCase
      flipped <- flippedPlotCase
      boundedGeoms <- boundedGeomsCase
      segmentGeoms <- segmentGeomsCase
      bandGeoms <- bandGeomsCase
    yield Vector(
      point,
      line,
      shapes,
      annotated,
      rectAndCircle,
      patternFills,
      text,
      image,
      clipped,
      rotated,
      clippedAndRotated,
      rasterOriented,
      axis,
      legend,
      colorbar,
      scaled,
      mixedLayers,
      solved,
      scatter,
      groupedLine,
      histogram,
      density,
      summary,
      ribbon,
      tiles,
      heatmap,
      bin2d,
      kde2d,
      contour,
      filledContour,
      faceted,
      counted,
      bandPosition,
      dodged,
      stacked,
      jittered,
      scientific,
      flipped,
      boundedGeoms,
      segmentGeoms,
      bandGeoms
    )

  def group(group: ConformanceGroup): Either[GraphicsError, Vector[ConformanceCase]] =
    cases.map(_.filter(_.group == group))

  // --- Primitive cases -----------------------------------------------------

  def pointCase: Either[GraphicsError, ConformanceCase] =
    Grob
      .points(
        Vector(Point.npcUnsafe(0.25, 0.25), Point.npcUnsafe(0.75, 0.75)),
        size = ExtentExpr.pointsUnsafe(4.0),
        gp = GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = Some(Rgba.unsafe(40, 80, 120))),
        name = Some(GraphicsName.unsafe("conformance-point"))
      )
      .map { grob =>
        ConformanceCase(
          GraphicsName.unsafe("point"),
          ConformanceGroup.Primitive,
          Scene(Vector(grob)),
          Vector(GraphicsName.unsafe("conformance-point")),
          Vector(
            RenderRequirement
              .Primitive(GraphicsName.unsafe("conformance-point"), RenderPrimitiveKind.Disc)
          )
        )
      }

  def lineCase: Either[GraphicsError, ConformanceCase] =
    Grob
      .lines(
        Vector(
          Point.npcUnsafe(0.1, 0.1),
          Point.npcUnsafe(0.5, 0.75),
          Point.npcUnsafe(0.9, 0.25)
        ),
        gp = GraphicParams
          .unsafe(
            stroke = Some(Rgba.unsafe(25, 75, 125)),
            lineType = LineType.Dashed,
            lineCap = LineCap.Round,
            lineJoin = LineJoin.Bevel
          )
          .withStrokeWidth(StrokeWidth.pointsUnsafe(1.5)),
        name = Some(GraphicsName.unsafe("conformance-line"))
      )
      .map { grob =>
        ConformanceCase(
          GraphicsName.unsafe("line"),
          ConformanceGroup.Primitive,
          Scene(Vector(grob)),
          Vector(GraphicsName.unsafe("conformance-line")),
          Vector(
            RenderRequirement
              .Primitive(GraphicsName.unsafe("conformance-line"), RenderPrimitiveKind.Polyline),
            RenderRequirement.Style(
              GraphicsName.unsafe("conformance-line"),
              Some(Rgba.unsafe(25, 75, 125)),
              None,
              2.0,
              LineType.Dashed,
              LineCap.Round,
              LineJoin.Bevel,
              1.0
            )
          )
        )
      }

  def shapeCase: Either[GraphicsError, ConformanceCase] =
    for
      square <- Grob.points(
        Vector(Point.npcUnsafe(0.25, 0.5)),
        size = ExtentExpr.pointsUnsafe(6.0),
        shape = PointShape.Square,
        name = Some(GraphicsName.unsafe("conformance-square"))
      )
      triangle <- Grob.points(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        size = ExtentExpr.pointsUnsafe(6.0),
        shape = PointShape.Triangle,
        name = Some(GraphicsName.unsafe("conformance-triangle"))
      )
      cross <- Grob.points(
        Vector(Point.npcUnsafe(0.75, 0.5)),
        size = ExtentExpr.pointsUnsafe(6.0),
        shape = PointShape.Cross,
        name = Some(GraphicsName.unsafe("conformance-cross"))
      )
      diamond <- Grob.points(
        Vector(Point.npcUnsafe(0.5, 0.25)),
        size = ExtentExpr.pointsUnsafe(6.0),
        shape = PointShape.Diamond,
        name = Some(GraphicsName.unsafe("conformance-diamond"))
      )
    yield ConformanceCase(
      GraphicsName.unsafe("shapes"),
      ConformanceGroup.Primitive,
      Scene(Vector(square, triangle, cross, diamond)),
      Vector(
        GraphicsName.unsafe("conformance-square"),
        GraphicsName.unsafe("conformance-triangle"),
        GraphicsName.unsafe("conformance-cross"),
        GraphicsName.unsafe("conformance-diamond")
      ),
      Vector(
        RenderRequirement.Primitive(
          GraphicsName.unsafe("conformance-square"),
          RenderPrimitiveKind.Rectangle
        ),
        RenderRequirement.Primitive(
          GraphicsName.unsafe("conformance-triangle"),
          RenderPrimitiveKind.Polygon
        ),
        RenderRequirement.Primitive(
          GraphicsName.unsafe("conformance-cross"),
          RenderPrimitiveKind.Polyline
        ),
        RenderRequirement.Primitive(
          GraphicsName.unsafe("conformance-diamond"),
          RenderPrimitiveKind.Polygon
        )
      )
    )

  /** A named disc wrapped in [[Grob.Annotated]]. Every backend must accept the wrapper, keep the
    * child's marker and primitive, and leave its style untouched; only the SVG backend can show the
    * metadata, so this case pins acceptance and transparency, not emission.
    */
  def annotatedCase: Either[GraphicsError, ConformanceCase] =
    for
      disc <- Grob.points(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        size = ExtentExpr.pointsUnsafe(5.0),
        gp = GraphicParams.unsafe(
          stroke = Some(Rgba.unsafe(30, 60, 90)),
          fill = Some(Rgba.unsafe(200, 220, 240)),
          alpha = 0.9
        ),
        name = Some(GraphicsName.unsafe("conformance-annotated"))
      )
      cssClass <- CssClass("mark decode-filled")
      kind <- DataKey("kind")
    yield
      val meta = GrobMeta(
        title = Some("Recall unit 7 & \"friends\" <b>"),
        description = Some("Mass 0.5 ]]> end"),
        cssClass = Some(cssClass),
        data = Vector(kind -> "anchor")
      )
      ConformanceCase(
        GraphicsName.unsafe("annotated"),
        ConformanceGroup.Primitive,
        Scene(Vector(Grob.annotated(disc, meta))),
        Vector(GraphicsName.unsafe("conformance-annotated")),
        Vector(
          RenderRequirement.Primitive(
            GraphicsName.unsafe("conformance-annotated"),
            RenderPrimitiveKind.Disc
          ),
          RenderRequirement.Style(
            GraphicsName.unsafe("conformance-annotated"),
            Some(Rgba.unsafe(30, 60, 90)),
            Some(Rgba.unsafe(200, 220, 240)),
            1.0,
            LineType.Solid,
            LineCap.Butt,
            LineJoin.Miter,
            0.9
          )
        )
      )

  def rectCircleCase: Either[GraphicsError, ConformanceCase] =
    for
      rect <- Grob.rect(
        Point.npcUnsafe(0.5, 0.5),
        Size.npcUnsafe(0.25, 0.4),
        anchor = Anchor.Center,
        gp = GraphicParams.unsafe(fill = Some(Rgba.unsafe(10, 20, 30)), alpha = 0.75),
        name = Some(GraphicsName.unsafe("conformance-rect"))
      )
      circle <- Grob.circle(
        Point.npcUnsafe(0.25, 0.75),
        ExtentExpr.pointsUnsafe(3.0),
        gp = GraphicParams.unsafe(stroke = Some(Rgba.unsafe(200, 0, 0))),
        name = Some(GraphicsName.unsafe("conformance-circle"))
      )
    yield ConformanceCase(
      GraphicsName.unsafe("rect-circle"),
      ConformanceGroup.Primitive,
      Scene(Vector(rect, circle)),
      Vector(GraphicsName.unsafe("conformance-rect"), GraphicsName.unsafe("conformance-circle"))
    )

  def patternFillCase: Either[GraphicsError, ConformanceCase] =
    val angledName = GraphicsName.unsafe("conformance-pattern-angled")
    val crossedName = GraphicsName.unsafe("conformance-pattern-crossed")
    val rulesName = GraphicsName.unsafe("conformance-pattern-rules")
    val stippleName = GraphicsName.unsafe("conformance-pattern-stipple")
    val solidName = GraphicsName.unsafe("conformance-pattern-solid-control")
    val groupName = GraphicsName.unsafe("conformance-pattern-transform")
    for
      angledRecipe <- PatternRecipe.angledHatch(30.0, 12.0, 1.5)
      crossedRecipe <- PatternRecipe.crossHatch(45.0, 12.0, 1.5)
      rulesRecipe <- PatternRecipe.parallelRules(RuleOrientation.Horizontal, 12.0, 2.0)
      stippleRecipe <- PatternRecipe.stipple(12.0, 2.5)
      angledPaint = PatternPaint(angledRecipe, Rgba.unsafe(25, 35, 45, 0.8), Some(Rgba.White))
      crossedPaint = PatternPaint(
        crossedRecipe,
        Rgba.unsafe(55, 65, 75),
        Some(Rgba.unsafe(230, 235, 240, 0.6))
      )
      rulesPaint = PatternPaint(rulesRecipe, Rgba.unsafe(85, 95, 105, 0.7))
      stipplePaint = PatternPaint(stippleRecipe, Rgba.unsafe(115, 125, 135), Some(Rgba.White))
      angled <- Grob.rect(
        Point.npcUnsafe(0.2, 0.25),
        Size.npcUnsafe(0.25, 0.3),
        gp = GraphicParams.unsafe(stroke = None, alpha = 0.85).withPatternFill(angledPaint),
        name = Some(angledName)
      )
      crossed <- Grob.circle(
        Point.npcUnsafe(0.45, 0.25),
        ExtentExpr.npcUnsafe(0.12),
        gp = GraphicParams.unsafe(stroke = None, alpha = 0.75).withPatternFill(crossedPaint),
        name = Some(crossedName)
      )
      rules <- Grob.polygon(
        Vector(
          Point.npcUnsafe(0.6, 0.1),
          Point.npcUnsafe(0.85, 0.1),
          Point.npcUnsafe(0.725, 0.4)
        ),
        gp = GraphicParams.unsafe(stroke = None, alpha = 0.65).withPatternFill(rulesPaint),
        name = Some(rulesName)
      )
      stipple <- Grob.compoundPolygon(
        Vector(
          Vector(
            Point.npcUnsafe(0.2, 0.55),
            Point.npcUnsafe(0.8, 0.55),
            Point.npcUnsafe(0.8, 0.9),
            Point.npcUnsafe(0.2, 0.9)
          ),
          Vector(
            Point.npcUnsafe(0.4, 0.65),
            Point.npcUnsafe(0.6, 0.65),
            Point.npcUnsafe(0.6, 0.8),
            Point.npcUnsafe(0.4, 0.8)
          )
        ),
        gp = GraphicParams.unsafe(stroke = None, alpha = 0.55).withPatternFill(stipplePaint),
        name = Some(stippleName)
      )
      solid <- Grob.rect(
        Point.npcUnsafe(0.88, 0.78),
        Size.npcUnsafe(0.12, 0.16),
        gp = GraphicParams
          .unsafe(stroke = None, fill = Some(Rgba.unsafe(145, 155, 165)), alpha = 0.45),
        name = Some(solidName)
      )
      viewport = Viewport.unsafe(
        origin = Point.npcUnsafe(0.0, 0.0),
        size = Size.npcUnsafe(1.0, 1.0),
        clip = Clip.On,
        angleDegrees = 7.5
      )
      group = Grob.group(
        Vector(angled, crossed, rules, stipple, solid),
        viewport = Some(viewport),
        name = Some(groupName)
      )
    yield ConformanceCase(
      GraphicsName.unsafe("pattern-fills"),
      ConformanceGroup.PatternFill,
      Scene(Vector(group)),
      Vector(groupName, angledName, crossedName, rulesName, stippleName, solidName),
      Vector(
        RenderRequirement.Group(groupName, clipped = true, rotated = true),
        RenderRequirement.Primitive(angledName, RenderPrimitiveKind.Rectangle),
        RenderRequirement.PatternFill(angledName, angledPaint, 0.85),
        RenderRequirement.Primitive(crossedName, RenderPrimitiveKind.Disc),
        RenderRequirement.PatternFill(crossedName, crossedPaint, 0.75),
        RenderRequirement.Primitive(rulesName, RenderPrimitiveKind.Polygon),
        RenderRequirement.PatternFill(rulesName, rulesPaint, 0.65),
        RenderRequirement.Primitive(stippleName, RenderPrimitiveKind.Polygon),
        RenderRequirement.PatternFill(stippleName, stipplePaint, 0.55),
        RenderRequirement.Primitive(solidName, RenderPrimitiveKind.Rectangle),
        RenderRequirement.Style(
          solidName,
          stroke = None,
          fill = Some(Rgba.unsafe(145, 155, 165)),
          lineWidth = 1.0,
          lineType = LineType.Solid,
          lineCap = LineCap.Butt,
          lineJoin = LineJoin.Miter,
          alpha = 0.45
        )
      )
    )

  def textCase: Either[GraphicsError, ConformanceCase] =
    val name = GraphicsName.unsafe("conformance-text")
    val color = Rgba.unsafe(20, 40, 80, 0.8)
    Grob
      .text(
        "A&B <label>",
        Point.npcUnsafe(0.5, 0.75),
        anchor = Anchor(HJust.Left, VJust.Top),
        rotationDegrees = 30.0,
        gp = GraphicParams.unsafe(
          stroke = None,
          fill = Some(color),
          alpha = 0.65,
          fontFamily = Some("Conformance Sans"),
          fontSize = Length.pointsUnsafe(9.0)
        ),
        name = Some(name)
      )
      .map { grob =>
        ConformanceCase(
          GraphicsName.unsafe("text"),
          ConformanceGroup.Primitive,
          Scene(Vector(grob)),
          Vector(name),
          Vector(
            RenderRequirement.Primitive(name, RenderPrimitiveKind.Text),
            RenderRequirement.Text(
              name,
              HJust.Left,
              VJust.Top,
              rotated = true
            ),
            RenderRequirement.TextStyle(
              name,
              color,
              fontSizePx = 12.0,
              fontFamily = Some("Conformance Sans"),
              alpha = 0.65
            )
          )
        )
      }

  def imageCase: Either[GraphicsError, ConformanceCase] =
    val dimensions = RasterDimensions.unsafe(2, 2)
    val raster = RasterImage.unsafePacked(
      dimensions,
      Vector(
        Rgba32.unsafe(220, 30, 30),
        Rgba32.unsafe(30, 200, 60, 160),
        Rgba32.unsafe(40, 80, 220),
        Rgba32.unsafe(245, 210, 40, 0)
      )
    )
    val name = GraphicsName.unsafe("conformance-image")
    Grob
      .image(
        raster,
        Point.npcUnsafe(0.5, 0.5),
        Size.npcUnsafe(0.5, 0.5),
        interpolation = RasterInterpolation.Nearest,
        alpha = 0.8,
        name = Some(name)
      )
      .map { grob =>
        ConformanceCase(
          GraphicsName.unsafe("image"),
          ConformanceGroup.Primitive,
          Scene(Vector(grob)),
          Vector(name),
          Vector(
            RenderRequirement.Primitive(name, RenderPrimitiveKind.Image),
            RenderRequirement.Image(name, dimensions, RasterInterpolation.Nearest, alpha = 0.8)
          )
        )
      }

  // --- Layout cases --------------------------------------------------------

  def clippedViewportCase: Either[GraphicsError, ConformanceCase] =
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.1, 0.2),
      size = Size.npcUnsafe(0.6, 0.5),
      xScale = Interval.unsafe(-1.0, 1.0),
      yScale = Interval.unsafe(0.0, 10.0),
      clip = Clip.On
    )
    Grob
      .lines(
        Vector(Point.nativeUnsafe(-1.0, 0.0), Point.nativeUnsafe(1.0, 10.0)),
        viewport = Some(viewport),
        name = Some(GraphicsName.unsafe("conformance-clip"))
      )
      .map { grob =>
        ConformanceCase(
          GraphicsName.unsafe("clipped-viewport"),
          ConformanceGroup.Layout,
          Scene(Vector(grob)),
          Vector(GraphicsName.unsafe("conformance-clip")),
          Vector(
            RenderRequirement
              .Group(GraphicsName.unsafe("conformance-clip"), clipped = true, rotated = false)
          )
        )
      }

  def rotatedViewportCase: Either[GraphicsError, ConformanceCase] =
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.2, 0.2),
      size = Size.npcUnsafe(0.5, 0.5),
      clip = Clip.Off,
      angleDegrees = 15.0
    )
    Grob
      .lines(
        Vector(Point.npcUnsafe(0.0, 0.0), Point.npcUnsafe(1.0, 1.0)),
        viewport = Some(viewport),
        name = Some(GraphicsName.unsafe("conformance-rotation"))
      )
      .map { grob =>
        ConformanceCase(
          GraphicsName.unsafe("rotated-viewport"),
          ConformanceGroup.Layout,
          Scene(Vector(grob)),
          Vector(GraphicsName.unsafe("conformance-rotation")),
          Vector(
            RenderRequirement
              .Group(GraphicsName.unsafe("conformance-rotation"), clipped = false, rotated = true)
          )
        )
      }

  def clippedRotatedViewportCase: Either[GraphicsError, ConformanceCase] =
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.15, 0.15),
      size = Size.npcUnsafe(0.6, 0.6),
      clip = Clip.On,
      angleDegrees = 22.5
    )
    val style = GraphicParams.unsafe(
      stroke = Some(Rgba.unsafe(80, 40, 160)),
      lineWidth = 2.0,
      lineType = LineType.Dotted,
      alpha = 0.6
    )
    Grob
      .lines(
        Vector(Point.npcUnsafe(-0.1, 0.2), Point.npcUnsafe(1.1, 0.8)),
        gp = style,
        viewport = Some(viewport),
        name = Some(GraphicsName.unsafe("conformance-clip-rotation"))
      )
      .map { grob =>
        val name = GraphicsName.unsafe("conformance-clip-rotation")
        ConformanceCase(
          GraphicsName.unsafe("clipped-rotated-viewport"),
          ConformanceGroup.Layout,
          Scene(Vector(grob)),
          Vector(name),
          Vector(
            RenderRequirement.Group(name, clipped = true, rotated = true),
            RenderRequirement.Primitive(name, RenderPrimitiveKind.Polyline),
            RenderRequirement.Style(
              name,
              Some(Rgba.unsafe(80, 40, 160)),
              None,
              2.0,
              LineType.Dotted,
              LineCap.Butt,
              LineJoin.Miter,
              0.6
            )
          )
        )
      }

  def yDownViewportCase: Either[GraphicsError, ConformanceCase] =
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.1, 0.1),
      size = Size.npcUnsafe(0.8, 0.8),
      yScale = Interval.unsafe(0.0, 4.0),
      clip = Clip.Off,
      yDirection = YDirection.Down
    )
    Grob
      .rect(
        Point.nativeUnsafe(0.5, 1.0),
        Size.npcUnsafe(0.5, 0.25),
        anchor = Anchor(HJust.Left, VJust.Top),
        viewport = Some(viewport),
        name = Some(GraphicsName.unsafe("conformance-ydown"))
      )
      .map { grob =>
        ConformanceCase(
          GraphicsName.unsafe("ydown-viewport"),
          ConformanceGroup.Layout,
          Scene(Vector(grob)),
          Vector(GraphicsName.unsafe("conformance-ydown"))
        )
      }

  // --- Guide cases ---------------------------------------------------------

  def axisCase: Either[GraphicsError, ConformanceCase] =
    val layout =
      PanelLayout(
        PanelFrame.npcUnsafe(0.1, 0.15, 0.8, 0.75),
        xScale = Interval.unsafe(0.0, 10.0),
        yScale = Interval.unsafe(-1.0, 1.0),
        clip = Clip.Off
      )
    for
      bottom <- GuideSpec.lower(
        GuideSpec.Axis(
          AxisSide.Bottom,
          breaks = Breaks.countUnsafe(3),
          name = Some(GraphicsName.unsafe("conformance-x-axis"))
        ),
        layout
      )
      left <- GuideSpec.lower(
        GuideSpec.Axis(
          AxisSide.Left,
          breaks = Breaks.countUnsafe(3),
          name = Some(GraphicsName.unsafe("conformance-y-axis"))
        ),
        layout
      )
    yield ConformanceCase(
      GraphicsName.unsafe("axes"),
      ConformanceGroup.Guide,
      Scene(Vector(bottom.grob, left.grob)),
      Vector(GraphicsName.unsafe("conformance-x-axis"), GraphicsName.unsafe("conformance-y-axis"))
    )

  def legendCase: Either[GraphicsError, ConformanceCase] =
    val layout =
      PanelLayout(
        PanelFrame.npcUnsafe(0.1, 0.1, 0.7, 0.8),
        xScale = Interval.unsafe(0.0, 1.0),
        yScale = Interval.unsafe(0.0, 1.0)
      )
    GuideSpec
      .lower(
        GuideSpec.Legend(
          title = Some("condition"),
          entries = Vector(
            LegendEntry.colorUnsafe("A", Rgba.unsafe(40, 80, 120)),
            LegendEntry.colorUnsafe("B", Rgba.unsafe(210, 120, 40))
          ),
          name = Some(GraphicsName.unsafe("conformance-legend"))
        ),
        layout
      )
      .map { guide =>
        ConformanceCase(
          GraphicsName.unsafe("legend"),
          ConformanceGroup.Guide,
          Scene(Vector(guide.grob)),
          Vector(GraphicsName.unsafe("conformance-legend"))
        )
      }

  def colorbarCase: Either[GraphicsError, ConformanceCase] =
    val layout = PanelLayout.unit(Interval.unsafe(0.0, 1.0), Interval.unsafe(0.0, 1.0))
    val name = GraphicsName.unsafe("conformance-colorbar")
    GuideSpec
      .lower(
        GuideSpec.Colorbar(
          title = Some("activation"),
          colors = Vector(
            Rgba.unsafe(20, 30, 80),
            Rgba.unsafe(90, 100, 100),
            Rgba.unsafe(165, 155, 70),
            Rgba.unsafe(240, 210, 40)
          ),
          ticks = Vector(
            AxisTick.unsafe(0.0, "1"),
            AxisTick.unsafe(0.5, "10"),
            AxisTick.unsafe(1.0, "100")
          ),
          name = Some(name)
        ),
        layout
      )
      .map { guide =>
        val swatch = GraphicsName.unsafe("conformance-colorbar-swatch-0")
        val ticks = GraphicsName.unsafe("conformance-colorbar-ticks")
        val title = GraphicsName.unsafe("conformance-colorbar-title")
        ConformanceCase(
          GraphicsName.unsafe("colorbar"),
          ConformanceGroup.Guide,
          Scene(Vector(guide.grob)),
          Vector(name, swatch, ticks, title),
          requirements = Vector(
            RenderRequirement.Primitive(swatch, RenderPrimitiveKind.Rectangle),
            RenderRequirement.Primitive(ticks, RenderPrimitiveKind.Polyline),
            RenderRequirement.Primitive(title, RenderPrimitiveKind.Text)
          )
        )
      }

  // --- Compiled plot cases -------------------------------------------------

  private final case class Observation(x: Double, y: Double, condition: String)

  private val observations =
    Vector(
      Observation(0.0, 1.0, "A"),
      Observation(1.0, 2.0, "B"),
      Observation(2.0, 3.0, "A")
    )

  private def conditionScale: Either[GraphicsError, DiscreteScale[String, Rgba]] =
    DiscreteDomain.ordered(Vector("A", "B")).flatMap { domain =>
      DiscreteScale(
        "condition",
        domain,
        DiscretePalette.valuesUnsafe(Vector(Rgba.unsafe(40, 80, 120), Rgba.unsafe(210, 120, 40)))
      )
    }

  def scaledPlotCase: Either[GraphicsError, ConformanceCase] =
    for
      xScale <- ContinuousScale.train("x-position", observations.map(_.x), Palette.numeric)
      yScale <- ContinuousScale.train("y-position", observations.map(_.y), Palette.numeric)
      colorScale <- conditionScale
      plot <- Plot(observations)
        .withScale(ScaleBinding[Observation, Double, Double](Aesthetic.X, _.x, xScale))
        .flatMap(_.withScale(ScaleBinding[Observation, Double, Double](Aesthetic.Y, _.y, yScale)))
        .flatMap(
          _.withScale(
            ScaleBinding[Observation, String, Rgba](Aesthetic.Color, _.condition, colorScale)
          )
        )
        .flatMap(_.addLayer(Layer.point[Observation](_.x, _.y)))
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(
          layout = Some(
            PanelLayout(
              PanelFrame.npcUnsafe(0.12, 0.12, 0.68, 0.72),
              xScale = Interval.unsafe(-0.05, 1.05),
              yScale = Interval.unsafe(-0.05, 1.05),
              clip = Clip.On
            )
          ),
          guides = GuidePolicy.Explicit(
            Vector(
              GuideSpec.Axis(
                AxisSide.Bottom,
                ticks = Some(
                  Vector(
                    AxisTick.unsafe(0.0, "0"),
                    AxisTick.unsafe(0.5, "0.5"),
                    AxisTick.unsafe(1.0, "1")
                  )
                ),
                tickLength = Some(ExtentExpr.nativeUnsafe(0.05)),
                labelOffset = Some(ExtentExpr.nativeUnsafe(0.1)),
                name = Some(GraphicsName.unsafe("scaled-x-axis"))
              ),
              GuideSpec.Legend(
                title = Some("condition"),
                entries = Vector(
                  LegendEntry.colorUnsafe("A", Rgba.unsafe(40, 80, 120)),
                  LegendEntry.colorUnsafe("B", Rgba.unsafe(210, 120, 40))
                ),
                origin = Point.npcUnsafe(0.84, 0.82),
                name = Some(GraphicsName.unsafe("condition-legend"))
              )
            )
          )
        )
      )
    yield ConformanceCase(
      GraphicsName.unsafe("scaled-plot"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("scaled-x-axis"),
        GraphicsName.unsafe("condition-legend")
      )
    )

  private final case class OverlayTile(x: Double, y: Double, width: Double, height: Double)

  def mixedLayerPlotCase: Either[GraphicsError, ConformanceCase] =
    val overlays = Vector(OverlayTile(1.0, 2.0, 0.5, 0.75))
    for
      plot <- Plot(observations)
        .addLayer(Layer.point[Observation](_.x, _.y))
        .flatMap(
          _.addIndependentLayer(
            overlays,
            Layer.tile[OverlayTile](_.x, _.y, _.width, _.height),
            LayerFacetPolicy.Repeat
          )
        )
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
      )
    yield ConformanceCase(
      GraphicsName.unsafe("mixed-layer-plot"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(GraphicsName.unsafe("plot-panel"), GraphicsName.unsafe("geom-tile-0")),
      Vector(
        RenderRequirement.Primitive(
          GraphicsName.unsafe("geom-tile-0"),
          RenderPrimitiveKind.Rectangle
        )
      )
    )

  def solvedPlotCase: Either[GraphicsError, ConformanceCase] =
    for
      colorScale <- conditionScale
      plot <- Plot(observations)
        .withLabels(
          PlotLabels(
            title = Some("Signal by condition"),
            subtitle = Some("Portable renderer contract"),
            x = Some("Time"),
            y = Some("Signal")
          )
        )
        .withScale(
          ScaleBinding[Observation, String, Rgba](Aesthetic.Color, _.condition, colorScale)
        )
        .flatMap(_.addLayer(Layer.point[Observation](_.x, _.y)))
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(
          policy = Some(LayoutPolicy()),
          guides = GuidePolicy.Derived()
        )
      )
    yield ConformanceCase(
      GraphicsName.unsafe("titled-plot"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("x-axis"),
        GraphicsName.unsafe("y-axis"),
        GraphicsName.unsafe("condition-legend"),
        PlotRegion.Title,
        PlotRegion.Subtitle,
        GraphicsName.unsafe("x-axis-title"),
        GraphicsName.unsafe("y-axis-title")
      ),
      Vector(
        RenderRequirement.Text(PlotRegion.Title, HJust.Left, VJust.Center, rotated = false),
        RenderRequirement.Text(PlotRegion.Subtitle, HJust.Left, VJust.Center, rotated = false),
        RenderRequirement
          .Text(GraphicsName.unsafe("x-axis-title"), HJust.Center, VJust.Center, rotated = false),
        RenderRequirement.Text(
          GraphicsName.unsafe("y-axis-title"),
          HJust.Center,
          VJust.Center,
          rotated = true
        )
      )
    )

  private final case class ComparisonPoint(x: Double, y: Double, condition: String)

  private val comparisonPoints =
    Vector(
      ComparisonPoint(0.0, 1.0, "A"),
      ComparisonPoint(1.0, 1.8, "A"),
      ComparisonPoint(2.0, 2.5, "A"),
      ComparisonPoint(0.0, 2.0, "B"),
      ComparisonPoint(1.0, 2.7, "B"),
      ComparisonPoint(2.0, 3.2, "B")
    )

  private def comparisonColor(condition: String): Rgba =
    if condition == "A" || condition == "red" then Rgba.unsafe(70, 125, 180)
    else Rgba.unsafe(220, 135, 65)

  def scatterComparisonCase: Either[GraphicsError, ConformanceCase] =
    val mapping = AesSpec
      .empty[ComparisonPoint]
      .withPosition(_.x, _.y)
      .withGroup(_.condition)
      .withColor(row => comparisonColor(row.condition))
      .withFill(row => comparisonColor(row.condition))
    for
      layer <- Layer.fromMapping(Geom.Point, mapping, inheritMapping = false)
      plot <- Plot(comparisonPoints)
        .withLabels(PlotLabels(title = Some("scatter"), x = Some("x"), y = Some("y")))
        .addLayer(layer)
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(
          policy = Some(LayoutPolicy()),
          guides = GuidePolicy.Derived(),
          theme = Theme.minimal
        )
      )
    yield ConformanceCase(
      GraphicsName.unsafe("comparison-scatter"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("x-axis"),
        GraphicsName.unsafe("y-axis")
      )
    )

  def groupedLineComparisonCase: Either[GraphicsError, ConformanceCase] =
    val mapping = AesSpec
      .empty[ComparisonPoint]
      .withPosition(_.x, _.y)
      .withGroup(_.condition)
      .withColor(row => comparisonColor(row.condition))
    for
      layer <- Layer.fromMapping(
        Geom.Line,
        mapping,
        inheritMapping = false,
        params = Some(GraphicParams.unsafe(lineWidth = 1.5))
      )
      plot <- Plot(comparisonPoints)
        .withLabels(PlotLabels(title = Some("grouped-line"), x = Some("x"), y = Some("y")))
        .addLayer(layer)
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(
          policy = Some(LayoutPolicy()),
          guides = GuidePolicy.Derived(),
          theme = Theme.minimal
        )
      )
    yield ConformanceCase(
      GraphicsName.unsafe("comparison-line"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("x-axis"),
        GraphicsName.unsafe("y-axis")
      )
    )

  private val distributionValues =
    Vector(0.0, 0.4, 0.9, 1.0, 1.3, 1.8, 2.0, 2.2, 2.7, 3.1, 3.6, 4.0)

  private val comparisonBlue =
    GraphicParams.unsafe(
      stroke = Some(comparisonColor("A")),
      fill = Some(Rgba.unsafe(90, 150, 205))
    )

  def histogramComparisonCase: Either[GraphicsError, ConformanceCase] =
    Plot(distributionValues)
      .withLabels(PlotLabels(title = Some("histogram"), x = Some("value"), y = Some("count")))
      .addLayer(
        Layer.histogram(
          identity,
          bins = HistogramBins.breaksUnsafe(Vector(0.0, 1.0, 2.0, 3.0, 4.0)),
          params = Some(comparisonBlue)
        )
      )
      .flatMap(
        compiledComparisonCase(
          "histogram",
          _,
          GraphicsName.unsafe("stat-bin-bar-0"),
          RenderPrimitiveKind.Rectangle
        )
      )

  def densityComparisonCase: Either[GraphicsError, ConformanceCase] =
    val config = DensityConfig.fixedUnsafe(
      bandwidth = 0.45,
      points = 64,
      domain = Some(Interval.unsafe(0.0, 4.0))
    )
    Plot(distributionValues)
      .withLabels(PlotLabels(title = Some("density"), x = Some("value"), y = Some("density")))
      .addLayer(
        Layer.density(
          identity,
          config = config,
          params = Some(GraphicParams.unsafe(stroke = Some(comparisonColor("A")), lineWidth = 1.5))
        )
      )
      .flatMap(
        compiledComparisonCase(
          "density",
          _,
          GraphicsName.unsafe("stat-density-line"),
          RenderPrimitiveKind.Polyline
        )
      )

  private final case class SummaryPoint(x: Double, y: Double)

  def summaryComparisonCase: Either[GraphicsError, ConformanceCase] =
    val samples = Vector(
      SummaryPoint(0.0, 1.0),
      SummaryPoint(0.0, 2.0),
      SummaryPoint(0.0, 3.0),
      SummaryPoint(1.0, 2.0),
      SummaryPoint(1.0, 4.0),
      SummaryPoint(1.0, 6.0),
      SummaryPoint(2.0, 4.0),
      SummaryPoint(2.0, 5.0),
      SummaryPoint(2.0, 6.0)
    )
    Plot(samples)
      .withLabels(PlotLabels(title = Some("mean-and-se"), x = Some("x"), y = Some("mean")))
      .addLayer(
        Layer.summary(
          _.x,
          _.y,
          params = Some(
            GraphicParams.unsafe(
              stroke = Some(comparisonColor("A")),
              fill = Some(comparisonColor("A")),
              lineWidth = 1.25
            )
          )
        )
      )
      .flatMap(
        compiledComparisonCase(
          "summary",
          _,
          GraphicsName.unsafe("stat-summary-interval-0"),
          RenderPrimitiveKind.Polyline
        )
      )

  private final case class RibbonPoint(x: Double, lower: Double, upper: Double)

  def ribbonComparisonCase: Either[GraphicsError, ConformanceCase] =
    val samples = Vector(
      RibbonPoint(0.0, 0.8, 1.5),
      RibbonPoint(1.0, 1.2, 2.0),
      RibbonPoint(2.0, 1.0, 1.8),
      RibbonPoint(3.0, 1.6, 2.4),
      RibbonPoint(4.0, 1.3, 2.0)
    )
    Plot(samples)
      .withLabels(PlotLabels(title = Some("ribbon"), x = Some("x"), y = Some("interval")))
      .addLayer(
        Layer.ribbon(
          _.x,
          _.lower,
          _.upper,
          params = Some(
            GraphicParams.unsafe(
              stroke = Some(comparisonColor("A")),
              fill = Some(comparisonColor("A")),
              alpha = 0.45
            )
          )
        )
      )
      .flatMap(
        compiledComparisonCase(
          "ribbon",
          _,
          GraphicsName.unsafe("geom-ribbon-0"),
          RenderPrimitiveKind.Polygon
        )
      )

  private final case class TilePoint(x: Double, y: Double, level: Int)

  def tileComparisonCase: Either[GraphicsError, ConformanceCase] =
    val samples = Vector(
      TilePoint(0.0, 0.0, 0),
      TilePoint(1.0, 0.0, 1),
      TilePoint(2.0, 0.0, 2),
      TilePoint(0.0, 1.0, 2),
      TilePoint(1.0, 1.0, 1),
      TilePoint(2.0, 1.0, 0)
    )
    val fills =
      Vector(Rgba.unsafe(225, 235, 245), Rgba.unsafe(125, 170, 210), Rgba.unsafe(45, 95, 145))
    val mapping = AesSpec.empty[TilePoint].withFill(row => fills(row.level))
    Plot(samples)
      .withLabels(PlotLabels(title = Some("tiles"), x = Some("x"), y = Some("y")))
      .addLayer(
        Layer.tile(
          _.x,
          _.y,
          _ => 1.0,
          _ => 1.0,
          mapping = mapping,
          params = Some(GraphicParams.unsafe(stroke = Some(Rgba.White), lineWidth = 1.0))
        )
      )
      .flatMap(
        compiledComparisonCase(
          "tiles",
          _,
          GraphicsName.unsafe("geom-tile-0"),
          RenderPrimitiveKind.Rectangle
        )
      )

  def heatmapComparisonCase: Either[GraphicsError, ConformanceCase] =
    for
      x <- RegularGridAxis.cellCentered(0.0, 3.0, 3)
      y <- RegularGridAxis.cellCentered(0.0, 2.0, 2)
      field <- ScalarField2D(x, y, Vector(0.0, 1.0, 2.0, 2.0, 1.0, 0.0))
      scene <- plot(field)
        .geomHeatmap(
          palette = Palette.gradient(Rgba.unsafe(239, 243, 255), Rgba.unsafe(8, 81, 156)),
          name = "value"
        )
        .title("heatmap")
        .axisTitles("x", "y")
        .theme(Theme.minimal)
        .scene
    yield ConformanceCase(
      GraphicsName.unsafe("comparison-heatmap"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("x-axis"),
        GraphicsName.unsafe("y-axis"),
        GraphicsName.unsafe("geom-tile-0"),
        GraphicsName.unsafe("value-colorbar")
      ),
      Vector(
        RenderRequirement.Primitive(
          GraphicsName.unsafe("geom-tile-0"),
          RenderPrimitiveKind.Rectangle
        )
      )
    )

  def bin2DComparisonCase: Either[GraphicsError, ConformanceCase] =
    final case class Sample(x: Double, y: Double)
    val samples = Vector(
      Sample(0.2, 0.2),
      Sample(0.4, 0.3),
      Sample(0.7, 0.8),
      Sample(1.2, 2.2),
      Sample(1.8, 2.7),
      Sample(2.2, 1.2),
      Sample(2.4, 1.4),
      Sample(2.6, 1.6),
      Sample(3.2, 3.2),
      Sample(3.7, 3.6)
    )
    val domain = Some(Interval.unsafe(0.0, 4.0))
    for
      config <- Bin2DConfig(4, 4, domain, domain)
      field <- FieldStat.bin2D[Sample](_.x, _.y, config).compute(samples)
      scene <- plot(field)
        .geomHeatmap(name = "count")
        .title("bin-2d")
        .axisTitles("x", "y")
        .theme(Theme.minimal)
        .scene
    yield ConformanceCase(
      GraphicsName.unsafe("comparison-bin2d"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("geom-tile-0"),
        GraphicsName.unsafe("count-colorbar")
      ),
      Vector(
        RenderRequirement.Primitive(
          GraphicsName.unsafe("geom-tile-0"),
          RenderPrimitiveKind.Rectangle
        )
      )
    )

  def kde2DComparisonCase: Either[GraphicsError, ConformanceCase] =
    final case class Sample(x: Double, y: Double)
    val samples = Vector(
      Sample(-1.4, -1.0),
      Sample(-1.1, -0.7),
      Sample(-0.8, -1.2),
      Sample(-0.5, -0.6),
      Sample(0.5, 0.8),
      Sample(0.9, 1.2),
      Sample(1.2, 0.7),
      Sample(1.5, 1.4)
    )
    val domain = Some(Interval.unsafe(-3.0, 3.0))
    val config = Kde2DConfig.fixedUnsafe(0.6, 0.7, 40, 40, domain, domain)
    for
      field <- FieldStat.kde2D[Sample](_.x, _.y, config).compute(samples)
      scene <- plot(field)
        .geomHeatmap(name = "density")
        .title("density-2d")
        .axisTitles("x", "y")
        .theme(Theme.minimal)
        .scene
    yield ConformanceCase(
      GraphicsName.unsafe("comparison-kde2d"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("geom-tile-0"),
        GraphicsName.unsafe("density-colorbar")
      ),
      Vector(
        RenderRequirement.Primitive(
          GraphicsName.unsafe("geom-tile-0"),
          RenderPrimitiveKind.Rectangle
        )
      )
    )

  def contourComparisonCase: Either[GraphicsError, ConformanceCase] =
    final case class Sample(x: Double, y: Double)
    val samples = Vector(
      Sample(-1.4, -1.0),
      Sample(-1.1, -0.7),
      Sample(-0.8, -1.2),
      Sample(-0.5, -0.6),
      Sample(0.5, 0.8),
      Sample(0.9, 1.2),
      Sample(1.2, 0.7),
      Sample(1.5, 1.4)
    )
    val domain = Some(Interval.unsafe(-3.0, 3.0))
    val config = Kde2DConfig.fixedUnsafe(0.6, 0.7, 80, 80, domain, domain)
    for
      field <- FieldStat.kde2D[Sample](_.x, _.y, config).compute(samples)
      levels <- ContourLevels.at(Vector(0.03, 0.06, 0.09, 0.12))
      contours <- ContourSet.extract(field, levels)
      scene <- plot(contours)
        .geomContour(
          params = Some(GraphicParams.unsafe(stroke = Some(comparisonColor("A")), lineWidth = 1.1))
        )
        .title("contour")
        .axisTitles("x", "y")
        .theme(Theme.minimal)
        .scene
    yield ConformanceCase(
      GraphicsName.unsafe("comparison-contour"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("x-axis"),
        GraphicsName.unsafe("y-axis")
      ),
      Vector.empty
    )

  def filledContourComparisonCase: Either[GraphicsError, ConformanceCase] =
    final case class Sample(x: Double, y: Double)
    val samples = Vector(
      Sample(-1.4, -1.0),
      Sample(-1.1, -0.7),
      Sample(-0.8, -1.2),
      Sample(-0.5, -0.6),
      Sample(0.5, 0.8),
      Sample(0.9, 1.2),
      Sample(1.2, 0.7),
      Sample(1.5, 1.4)
    )
    val domain = Some(Interval.unsafe(-3.0, 3.0))
    val config = Kde2DConfig.fixedUnsafe(0.6, 0.7, 40, 40, domain, domain)
    for
      field <- FieldStat.kde2D[Sample](_.x, _.y, config).compute(samples)
      breaks <- ContourBreaks.at(Vector(0.02, 0.05, 0.08, 0.11, 0.15))
      bands <- ContourBandSet.extract(field, breaks)
      scene <- plot(bands)
        .geomFilledContour(name = "density")
        .title("filled-contour")
        .axisTitles("x", "y")
        .theme(Theme.minimal)
        .scene
    yield ConformanceCase(
      GraphicsName.unsafe("comparison-filled-contour"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("geom-polygon-0"),
        GraphicsName.unsafe("density-colorbar")
      ),
      Vector(
        RenderRequirement.Primitive(
          GraphicsName.unsafe("geom-polygon-0"),
          RenderPrimitiveKind.Polygon
        )
      )
    )

  private def compiledComparisonCase[Row](
      name: String,
      plot: Plot[Row],
      mark: GraphicsName,
      kind: RenderPrimitiveKind
  ): Either[GraphicsError, ConformanceCase] =
    PlotCompiler
      .compile(
        plot,
        PlotCompilerOptions(
          policy = Some(LayoutPolicy()),
          guides = GuidePolicy.Derived(),
          theme = Theme.minimal
        )
      )
      .map { scene =>
        ConformanceCase(
          GraphicsName.unsafe(s"comparison-$name"),
          ConformanceGroup.CompiledPlot,
          scene,
          Vector(
            GraphicsName.unsafe("plot-panel"),
            GraphicsName.unsafe("x-axis"),
            GraphicsName.unsafe("y-axis"),
            mark
          ),
          Vector(RenderRequirement.Primitive(mark, kind))
        )
      }

  def facetedPlotCase: Either[GraphicsError, ConformanceCase] =
    final case class Sample(x: Double, y: Double, condition: String)
    val samples =
      Vector(
        Sample(0.0, 0.0, "control"),
        Sample(1.0, 1.0, "control"),
        Sample(10.0, 2.0, "task"),
        Sample(20.0, 3.0, "task")
      )
    plot(samples)
      .aes(_.x, _.y)
      .facetWrap(_.condition, columns = 2)
      .geomPoint(
        params = Some(
          GraphicParams.unsafe(
            stroke = Some(comparisonColor("A")),
            fill = Some(comparisonColor("A"))
          )
        )
      )
      .title("facets")
      .axisTitles("x", "y")
      .theme(Theme.minimal)
      .scene
      .map { scene =>
        ConformanceCase(
          GraphicsName.unsafe("faceted-plot"),
          ConformanceGroup.CompiledPlot,
          scene,
          Vector(
            GraphicsName.unsafe("panel-0-0"),
            GraphicsName.unsafe("strip-0-0"),
            GraphicsName.unsafe("panel-0-1"),
            GraphicsName.unsafe("strip-0-1"),
            GraphicsName.unsafe("x-axis-0-0"),
            GraphicsName.unsafe("x-axis-0-1"),
            GraphicsName.unsafe("y-axis-0-0")
          ),
          Vector(
            RenderRequirement
              .Group(GraphicsName.unsafe("panel-0-0"), clipped = true, rotated = false),
            RenderRequirement
              .Group(GraphicsName.unsafe("panel-0-1"), clipped = true, rotated = false),
            RenderRequirement
              .Text(GraphicsName.unsafe("strip-0-0"), HJust.Center, VJust.Center, rotated = false),
            RenderRequirement
              .Text(GraphicsName.unsafe("strip-0-1"), HJust.Center, VJust.Center, rotated = false)
          )
        )
      }

  def countPlotCase: Either[GraphicsError, ConformanceCase] =
    val categories = Vector("control", "task", "task", "other", "task", "control")
    for
      plot <- Plot(categories)
        .withLabels(PlotLabels(title = Some("count"), x = Some("category"), y = Some("count")))
        .addLayer(
          Layer.count(
            identity,
            order = CountOrder.Lexicographic,
            params = Some(
              GraphicParams.unsafe(
                stroke = Some(Rgba.unsafe(35, 60, 90)),
                fill = Some(Rgba.unsafe(90, 150, 205))
              )
            )
          )
        )
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(
          policy = Some(LayoutPolicy()),
          guides = GuidePolicy.Derived(),
          theme = Theme.minimal
        )
      )
    yield ConformanceCase(
      GraphicsName.unsafe("count-plot"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("x-axis"),
        GraphicsName.unsafe("y-axis"),
        GraphicsName.unsafe("stat-count-bar-0")
      ),
      Vector(
        RenderRequirement.Primitive(
          GraphicsName.unsafe("stat-count-bar-0"),
          RenderPrimitiveKind.Rectangle
        )
      )
    )

  def bandPositionCase: Either[GraphicsError, ConformanceCase] =
    val conditions = Vector("control", "task", "task", "control", "other")
    for
      plot <- Plot(conditions).addLayer(
        Layer.count(
          identity,
          order = CountOrder.declaredUnsafe(Vector("control", "task", "other")),
          padding = BandPadding.unsafe(0.2),
          params = Some(
            GraphicParams.unsafe(
              stroke = Some(Rgba.unsafe(30, 55, 85)),
              fill = Some(Rgba.unsafe(105, 165, 210))
            )
          )
        )
      )
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(
          policy = Some(LayoutPolicy()),
          expansion = RangeExpansion.none,
          guides = GuidePolicy.Derived()
        )
      )
    yield ConformanceCase(
      GraphicsName.unsafe("band-position-plot"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("x-axis"),
        GraphicsName.unsafe("y-axis"),
        GraphicsName.unsafe("stat-count-bar-0")
      ),
      Vector(
        RenderRequirement.Primitive(
          GraphicsName.unsafe("stat-count-bar-0"),
          RenderPrimitiveKind.Rectangle
        )
      )
    )

  private final case class PositionBar(category: String, value: Double, group: String)

  private val positionBars =
    Vector(
      PositionBar("A", 3.0, "red"),
      PositionBar("A", 2.0, "blue"),
      PositionBar("B", 1.0, "red"),
      PositionBar("B", 4.0, "blue")
    )

  def dodgedPositionCase: Either[GraphicsError, ConformanceCase] =
    positionBarCase("position-dodge", Position.Dodge())

  def stackedPositionCase: Either[GraphicsError, ConformanceCase] =
    positionBarCase("position-stack", Position.Stack())

  private def positionBarCase(
      name: String,
      position: Position
  ): Either[GraphicsError, ConformanceCase] =
    for
      band <- BandScale("category", DiscreteDomain.empty)
      mapping <- AesSpec
        .empty[PositionBar]
        .withPosition(_ => 0.0, _.value)
        .withGroup(_.group)
        .withFill(row => comparisonColor(row.group))
        .bindScale(ScaleBinding[PositionBar, String, Double](Aesthetic.X, _.category, band))
      layer <- Layer.fromMapping(
        Geom.Bar,
        mapping,
        inheritMapping = false,
        params = Some(GraphicParams.unsafe(stroke = Some(Rgba.unsafe(35, 45, 55)))),
        position = position
      )
      plot <- Plot(positionBars)
        .withLabels(PlotLabels(title = Some(name), x = Some("category"), y = Some("value")))
        .addLayer(layer)
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(
          policy = Some(LayoutPolicy()),
          guides = GuidePolicy.Derived(),
          theme = Theme.minimal
        )
      )
    yield ConformanceCase(
      GraphicsName.unsafe(name),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("x-axis"),
        GraphicsName.unsafe("y-axis"),
        GraphicsName.unsafe("stat-count-bar-0")
      ),
      Vector(
        RenderRequirement.Primitive(
          GraphicsName.unsafe("stat-count-bar-0"),
          RenderPrimitiveKind.Rectangle
        )
      )
    )

  private final case class JitterPoint(category: String, value: Double, group: String)

  def jitteredPositionCase: Either[GraphicsError, ConformanceCase] =
    val points = Vector(
      JitterPoint("A", 1.0, "red"),
      JitterPoint("A", 1.0, "blue"),
      JitterPoint("A", 1.6, "red"),
      JitterPoint("B", 2.0, "blue"),
      JitterPoint("B", 2.0, "red"),
      JitterPoint("B", 2.6, "blue")
    )
    for
      band <- BandScale("category", DiscreteDomain.empty)
      mapping <- AesSpec
        .empty[JitterPoint]
        .withPosition(_ => 0.0, _.value)
        .withColor(row => comparisonColor(row.group))
        .withFill(row => comparisonColor(row.group))
        .bindScale(ScaleBinding[JitterPoint, String, Double](Aesthetic.X, _.category, band))
      layer <- Layer.fromMapping(
        Geom.Point,
        mapping,
        inheritMapping = false,
        position = Position.jitterUnsafe(2026L, width = Some(0.22), height = Some(0.12))
      )
      plot <- Plot(points)
        .withLabels(
          PlotLabels(title = Some("position-jitter"), x = Some("category"), y = Some("value"))
        )
        .addLayer(layer)
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(
          policy = Some(LayoutPolicy()),
          guides = GuidePolicy.Derived(),
          theme = Theme.minimal
        )
      )
    yield ConformanceCase(
      GraphicsName.unsafe("position-jitter"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("x-axis"),
        GraphicsName.unsafe("y-axis")
      )
    )

  def scientificStatsCase: Either[GraphicsError, ConformanceCase] =
    final case class Sample(x: Double, y: Double)
    val samples = Vector.tabulate(5)(idx => Sample(idx.toDouble, idx.toDouble))
    val bins = HistogramBins.breaksUnsafe(Vector(0.0, 2.0, 4.0))
    val density =
      DensityConfig.fixedUnsafe(1.0, points = 16, domain = Some(Interval.unsafe(0.0, 4.0)))
    for
      histogram <- Plot(samples).addLayer(Layer.histogram[Sample](_.x, bins = bins))
      summarized <- histogram.addLayer(Layer.summary[Sample](_.x, _.y))
      plot <- summarized.addLayer(Layer.density[Sample](_.x, config = density))
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
      )
    yield ConformanceCase(
      GraphicsName.unsafe("scientific-stats"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("plot-panel"),
        GraphicsName.unsafe("stat-bin-bar-0"),
        GraphicsName.unsafe("stat-summary-interval-0"),
        GraphicsName.unsafe("stat-summary-mean-0"),
        GraphicsName.unsafe("stat-density-line")
      ),
      Vector(
        RenderRequirement
          .Primitive(GraphicsName.unsafe("stat-bin-bar-0"), RenderPrimitiveKind.Rectangle),
        RenderRequirement
          .Primitive(GraphicsName.unsafe("stat-summary-interval-0"), RenderPrimitiveKind.Polyline),
        RenderRequirement
          .Primitive(GraphicsName.unsafe("stat-summary-mean-0"), RenderPrimitiveKind.Disc),
        RenderRequirement.Primitive(
          GraphicsName.unsafe("stat-density-line"),
          RenderPrimitiveKind.Polyline
        )
      )
    )

  def flippedPlotCase: Either[GraphicsError, ConformanceCase] =
    val bins = HistogramBins.breaksUnsafe(Vector(0.0, 2.0, 4.0))
    for
      histogram <- Plot(Vector(0.0, 1.0, 2.0, 3.0, 4.0)).addLayer(
        Layer.histogram(identity, bins = bins)
      )
      scene <- PlotCompiler.compile(
        histogram.withCoord(Coord.Flipped()),
        PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
      )
    yield ConformanceCase(
      GraphicsName.unsafe("flipped-plot"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(GraphicsName.unsafe("plot-panel"), GraphicsName.unsafe("stat-bin-bar-0")),
      Vector(
        RenderRequirement.Primitive(
          GraphicsName.unsafe("stat-bin-bar-0"),
          RenderPrimitiveKind.Rectangle
        )
      )
    )

  def boundedGeomsCase: Either[GraphicsError, ConformanceCase] =
    final case class Bounds(xMin: Double, xMax: Double, yMin: Double, yMax: Double)
    val data = Vector(Bounds(0.1, 0.4, 0.2, 0.6), Bounds(0.55, 0.85, 0.35, 0.8))
    val fill = Some(GraphicParams.unsafe(fill = Some(Rgba.unsafe(75, 130, 185))))
    for
      rects <- Plot(data).addLayer(
        Layer.rect[Bounds](_.xMin, _.xMax, _.yMin, _.yMax, params = fill)
      )
      plot <- rects.addLayer(
        Layer.tile[Bounds](
          row => (row.xMin + row.xMax) / 2.0,
          row => (row.yMin + row.yMax) / 2.0,
          row => row.xMax - row.xMin,
          row => row.yMax - row.yMin,
          params = Some(GraphicParams.unsafe(fill = Some(Rgba.unsafe(205, 125, 55))))
        )
      )
      scene <- PlotCompiler.compile(plot)
    yield ConformanceCase(
      GraphicsName.unsafe("bounded-geoms"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(GraphicsName.unsafe("geom-rect-0"), GraphicsName.unsafe("geom-tile-0")),
      Vector(
        RenderRequirement
          .Primitive(GraphicsName.unsafe("geom-rect-0"), RenderPrimitiveKind.Rectangle),
        RenderRequirement.Primitive(
          GraphicsName.unsafe("geom-tile-0"),
          RenderPrimitiveKind.Rectangle
        )
      )
    )

  def segmentGeomsCase: Either[GraphicsError, ConformanceCase] =
    final case class SegmentDatum(
        x: Double,
        y: Double,
        xEnd: Double,
        yEnd: Double,
        lower: Double,
        upper: Double
    )
    val data = Vector(
      SegmentDatum(0.1, 0.2, 0.4, 0.6, 0.1, 0.5),
      SegmentDatum(0.6, 0.4, 0.9, 0.8, 0.3, 0.9)
    )
    for
      segments <- Plot(data).addLayer(Layer.segment[SegmentDatum](_.x, _.y, _.xEnd, _.yEnd))
      errors <- segments.addLayer(Layer.errorBar[SegmentDatum](_.x, _.lower, _.upper))
      horizontal <- errors.addLayer(Layer.hline[SegmentDatum](0.5))
      plot <- horizontal.addLayer(Layer.vline[SegmentDatum](0.5))
      scene <- PlotCompiler.compile(plot)
    yield ConformanceCase(
      GraphicsName.unsafe("segment-geoms"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(
        GraphicsName.unsafe("geom-segment-0"),
        GraphicsName.unsafe("geom-errorbar-0"),
        GraphicsName.unsafe("geom-hline"),
        GraphicsName.unsafe("geom-vline")
      ),
      Vector(
        RenderRequirement
          .Primitive(GraphicsName.unsafe("geom-segment-0"), RenderPrimitiveKind.Polyline),
        RenderRequirement
          .Primitive(GraphicsName.unsafe("geom-errorbar-0"), RenderPrimitiveKind.Polyline),
        RenderRequirement
          .Primitive(GraphicsName.unsafe("geom-hline"), RenderPrimitiveKind.Polyline),
        RenderRequirement.Primitive(GraphicsName.unsafe("geom-vline"), RenderPrimitiveKind.Polyline)
      )
    )

  def bandGeomsCase: Either[GraphicsError, ConformanceCase] =
    final case class Band(x: Double, y: Double, lower: Double, upper: Double)
    val data =
      Vector(Band(0.1, 0.3, 0.2, 0.4), Band(0.5, 0.7, 0.5, 0.85), Band(0.9, 0.5, 0.3, 0.65))
    val ribbonStyle = Some(GraphicParams.unsafe(fill = Some(Rgba.unsafe(80, 145, 205, 0.6))))
    val areaStyle = Some(GraphicParams.unsafe(fill = Some(Rgba.unsafe(215, 135, 65, 0.45))))
    for
      ribbon <- Plot(data).addLayer(Layer.ribbon[Band](_.x, _.lower, _.upper, params = ribbonStyle))
      plot <- ribbon.addLayer(Layer.area[Band](_.x, _.y, params = areaStyle))
      scene <- PlotCompiler.compile(plot)
    yield ConformanceCase(
      GraphicsName.unsafe("band-geoms"),
      ConformanceGroup.CompiledPlot,
      scene,
      Vector(GraphicsName.unsafe("geom-ribbon-0"), GraphicsName.unsafe("geom-area-0")),
      Vector(
        RenderRequirement
          .Primitive(GraphicsName.unsafe("geom-ribbon-0"), RenderPrimitiveKind.Polygon),
        RenderRequirement.Primitive(GraphicsName.unsafe("geom-area-0"), RenderPrimitiveKind.Polygon)
      )
    )

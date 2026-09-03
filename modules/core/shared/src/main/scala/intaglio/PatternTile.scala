package intaglio

/** Deterministic raster form of a validated pattern paint.
  *
  * Raster backends use the image as one reusable native texture while the logical width and height
  * retain the recipe's device-pixel spacing. The image owns ink/background RGBA only; a mark's
  * `GraphicParams.alpha` remains a separate compositing step in each backend.
  */
private[intaglio] final case class PatternTile(
    image: RasterImage,
    width: Double,
    height: Double
)

private[intaglio] object PatternTile:
  val MaxAxisPixels: Int = 1024
  private val SamplesPerAxis = 4

  def validate(paint: PatternPaint): Either[GraphicsError, Unit] =
    val spacing = paint.recipe.spacing
    if spacing <= MaxAxisPixels.toDouble then Right(())
    else
      Left(
        GraphicsError.InvalidPatternParameter(
          "raster",
          "spacing",
          spacing,
          s"no greater than $MaxAxisPixels device pixels"
        )
      )

  def validate(scene: DeviceScene): Either[GraphicsError, Unit] =
    def validateElements(elements: Vector[DeviceElement]): Either[GraphicsError, Unit] =
      var index = 0
      var result: Either[GraphicsError, Unit] = Right(())
      while index < elements.length && result.isRight do
        result = elements(index) match
          case DeviceElement.Group(_, _, _, children) =>
            validateElements(children)
          case DeviceElement.Annotated(_, children) =>
            validateElements(children)
          case DeviceElement.Mark(primitive) =>
            primitive match
              case DevicePrimitive.PointBatch(_, _, _, params, _) =>
                var mark = 0
                var batchResult: Either[GraphicsError, Unit] = Right(())
                val count = params.valueCount.getOrElse(1)
                while mark < count && batchResult.isRight do
                  batchResult = params
                    .valueAt(mark)
                    .fillPattern
                    .fold[Either[GraphicsError, Unit]](Right(()))(validate)
                  mark += 1
                batchResult
              case other =>
                val params = other match
                  case DevicePrimitive.Disc(_, _, _, gp, _)                  => Some(gp)
                  case DevicePrimitive.Polyline(_, true, gp, _)              => Some(gp)
                  case DevicePrimitive.CompoundPolygon(_, gp, _)             => Some(gp)
                  case DevicePrimitive.RectShape(_, _, _, _, _, gp, _)       => Some(gp)
                  case DevicePrimitive.Polyline(_, false, _, _)              => None
                  case DevicePrimitive.TextRun(_, _, _, _, _, _, _, _, _, _) => None
                  case DevicePrimitive.Image(_, _, _, _, _, _, _, _)         => None
                  case _: DevicePrimitive.PointBatch                         => None
                params
                  .flatMap(_.fillPattern)
                  .fold[Either[GraphicsError, Unit]](Right(()))(validate)
        index += 1
      result

    validateElements(scene.elements)

  def fromPaint(paint: PatternPaint): Either[GraphicsError, PatternTile] =
    validate(paint).map { _ =>
      val spacing = paint.recipe.spacing
      val axisPixels = math.max(1, math.ceil(spacing).toInt)
      val dimensions = RasterDimensions.unsafe(axisPixels, axisPixels)
      val image = RasterImage.tabulate(dimensions) { (x, y) =>
        val coverage = pixelCoverage(paint.recipe, x, y, axisPixels)
        composite(paint.ink, paint.background, coverage)
      }
      PatternTile(image, spacing, spacing)
    }

  private def pixelCoverage(recipe: PatternRecipe, x: Int, y: Int, axisPixels: Int): Double =
    var covered = 0
    var sampleY = 0
    while sampleY < SamplesPerAxis do
      var sampleX = 0
      while sampleX < SamplesPerAxis do
        val px =
          (x.toDouble + (sampleX.toDouble + 0.5) / SamplesPerAxis) * recipe.spacing / axisPixels
        val py =
          (y.toDouble + (sampleY.toDouble + 0.5) / SamplesPerAxis) * recipe.spacing / axisPixels
        if containsInk(recipe, px, py) then covered += 1
        sampleX += 1
      sampleY += 1
    covered.toDouble / (SamplesPerAxis * SamplesPerAxis).toDouble

  private def containsInk(recipe: PatternRecipe, x: Double, y: Double): Boolean =
    recipe match
      case value: PatternRecipe.AngledHatch =>
        torusLineDistance(x, y, value.spacing, value.angleDegrees) <= value.lineWidth / 2.0
      case value: PatternRecipe.CrossHatch =>
        math.min(
          torusLineDistance(x, y, value.spacing, value.angleDegrees),
          torusLineDistance(x, y, value.spacing, value.angleDegrees + 90.0)
        ) <= value.lineWidth / 2.0
      case value: PatternRecipe.ParallelRules =>
        val coordinate =
          value.orientation match
            case RuleOrientation.Horizontal => y
            case RuleOrientation.Vertical   => x
        edgeDistance(coordinate, value.spacing) <= value.lineWidth / 2.0
      case value: PatternRecipe.Stipple =>
        val dx = x - value.spacing / 2.0
        val dy = y - value.spacing / 2.0
        dx * dx + dy * dy <= value.radius * value.radius

  /** Distance to a line through the tile center on a square torus. Sampling neighboring tile copies
    * makes opposite bitmap edges join without a seam for every finite angle.
    */
  private def torusLineDistance(
      x: Double,
      y: Double,
      spacing: Double,
      angleDegrees: Double
  ): Double =
    val radians = angleDegrees * math.Pi / 180.0
    val normalX = math.cos(radians)
    val normalY = math.sin(radians)
    val centeredX = x - spacing / 2.0
    val centeredY = y - spacing / 2.0
    var best = Double.PositiveInfinity
    var tileY = -1
    while tileY <= 1 do
      var tileX = -1
      while tileX <= 1 do
        val dx = centeredX + tileX.toDouble * spacing
        val dy = centeredY + tileY.toDouble * spacing
        best = math.min(best, math.abs(dx * normalX + dy * normalY))
        tileX += 1
      tileY += 1
    best

  private def edgeDistance(coordinate: Double, spacing: Double): Double =
    math.min(coordinate, spacing - coordinate)

  private def composite(ink: Rgba, background: Option[Rgba], coverage: Double): Rgba32 =
    val base = background.getOrElse(Rgba.Transparent)
    val sourceAlpha = ink.alpha * coverage
    val destinationAlpha = base.alpha
    val outputAlpha = sourceAlpha + destinationAlpha * (1.0 - sourceAlpha)

    def channel(source: Int, destination: Int): Int =
      if outputAlpha == 0.0 then 0
      else
        math
          .round(
            (source.toDouble * sourceAlpha + destination.toDouble * destinationAlpha * (1.0 - sourceAlpha)) /
              outputAlpha
          )
          .toInt
          .max(0)
          .min(255)

    Rgba32.packUnsafe(
      channel(ink.red, base.red),
      channel(ink.green, base.green),
      channel(ink.blue, base.blue),
      math.round(outputAlpha * 255.0).toInt.max(0).min(255)
    )

package intaglio

enum GraphicsError extends IntaglioError:
  case BlankName(kind: String)
  case InvalidInterval(lower: Double, upper: Double)
  case EmptyContinuousRange
  case InvalidTransformDomain(name: String, lower: Double, upper: Double)
  case TransformOutsideDomain(name: String, value: Double)
  case InvalidLength(value: Double)
  case InvalidExtent(description: String)
  case InvalidColorChannel(channel: String, value: Int)
  case InvalidRasterDimensions(width: Int, height: Int)
  case RasterPixelCountMismatch(expected: Int, actual: Int)
  case RasterPixelOutsideBounds(x: Int, y: Int, width: Int, height: Int)
  case InvalidGridSize(sampling: String, minimum: Int, actual: Int)
  case InvalidGridDomain(lower: Double, upper: Double)
  case ScalarFieldValueCountMismatch(expected: Int, actual: Int)
  case NonFiniteScalarFieldValue(index: Int, value: Double)
  case ScalarFieldIndexOutsideBounds(x: Int, y: Int, width: Int, height: Int)
  case InvalidContourLevels(expectation: String, actual: String)
  case InvalidContourPoint(x: Double, y: Double)
  case ContourGridTooSmall(width: Int, height: Int)
  case InvalidContourTopology(detail: String)
  case InvalidAlpha(value: Double)
  case InvalidLineWidth(value: Double)
  case InvalidPatternParameter(
      recipe: String,
      parameter: String,
      value: Double,
      expectation: String
  )
  case InvalidRotation(value: Double)
  case InvalidBreakCount(value: Int)
  case InvalidBreakWidth(value: Double)
  case NonFiniteBreak(generator: String, value: Double)
  case BreakGenerationDidNotProgress(generator: String, previous: Double, next: Double)
  case BreakOutputLimitExceeded(generator: String, attempted: Int, maximum: Int)
  case BreakIterationLimitExceeded(generator: String, maximum: Int)
  case InvalidBand(center: Double, width: Double)
  case InvalidBandPadding(value: Double)
  case EmptyPalette
  case DiscretePaletteOverflow(scale: String, levels: Int, capacity: Int)
  case DuplicateLevel(level: String)
  case EmptyGeometry(kind: String)
  case InvalidGeometrySize(kind: String, minimum: Int, actual: Int)
  case MissingAesthetic(geom: String, aesthetic: String)
  case DuplicateScale(aesthetic: String)
  case ConflictingPlotScales(
      aesthetic: String,
      firstLayer: Int,
      firstScale: String,
      conflictingLayer: Int,
      conflictingScale: String
  )
  case UnsupportedGeom(geom: String)
  case InvalidStatGeom(stat: String, geom: String)
  case StatAestheticConflict(stat: String, aesthetic: String)
  case UnsupportedStatAesthetic(stat: String, aesthetic: String)
  case InvalidStatParameter(stat: String, parameter: String, value: String)
  case InvalidPositionParameter(
      position: String,
      parameter: String,
      value: Double,
      expectation: String
  )
  case InvalidPositionGeom(position: String, geom: String)
  case NonFiniteStatInput(stat: String, aesthetic: String, value: Double)
  case InsufficientStatData(stat: String, minimum: Int, actual: Int)
  case StatInputOutsideBins(value: Double, lower: Double, upper: Double)
  case StatInputOutsideGrid(
      stat: String,
      aesthetic: String,
      value: Double,
      lower: Double,
      upper: Double
  )
  case InvalidCoordinateRatio(value: Double)
  case DegenerateFixedAspect(xWidth: Double, yWidth: Double)
  case InvalidFacetColumns(value: Int)
  case EmptyFacet
  case FacetRequiresSolver
  case FacetFixedCoordinates
  case MissingLayout(feature: String)
  case InvalidLayoutCoordinate(kind: String, value: Double)
  case InvalidDeviceSize(width: Double, height: Double)
  case InvalidDeviceResolution(pixelsPerInch: Double)
  case InvalidDeviceValue(field: String, value: Double)
  case UnresolvableLength(description: String)
  case LayoutOverflow(region: String)
  case InvalidRangeExpansion(multiplicative: Double, additive: Double, zeroWidth: Double)
  case MixedPositionScaling(aesthetic: String)
  case InvalidAxisCoordinate(kind: String, value: Double)
  case AxisTickOutsideRange(value: Double, lower: Double, upper: Double)
  case AxisLabelCountMismatch(values: Int, labels: Int)

  def message: String =
    this match
      case BlankName(kind) =>
        s"$kind name must not be blank"
      case InvalidInterval(lower, upper) =>
        s"invalid interval [$lower, $upper]"
      case EmptyContinuousRange =>
        "continuous range has no finite values"
      case InvalidTransformDomain(name, lower, upper) =>
        s"transform '$name' has invalid domain [$lower, $upper]"
      case TransformOutsideDomain(name, value) =>
        s"value $value is outside transform '$name' domain"
      case InvalidLength(value) =>
        s"length value must be finite: $value"
      case InvalidExtent(description) =>
        s"extent must be provably non-negative: $description"
      case InvalidColorChannel(channel, value) =>
        s"color channel '$channel' must be in [0, 255]: $value"
      case InvalidRasterDimensions(width, height) =>
        s"raster dimensions must be positive with a representable pixel count: ${width}x$height"
      case RasterPixelCountMismatch(expected, actual) =>
        s"raster pixel count mismatch: expected $expected, found $actual"
      case RasterPixelOutsideBounds(x, y, width, height) =>
        s"raster pixel ($x, $y) is outside ${width}x$height"
      case InvalidGridSize(sampling, minimum, actual) =>
        s"$sampling grid axis requires at least $minimum samples: found $actual"
      case InvalidGridDomain(lower, upper) =>
        s"grid axis requires a finite, increasing domain: [$lower, $upper]"
      case ScalarFieldValueCountMismatch(expected, actual) =>
        s"scalar field value count mismatch: expected $expected, found $actual"
      case NonFiniteScalarFieldValue(index, value) =>
        s"scalar field sample $index must be finite: $value"
      case ScalarFieldIndexOutsideBounds(x, y, width, height) =>
        s"scalar field index ($x, $y) is outside ${width}x$height"
      case InvalidContourLevels(expectation, actual) =>
        s"contour levels require $expectation: $actual"
      case InvalidContourPoint(x, y) =>
        s"contour point coordinates must be finite: ($x, $y)"
      case ContourGridTooSmall(width, height) =>
        s"contour extraction requires at least a 2x2 scalar field: found ${width}x$height"
      case InvalidContourTopology(detail) =>
        s"invalid contour topology: $detail"
      case InvalidAlpha(value) =>
        s"alpha must be finite and in [0, 1]: $value"
      case InvalidLineWidth(value) =>
        s"line width must be finite and >= 0: $value"
      case InvalidPatternParameter(recipe, parameter, value, expectation) =>
        s"$recipe pattern requires $parameter to be $expectation: $value"
      case InvalidRotation(value) =>
        s"rotation angle must be finite: $value"
      case InvalidBreakCount(value) =>
        s"break count must be >= 1: $value"
      case InvalidBreakWidth(value) =>
        s"break width must be finite and > 0: $value"
      case NonFiniteBreak(generator, value) =>
        s"break generator '$generator' produced a non-finite value: $value"
      case BreakGenerationDidNotProgress(generator, previous, next) =>
        s"break generator '$generator' did not make floating-point progress: $previous -> $next"
      case BreakOutputLimitExceeded(generator, attempted, maximum) =>
        s"break generator '$generator' attempted $attempted values; maximum is $maximum"
      case BreakIterationLimitExceeded(generator, maximum) =>
        s"break generator '$generator' exceeded its deterministic iteration limit of $maximum"
      case InvalidBand(center, width) =>
        s"band center must be finite and width must be finite and > 0: ($center, $width)"
      case InvalidBandPadding(value) =>
        s"band padding must be finite and in [0, 1): $value"
      case EmptyPalette =>
        "palette must contain at least one value"
      case DiscretePaletteOverflow(scale, levels, capacity) =>
        s"discrete scale '$scale' has $levels levels but its palette capacity is $capacity; select an explicit cycling policy to reuse values"
      case DuplicateLevel(level) =>
        s"duplicate discrete level '$level'"
      case EmptyGeometry(kind) =>
        s"$kind geometry requires at least one element"
      case InvalidGeometrySize(kind, minimum, actual) =>
        s"$kind geometry requires at least $minimum elements: found $actual"
      case MissingAesthetic(geom, aesthetic) =>
        s"geom '$geom' requires aesthetic '$aesthetic'"
      case DuplicateScale(aesthetic) =>
        s"duplicate scale for aesthetic '$aesthetic'"
      case ConflictingPlotScales(
            aesthetic,
            firstLayer,
            firstScale,
            conflictingLayer,
            conflictingScale
          ) =>
        s"aesthetic '$aesthetic' uses different plot scales in layers $firstLayer ('$firstScale') and $conflictingLayer ('$conflictingScale'); bind one scale at plot level or reuse the same scale declaration"
      case UnsupportedGeom(geom) =>
        s"unsupported geom '$geom'"
      case InvalidStatGeom(stat, geom) =>
        s"stat '$stat' cannot produce geom '$geom'"
      case StatAestheticConflict(stat, aesthetic) =>
        s"stat '$stat' computes aesthetic '$aesthetic'; do not map it from input rows"
      case UnsupportedStatAesthetic(stat, aesthetic) =>
        s"stat '$stat' does not yet aggregate input aesthetic '$aesthetic'"
      case InvalidStatParameter(stat, parameter, value) =>
        s"stat '$stat' requires a valid $parameter: $value"
      case InvalidPositionParameter(position, parameter, value, expectation) =>
        s"position '$position' requires $parameter to be $expectation: $value"
      case InvalidPositionGeom(position, geom) =>
        s"position '$position' cannot adjust geom '$geom'"
      case NonFiniteStatInput(stat, aesthetic, value) =>
        s"stat '$stat' requires finite '$aesthetic' values: $value"
      case InsufficientStatData(stat, minimum, actual) =>
        s"stat '$stat' requires at least $minimum observations: found $actual"
      case StatInputOutsideBins(value, lower, upper) =>
        s"histogram value $value is outside explicit breaks [$lower, $upper]"
      case StatInputOutsideGrid(stat, aesthetic, value, lower, upper) =>
        s"stat '$stat' value $value for '$aesthetic' is outside fixed domain [$lower, $upper]"
      case InvalidCoordinateRatio(value) =>
        s"coordinate ratio must be finite and > 0: $value"
      case DegenerateFixedAspect(xWidth, yWidth) =>
        s"fixed coordinates require non-degenerate expanded ranges: x width $xWidth, y width $yWidth"
      case InvalidFacetColumns(value) =>
        s"facet column count must be >= 1: $value"
      case EmptyFacet =>
        "facet specification produced no panels"
      case FacetRequiresSolver =>
        "facets require a layout policy; explicit single-panel layouts and frames are not facet grids"
      case FacetFixedCoordinates =>
        "fixed coordinates are not yet supported for facet grids"
      case MissingLayout(feature) =>
        s"$feature requires a panel layout"
      case InvalidLayoutCoordinate(kind, value) =>
        s"layout $kind coordinate must be finite: $value"
      case InvalidDeviceSize(width, height) =>
        s"device size must be finite and positive: ${width}x$height"
      case InvalidDeviceResolution(pixelsPerInch) =>
        s"device resolution must be finite and positive: $pixelsPerInch"
      case InvalidDeviceValue(field, value) =>
        s"device $field must be finite with magnitude <= 1e13: $value"
      case UnresolvableLength(description) =>
        s"length cannot be resolved to device pixels: $description"
      case LayoutOverflow(region) =>
        s"plot layout leaves no room for the $region"
      case InvalidRangeExpansion(multiplicative, additive, zeroWidth) =>
        s"range expansion must be finite with multiplicative/additive >= 0 and zeroWidth > 0: ($multiplicative, $additive, $zeroWidth)"
      case MixedPositionScaling(aesthetic) =>
        s"aesthetic '$aesthetic' is scaled in some layers and unscaled in others; panel ranges cannot mix mapped and raw coordinates"
      case InvalidAxisCoordinate(kind, value) =>
        s"axis $kind must be finite and non-negative where applicable: $value"
      case AxisTickOutsideRange(value, lower, upper) =>
        s"axis tick $value is outside range [$lower, $upper]"
      case AxisLabelCountMismatch(values, labels) =>
        s"axis labeler returned $labels labels for $values tick values"

object GraphicsError:
  extension [A](either: Either[GraphicsError, A])
    def orThrow: A =
      either match
        case Right(value) => value
        case Left(error)  => throw new IllegalArgumentException(error.message)

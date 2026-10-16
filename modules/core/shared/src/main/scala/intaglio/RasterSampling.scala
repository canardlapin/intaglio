package intaglio

/** Pixel-centre nearest-neighbour mapping for an axis-aligned image in physical device pixels. The
  * covered integer columns are `[left, right)`: their centres lie in `[x, x + width)`. Each centre
  * samples `floor((centre - x) / width * sourceWidth)`, independently of a backend's image scaler.
  * Rows follow the same rule in visual, top-down order.
  */
final case class NearestRasterGrid private[intaglio] (
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    source: RasterDimensions,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int
):
  def sourceColumn(deviceColumn: Int): Option[Int] =
    if deviceColumn < left || deviceColumn >= right then None
    else
      Some(
        math
          .floor((deviceColumn + 0.5 - x) / width * source.width)
          .toInt
          .max(0)
          .min(source.width - 1)
      )

  def sourceRow(deviceRow: Int): Option[Int] =
    if deviceRow < top || deviceRow >= bottom then None
    else
      Some(
        math
          .floor((deviceRow + 0.5 - y) / height * source.height)
          .toInt
          .max(0)
          .min(source.height - 1)
      )

object RasterSampling:
  /** Bound temporary resampling storage to the conservative JavaFX resource policy. */
  val MaxPreparedDimension: Int = 4096

  def validate(scene: DeviceScene): Either[GraphicsError, Unit] =
    def elements(values: Vector[DeviceElement]): Either[GraphicsError, Unit] =
      values.foldLeft[Either[GraphicsError, Unit]](Right(())) { (result, element) =>
        result.flatMap { _ =>
          element match
            case DeviceElement.Mark(p: DevicePrimitive.Image)
                if p.interpolation == RasterInterpolation.Nearest =>
              nearestGrid(p.image.dimensions, p.x, p.y, p.width, p.height).flatMap(checkSize)
            case DeviceElement.Group(_, _, _, children) => elements(children)
            case DeviceElement.Annotated(_, children)   => elements(children)
            case _                                      => Right(())
        }
      }
    elements(scene.elements)

  private def checkSize(grid: NearestRasterGrid): Either[GraphicsError, Unit] =
    val w = grid.right.toLong - grid.left.toLong
    val h = grid.bottom.toLong - grid.top.toLong
    if w > MaxPreparedDimension || h > MaxPreparedDimension then
      Left(
        GraphicsError.InvalidRasterDimensions(w.min(Int.MaxValue).toInt, h.min(Int.MaxValue).toInt)
      )
    else Right(())

  def nearestGrid(
      source: RasterDimensions,
      x: Double,
      y: Double,
      width: Double,
      height: Double
  ): Either[GraphicsError, NearestRasterGrid] =
    val edges = Vector(x, y, x + width, y + height)
    if !edges.forall(v => v.isFinite && v >= Int.MinValue + 1.0 && v <= Int.MaxValue - 1.0)
      || !width.isFinite || !height.isFinite || width < 0.0 || height < 0.0
    then Left(GraphicsError.InvalidDeviceSize(width, height))
    else
      Right(
        NearestRasterGrid(
          x,
          y,
          width,
          height,
          source,
          math.ceil(x - 0.5).toInt,
          math.ceil(y - 0.5).toInt,
          math.ceil(x + width - 0.5).toInt,
          math.ceil(y + height - 0.5).toInt
        )
      )

  /** Prepare a one-to-one device image: no backend resampling remains. Empty coverage draws
    * nothing. The original scene image remains intact for source-cell inspection and picking.
    * Smooth interpolation retains the backend's continuous image path.
    */
  def prepare(
      image: RasterImage,
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      interpolation: RasterInterpolation
  ): Either[GraphicsError, Option[(RasterImage, Double, Double, Double, Double)]] =
    if interpolation == RasterInterpolation.Smooth then Right(Some((image, x, y, width, height)))
    else
      nearestGrid(image.dimensions, x, y, width, height).flatMap { grid =>
        checkSize(grid).flatMap { _ =>
          val w = grid.right.toLong - grid.left.toLong
          val h = grid.bottom.toLong - grid.top.toLong
          if w == 0 || h == 0 then Right(None)
          else if w > Int.MaxValue || h > Int.MaxValue then
            Left(GraphicsError.InvalidRasterDimensions(Int.MaxValue, Int.MaxValue))
          else
            RasterDimensions(w.toInt, h.toInt).map { dimensions =>
              val sampled =
                if x == grid.left.toDouble && y == grid.top.toDouble &&
                  width == image.width.toDouble && height == image.height.toDouble
                then image
                else
                  val columns = Array.tabulate(dimensions.width)(column =>
                    grid.sourceColumn(grid.left + column).get
                  )
                  val rows =
                    Array.tabulate(dimensions.height)(row => grid.sourceRow(grid.top + row).get)
                  RasterImage.tabulate(dimensions) { (column, row) =>
                    image.pixelUnsafe(columns(column), rows(row))
                  }
              Some((sampled, grid.left.toDouble, grid.top.toDouble, w.toDouble, h.toDouble))
            }
        }
      }

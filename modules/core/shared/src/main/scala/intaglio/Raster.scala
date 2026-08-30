package intaglio

/** Packed 8-bit RGBA pixel (`0xRRGGBBAA`). The opaque representation keeps raster storage primitive
  * while preventing arbitrary integers from entering public image constructors.
  */
opaque type Rgba32 = Int

object Rgba32:
  def apply(red: Int, green: Int, blue: Int, alpha: Int = 255): Either[GraphicsError, Rgba32] =
    if red < 0 || red > 255 then Left(GraphicsError.InvalidColorChannel("red", red))
    else if green < 0 || green > 255 then Left(GraphicsError.InvalidColorChannel("green", green))
    else if blue < 0 || blue > 255 then Left(GraphicsError.InvalidColorChannel("blue", blue))
    else if alpha < 0 || alpha > 255 then Left(GraphicsError.InvalidColorChannel("alpha", alpha))
    else Right(pack(red, green, blue, alpha))

  def unsafe(red: Int, green: Int, blue: Int, alpha: Int = 255): Rgba32 =
    apply(red, green, blue, alpha).orThrow

  def fromRgba(color: Rgba): Rgba32 =
    pack(color.red, color.green, color.blue, math.round(color.alpha * 255.0).toInt)

  /** Interpret one packed `0xRRGGBBAA` word. Every 32-bit value is a valid pixel; the named
    * conversion keeps packed-buffer boundaries explicit.
    */
  inline def fromPackedInt(value: Int): Rgba32 =
    value

  /** Internal hot-path constructor. Callers must prove every channel is in `[0, 255]`; public and
    * trust-boundary code must use `apply` or `unsafe`.
    */
  private[intaglio] def packUnsafe(red: Int, green: Int, blue: Int, alpha: Int): Rgba32 =
    pack(red, green, blue, alpha)

  private def pack(red: Int, green: Int, blue: Int, alpha: Int): Rgba32 =
    (red << 24) | (green << 16) | (blue << 8) | alpha

extension (pixel: Rgba32)
  def red: Int =
    (pixel >>> 24) & 0xff

  def green: Int =
    (pixel >>> 16) & 0xff

  def blue: Int =
    (pixel >>> 8) & 0xff

  def alpha: Int =
    pixel & 0xff

  def toRgba: Rgba =
    Rgba.unsafe(pixel.red, pixel.green, pixel.blue, pixel.alpha.toDouble / 255.0)

  /** Return this pixel's exact `0xRRGGBBAA` packed word. */
  inline def toPackedInt: Int =
    pixel

final case class RasterDimensions private (width: Int, height: Int):
  val pixelCount: Int =
    width * height

object RasterDimensions:
  def apply(width: Int, height: Int): Either[GraphicsError, RasterDimensions] =
    val count = width.toLong * height.toLong
    val scanlineBytes = count * 4L + height.toLong
    val storedBlocks = (scanlineBytes + 65534L) / 65535L
    val encodedBytes = scanlineBytes + storedBlocks * 5L + 63L
    if width <= 0 || height <= 0 || count > Int.MaxValue || encodedBytes > Int.MaxValue then
      Left(GraphicsError.InvalidRasterDimensions(width, height))
    else Right(new RasterDimensions(width, height))

  def unsafe(width: Int, height: Int): RasterDimensions =
    apply(width, height).orThrow

enum RasterInterpolation:
  case Nearest
  case Smooth

/** Immutable row-major raster pixels. Row zero is the visual top row, matching conventional image
  * formats and every concrete backend. Scene placement is still resolved through the y-up graphics
  * coordinate system.
  */
final class RasterImage private (
    val dimensions: RasterDimensions,
    private val data: Array[Int]
):
  private lazy val valueHash: Int =
    var hash = dimensions.hashCode
    var idx = 0
    while idx < data.length do
      hash = 31 * hash + data(idx)
      idx += 1
    hash

  def width: Int =
    dimensions.width

  def height: Int =
    dimensions.height

  def pixel(x: Int, y: Int): Either[GraphicsError, Rgba32] =
    if x < 0 || x >= width || y < 0 || y >= height then
      Left(GraphicsError.RasterPixelOutsideBounds(x, y, width, height))
    else Right(Rgba32.fromPackedInt(data(y * width + x)))

  def pixelUnsafe(x: Int, y: Int): Rgba32 =
    pixel(x, y).orThrow

  private[intaglio] def packedAt(index: Int): Rgba32 =
    Rgba32.fromPackedInt(data(index))

  override def equals(other: Any): Boolean =
    other match
      case that: RasterImage =>
        if dimensions != that.dimensions || data.length != that.data.length then false
        else
          var idx = 0
          var equal = true
          while idx < data.length && equal do
            equal = data(idx) == that.data(idx)
            idx += 1
          equal
      case _ => false

  override def hashCode: Int =
    valueHash

  override def toString: String =
    s"RasterImage(${width}x$height)"

object RasterImage:
  /** Wrap a freshly allocated packed buffer without copying it.
    *
    * The caller transfers exclusive ownership of `pixels` to the returned image and must never read
    * or mutate the array afterward.
    */
  def unsafeFromOwnedPackedArray(
      dimensions: RasterDimensions,
      pixels: Array[Int]
  ): RasterImage =
    require(pixels.length == dimensions.pixelCount, "raster pixel count must match dimensions")
    new RasterImage(dimensions, pixels)

  /** Build directly into primitive packed storage without an intermediate collection. The callback
    * is evaluated in row-major visual order.
    */
  def tabulate(
      dimensions: RasterDimensions
  )(pixelAt: (Int, Int) => Rgba32): RasterImage =
    val data = new Array[Int](dimensions.pixelCount)
    var y = 0
    while y < dimensions.height do
      var x = 0
      val rowOffset = y * dimensions.width
      while x < dimensions.width do
        data(rowOffset + x) = pixelAt(x, y).toPackedInt
        x += 1
      y += 1
    new RasterImage(dimensions, data)

  def fromPacked(
      dimensions: RasterDimensions,
      pixels: IndexedSeq[Rgba32]
  ): Either[GraphicsError, RasterImage] =
    if pixels.length != dimensions.pixelCount then
      Left(GraphicsError.RasterPixelCountMismatch(dimensions.pixelCount, pixels.length))
    else
      val data = new Array[Int](pixels.length)
      var idx = 0
      while idx < pixels.length do
        data(idx) = pixels(idx).toPackedInt
        idx += 1
      Right(new RasterImage(dimensions, data))

  def fromRgba(
      dimensions: RasterDimensions,
      pixels: IndexedSeq[Rgba]
  ): Either[GraphicsError, RasterImage] =
    if pixels.length != dimensions.pixelCount then
      Left(GraphicsError.RasterPixelCountMismatch(dimensions.pixelCount, pixels.length))
    else
      val data = new Array[Int](pixels.length)
      var idx = 0
      while idx < pixels.length do
        data(idx) = Rgba32.fromRgba(pixels(idx)).toPackedInt
        idx += 1
      Right(new RasterImage(dimensions, data))

  def solid(dimensions: RasterDimensions, pixel: Rgba32): RasterImage =
    val data = Array.fill(dimensions.pixelCount)(pixel.toPackedInt)
    new RasterImage(dimensions, data)

  def unsafePacked(dimensions: RasterDimensions, pixels: IndexedSeq[Rgba32]): RasterImage =
    fromPacked(dimensions, pixels).orThrow

  def unsafeRgba(dimensions: RasterDimensions, pixels: IndexedSeq[Rgba]): RasterImage =
    fromRgba(dimensions, pixels).orThrow

package intaglio

import scala.collection.immutable.ArraySeq

class RasterSuite extends munit.FunSuite:

  test("raster dimensions reject non-positive and overflowing shapes") {
    assertEquals(
      RasterDimensions(width = 0, height = 2).left.toOption,
      Some(GraphicsError.InvalidRasterDimensions(0, 2))
    )
    assertEquals(
      RasterDimensions(width = Int.MaxValue, height = 2).left.toOption,
      Some(GraphicsError.InvalidRasterDimensions(Int.MaxValue, 2))
    )
    assertEquals(RasterDimensions.unsafe(3, 2).pixelCount, 6)
  }

  test("rgba32 is a checked packed pixel with exact byte channels") {
    val pixel = Rgba32.unsafe(red = 12, green = 34, blue = 56, alpha = 78)

    assertEquals(pixel.red, 12)
    assertEquals(pixel.green, 34)
    assertEquals(pixel.blue, 56)
    assertEquals(pixel.alpha, 78)
    assertEquals(pixel.toPackedInt, 0x0c22384e)
    assertEquals(Rgba32.fromPackedInt(pixel.toPackedInt), pixel)
    assertEquals(
      Rgba32(red = -1, green = 0, blue = 0).left.toOption,
      Some(GraphicsError.InvalidColorChannel("red", -1))
    )
  }

  test("unsafe packed-array construction is an explicit zero-copy ownership transfer") {
    val dimensions = RasterDimensions.unsafe(2, 1)
    val pixels = Array(
      Rgba32.unsafe(1, 2, 3, 4).toPackedInt,
      Rgba32.unsafe(5, 6, 7, 8).toPackedInt
    )
    val image = RasterImage.unsafeFromOwnedPackedArray(dimensions, pixels)

    assertEquals(image.pixelUnsafe(0, 0), Rgba32.unsafe(1, 2, 3, 4))
    assertEquals(image.pixelUnsafe(1, 0), Rgba32.unsafe(5, 6, 7, 8))
    // Deliberately violate the ownership contract to prove this path does not copy.
    pixels(0) = Rgba32.unsafe(9, 10, 11, 12).toPackedInt
    assertEquals(image.pixelUnsafe(0, 0), Rgba32.unsafe(9, 10, 11, 12))
    intercept[IllegalArgumentException]:
      RasterImage.unsafeFromOwnedPackedArray(dimensions, Array.emptyIntArray)
  }

  test("raster images own their packed storage and compare by pixel value") {
    val dimensions = RasterDimensions.unsafe(2, 2)
    val sourceArray = Array(
      Rgba32.unsafe(255, 0, 0),
      Rgba32.unsafe(0, 255, 0, 128),
      Rgba32.unsafe(0, 0, 255),
      Rgba32.unsafe(255, 255, 255, 0)
    )
    val source = ArraySeq.unsafeWrapArray(sourceArray)
    val image = RasterImage.fromPacked(dimensions, source).toOption.get
    val same = RasterImage.fromPacked(dimensions, source).toOption.get
    sourceArray(0) = Rgba32.unsafe(0, 0, 0)

    assertEquals(image.pixelUnsafe(0, 0), Rgba32.unsafe(255, 0, 0))
    assertEquals(image.pixelUnsafe(1, 0), Rgba32.unsafe(0, 255, 0, 128))
    assertEquals(image.pixelUnsafe(0, 1), Rgba32.unsafe(0, 0, 255))
    assertEquals(image, same)
    assertEquals(image.hashCode, same.hashCode)
  }

  test("raster constructors validate pixel counts and coordinates") {
    val dimensions = RasterDimensions.unsafe(2, 2)
    assertEquals(
      RasterImage.fromPacked(dimensions, Vector(Rgba32.unsafe(0, 0, 0))).left.toOption,
      Some(GraphicsError.RasterPixelCountMismatch(expected = 4, actual = 1))
    )
    val image = RasterImage.solid(dimensions, Rgba32.unsafe(10, 20, 30))
    assertEquals(
      image.pixel(2, 0).left.toOption,
      Some(GraphicsError.RasterPixelOutsideBounds(2, 0, 2, 2))
    )
  }

  test("RGBA colors quantize alpha to the nearest raster byte") {
    val image = RasterImage
      .fromRgba(
        RasterDimensions.unsafe(1, 1),
        Vector(Rgba.unsafe(10, 20, 30, alpha = 0.5))
      )
      .toOption
      .get

    assertEquals(image.pixelUnsafe(0, 0), Rgba32.unsafe(10, 20, 30, 128))
  }

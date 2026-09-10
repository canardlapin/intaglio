package intaglio

class PatternTileSuite extends munit.FunSuite:

  private def required[A](value: Either[GraphicsError, A]): A =
    value.fold(error => fail(error.message), identity)

  test("all four monochrome recipes produce distinct non-solid tiles") {
    val recipes = Vector[PatternRecipe](
      required(PatternRecipe.angledHatch(30.0, 16.0, 2.0)),
      required(PatternRecipe.crossHatch(30.0, 16.0, 2.0)),
      required(PatternRecipe.parallelRules(RuleOrientation.Horizontal, 16.0, 2.0)),
      required(PatternRecipe.stipple(16.0, 3.0))
    )
    val tiles = recipes.map { recipe =>
      required(PatternTile.fromPaint(PatternPaint(recipe, Rgba.Black, Some(Rgba.White))))
    }

    tiles.foreach { tile =>
      assertEquals(tile.image.dimensions, RasterDimensions.unsafe(16, 16))
      val pixels = (0 until tile.image.dimensions.pixelCount).map(tile.image.packedAt)
      assert(pixels.exists(_.red < 64), "recipe must contain visible dark ink")
      assert(pixels.exists(_.red > 191), "recipe must retain visible background")
      assert(pixels.forall(pixel => pixel.red == pixel.green && pixel.green == pixel.blue))
    }
    tiles.indices.foreach { left =>
      ((left + 1) until tiles.length).foreach { right =>
        assertNotEquals(tiles(left).image, tiles(right).image)
      }
    }
  }

  test("tile pixels preserve source-over ink and background alpha") {
    val recipe = required(PatternRecipe.parallelRules(RuleOrientation.Vertical, 8.0, 4.0))
    val paint = PatternPaint(
      recipe,
      Rgba.unsafe(255, 0, 0, 0.5),
      Some(Rgba.unsafe(0, 0, 255, 0.5))
    )
    val tile = required(PatternTile.fromPaint(paint))

    val ink = tile.image.pixelUnsafe(0, 4)
    assertEquals((ink.red, ink.green, ink.blue, ink.alpha), (170, 0, 85, 191))
    val background = tile.image.pixelUnsafe(4, 4)
    assertEquals(
      (background.red, background.green, background.blue, background.alpha),
      (0, 0, 255, 128)
    )
  }

  test("positive hatch angles turn clockwise from a vertical rule in y-down space") {
    val recipe = required(PatternRecipe.angledHatch(45.0, 16.0, 1.0))
    val tile = required(PatternTile.fromPaint(PatternPaint(recipe, Rgba.Black, Some(Rgba.White))))

    assert(tile.image.pixelUnsafe(3, 12).red < 64, "clockwise diagonal must carry ink")
    assert(tile.image.pixelUnsafe(3, 3).red > 191, "the reflected diagonal must remain background")
  }

  test("raster resource limits fail through the typed pattern boundary") {
    val recipe = required(
      PatternRecipe.parallelRules(
        RuleOrientation.Horizontal,
        PatternTile.MaxAxisPixels.toDouble + 1.0,
        1.0
      )
    )

    assertEquals(
      PatternTile.fromPaint(PatternPaint(recipe, Rgba.Black)).left.toOption,
      Some(
        GraphicsError.InvalidPatternParameter(
          "raster",
          "spacing",
          PatternTile.MaxAxisPixels.toDouble + 1.0,
          s"no greater than ${PatternTile.MaxAxisPixels} device pixels"
        )
      )
    )
  }

package intaglio

/** Grid validation and visual (top-row-first) order shared by painting and picking. */
private[intaglio] object RasterGeom:
  def visualOrder[Row](rows: Vector[ResolvedRow[Row]]): Vector[Int] =
    rows.indices.toVector.sortBy(i => (-rows(i).y, rows(i).x))

  def lower[Row](
      rows: Vector[ResolvedRow[Row]],
      interpolation: RasterInterpolation
  ): Either[GraphicsError, Vector[Grob]] =
    if rows.isEmpty then Right(Vector.empty)
    else
      val xs = rows.map(_.x).distinct.sorted
      val ys = rows.map(_.y).distinct.sorted
      val ordered = visualOrder(rows).map(rows(_))
      val first = ordered.head
      val dx = first.xMax.getOrElse(first.x) - first.xMin.getOrElse(first.x)
      val dy = first.yMax.getOrElse(first.y) - first.yMin.getOrElse(first.y)
      def close(a: Double, b: Double, step: Double): Boolean =
        math.abs(a - b) <= math.max(1e-9 * step, 4 * math.ulp(math.max(math.abs(a), math.abs(b))))
      val regular = xs.length.toLong * ys.length == rows.length.toLong && dx > 0 && dy > 0 &&
        rows.map(row => (row.x, row.y)).distinct.size == rows.size &&
        ordered.zipWithIndex.forall { (row, i) =>
          val x = xs.head + (i % xs.length) * dx
          val y = ys.last - (i / xs.length) * dy
          close(row.x, x, dx) && close(row.y, y, dy) &&
          row.xMin.exists(close(_, x - dx / 2, dx)) && row.xMax.exists(close(_, x + dx / 2, dx)) &&
          row.yMin.exists(close(_, y - dy / 2, dy)) && row.yMax.exists(close(_, y + dy / 2, dy))
        }
      if !regular then
        Left(
          GraphicsError.InvalidGeomAestheticContract(
            "raster requires a complete, uniformly spaced rectangular grid"
          )
        )
      else
        RasterDimensions(xs.length, ys.length).flatMap { dimensions =>
          val image = RasterImage.tabulate(dimensions) { (x, y) =>
            val row = ordered(y * dimensions.width + x)
            val color = row.gp.fill.getOrElse(Rgba.unsafe(0, 0, 0, 0.0))
            Rgba32.fromRgba(
              Rgba.unsafe(color.red, color.green, color.blue, color.alpha * row.gp.alpha)
            )
          }
          Grob
            .image(
              image,
              at = Point.nativeUnsafe(xs.head - dx / 2, ys.head - dy / 2),
              size = Size.fromExtents(
                ExtentExpr.nativeUnsafe(dx * xs.length),
                ExtentExpr.nativeUnsafe(dy * ys.length)
              ),
              anchor = Anchor(HJust.Left, VJust.Bottom),
              interpolation = interpolation,
              name = Some(GraphicsName.unsafe("geom-raster"))
            )
            .map(Vector(_))
        }

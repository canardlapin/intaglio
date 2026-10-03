package intaglio.browser

import intaglio.*
import intaglio.interaction.*

/** Paint supplied by an application for a logical target. None preserves the original channel.
  * Opacity replaces the mark opacity; it must be finite and within [0, 1]. Raster cells support
  * fill and opacity, but have no stroke channel: a cell's fill replaces its colour, and the fill's
  * alpha and the opacity both multiply the cell's own pixel alpha, so a transparent (missing) cell
  * stays transparent. Other images support opacity only. A channel a target cannot show is rejected
  * rather than ignored.
  */
final case class WidgetTargetStyle(
    fill: Option[Rgba] = None,
    stroke: Option[Rgba] = None,
    opacity: Option[Double] = None
):
  private[browser] def validate: Either[IntaglioError, Unit] =
    Either.cond(
      opacity.forall(v => v.isFinite && v >= 0 && v <= 1),
      (),
      InteractionError.InvalidValue("target style opacity", "must be finite and between 0 and 1")
    )
  private[browser] def applyTo(gp: GraphicParams): Either[IntaglioError, GraphicParams] =
    gp.withAestheticOverrides(stroke = stroke, fill = fill, alpha = opacity)

  /** One raster cell's pixel under this style. */
  private[browser] def applyTo(pixel: Rgba32): Rgba32 =
    val colour = fill.fold(pixel)(Rgba32.fromRgba)
    val alpha = pixel.alpha * fill.fold(1.0)(_ => colour.alpha / 255.0) * opacity.getOrElse(1.0)
    Rgba32.unsafe(colour.red, colour.green, colour.blue, math.round(alpha).toInt)

object WidgetTargetStyle:
  private type Result[X] = Either[IntaglioError, X]

  private def traverse[X, Y](values: Vector[X])(f: X => Result[Y]): Result[Vector[Y]] =
    values.foldLeft[Result[Vector[Y]]](Right(Vector.empty)) { (done, value) =>
      done.flatMap(out => f(value).map(out :+ _))
    }

  private[browser] def paint[A](
      view: SvgWidgetView[A],
      styles: Map[VisualTargetId, WidgetTargetStyle]
  ): Result[Scene] =
    val groups = view.plans.flatMap(_.groups)
    val byRoute = groups.map(group => group.name.value -> group).toMap
    def routeOf(meta: GrobMeta): Option[TargetGroup[A]] =
      meta.data
        .collectFirst { case (key, value) if key == InteractionCompiler.targetAttribute => value }
        .flatMap(byRoute.get)
    def isRaster(group: TargetGroup[A]): Boolean =
      group.at(0).toOption.flatMap(_.rasterCell).nonEmpty
    // Targets drawn (in whole or part) as a non-raster image, which has no fill or stroke channel.
    val imageRoutes: Set[String] =
      def images(grob: Grob): Boolean = grob match
        case _: Grob.Image     => true
        case a: Grob.Annotated => images(a.child)
        case other             => other.children.exists(images)
      def walk(grob: Grob): Vector[String] = grob match
        case x: Grob.Annotated =>
          routeOf(x.meta)
            .filter(group => !isRaster(group) && images(x.child))
            .map(_.name.value)
            .toVector ++ walk(x.child)
        case x => x.children.flatMap(walk)
      view.scene.grobs.flatMap(walk).toSet
    def owns(group: TargetGroup[A], id: VisualTargetId): Boolean =
      val series = group.series
      id.plan == series.plan && id.revision == series.revision && id.scope == series.scope &&
      id.ordinal >= series.first && id.ordinal.toLong < series.first.toLong + series.size
    // Entries are checked in ordinal order, so the error reported for several bad ones is stable.
    val ordered = styles.toVector.sortBy((id, _) => (id.plan.value, id.scope.value, id.ordinal))
    val validation = traverse(ordered) { (id, style) =>
      groups.find(owns(_, id)) match
        case None =>
          Left(InteractionError.InvalidValue("target style", "target is not in this view"))
        case Some(group) if isRaster(group) && style.stroke.nonEmpty =>
          Left(InteractionError.UnsupportedCapability("raster cell stroke styles"))
        case Some(group)
            if imageRoutes(group.name.value) && (style.fill.nonEmpty || style.stroke.nonEmpty) =>
          Left(InteractionError.UnsupportedCapability("image fill and stroke styles"))
        case Some(_) => style.validate
    }
    def styled(grob: Grob, style: WidgetTargetStyle): Result[Grob] = grob match
      case x: Grob.Points     => style.applyTo(x.gp).map(gp => x.copy(gp = gp))
      case x: Grob.PointBatch =>
        traverse(x.points.indices.toVector.map(x.graphicParams.valueAt))(style.applyTo)
          .map(gps => x.copy(graphicParams = BatchColumn.compact(gps)))
      case x: Grob.Lines           => style.applyTo(x.gp).map(gp => x.copy(gp = gp))
      case x: Grob.Polygon         => style.applyTo(x.gp).map(gp => x.copy(gp = gp))
      case x: Grob.CompoundPolygon => style.applyTo(x.gp).map(gp => x.copy(gp = gp))
      case x: Grob.Segments        => style.applyTo(x.gp).map(gp => x.copy(gp = gp))
      case x: Grob.Rect            => style.applyTo(x.gp).map(gp => x.copy(gp = gp))
      case x: Grob.Circle          => style.applyTo(x.gp).map(gp => x.copy(gp = gp))
      case x: Grob.Text            => style.applyTo(x.gp).map(gp => x.copy(gp = gp))
      case x: Grob.Image           => Right(x.copy(alpha = style.opacity.getOrElse(x.alpha)))
      case x: Grob.Group           =>
        traverse(x.children)(styled(_, style)).map(children => x.copy(children = children))
      case x: Grob.Annotated => styled(x.child, style).map(child => x.copy(child = child))
    def route(grob: Grob, group: TargetGroup[A]): Result[Grob] =
      def styleAt(index: Int) = group.series.at(index).toOption.flatMap(styles.get)
      grob match
        case x: Grob.PointBatch if group.size == x.points.size =>
          traverse(x.points.indices.toVector) { i =>
            val gp = x.graphicParams.valueAt(i)
            styleAt(i).fold[Result[GraphicParams]](Right(gp))(_.applyTo(gp))
          }.map(gps => x.copy(graphicParams = BatchColumn.compact(gps)))
        case x: Grob.Image if isRaster(group) =>
          // The same row-major cell order raster picking resolves a pointer to.
          val width = x.image.width
          Right(x.copy(image = RasterImage.tabulate(x.image.dimensions) { (column, row) =>
            val index = row * width + column
            val original = x.image.packedAt(index)
            styleAt(index).fold(original)(_.applyTo(original))
          }))
        case _ if group.size == 1 => styleAt(0).fold[Result[Grob]](Right(grob))(styled(grob, _))
        case x: Grob.Group        =>
          traverse(x.children)(route(_, group)).map(children => x.copy(children = children))
        case x: Grob.Annotated => route(x.child, group).map(child => x.copy(child = child))
        case _                 => Right(grob)
    def walk(grob: Grob): Result[Grob] = grob match
      case x: Grob.Annotated =>
        routeOf(x.meta)
          .fold(walk(x.child))(route(x.child, _))
          .map(child => x.copy(child = child))
      case x: Grob.Group =>
        traverse(x.children)(walk).map(children => x.copy(children = children))
      case _ => Right(grob)
    for
      _ <- validation
      grobs <- traverse(view.scene.grobs)(walk)
    yield Scene(grobs).withSemantics(view.scene.semantics)

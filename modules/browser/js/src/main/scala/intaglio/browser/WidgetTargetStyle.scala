package intaglio.browser

import intaglio.*
import intaglio.interaction.*

/** Paint supplied by an application for a logical target. None preserves the original channel.
  * Opacity replaces the mark opacity; it must be finite and within [0, 1]. Raster cells support
  * fill and opacity, but have no stroke channel. Cell opacity multiplies its intrinsic pixel alpha.
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
  private[browser] def applyTo(gp: GraphicParams): GraphicParams =
    gp.withAestheticOverrides(stroke = stroke, fill = fill, alpha = opacity).toOption.get

object WidgetTargetStyle:
  private[browser] def paint[A](
      view: SvgWidgetView[A],
      styles: Map[VisualTargetId, WidgetTargetStyle]
  ): Either[IntaglioError, Scene] =
    val groups = view.plans.flatMap(_.groups)
    def isRaster(group: TargetGroup[A]): Boolean =
      group.at(0).toOption.flatMap(_.rasterCell).nonEmpty
    def owns(group: TargetGroup[A], id: VisualTargetId): Boolean =
      val series = group.series
      id.plan == series.plan && id.revision == series.revision && id.scope == series.scope &&
      id.ordinal >= series.first && id.ordinal.toLong < series.first.toLong + series.size
    val validation = styles.toVector.foldLeft[Either[IntaglioError, Unit]](Right(())) {
      case (result, (id, style)) =>
        result.flatMap { _ =>
          groups.find(owns(_, id)) match
            case None =>
              Left(InteractionError.InvalidValue("target style", "target is not in this view"))
            case Some(group) if isRaster(group) && style.stroke.nonEmpty =>
              Left(InteractionError.UnsupportedCapability("raster cell stroke styles"))
            case Some(_) => style.validate
        }
    }
    validation.map { _ =>
      val byRoute = groups.map(group => group.name.value -> group).toMap
      def styled(grob: Grob, style: WidgetTargetStyle): Grob = grob match
        case x: Grob.Points          => x.copy(gp = style.applyTo(x.gp))
        case x: Grob.PointBatch      => x.copy(graphicParams = x.graphicParams.map(style.applyTo))
        case x: Grob.Lines           => x.copy(gp = style.applyTo(x.gp))
        case x: Grob.Polygon         => x.copy(gp = style.applyTo(x.gp))
        case x: Grob.CompoundPolygon => x.copy(gp = style.applyTo(x.gp))
        case x: Grob.Segments        => x.copy(gp = style.applyTo(x.gp))
        case x: Grob.Rect            => x.copy(gp = style.applyTo(x.gp))
        case x: Grob.Circle          => x.copy(gp = style.applyTo(x.gp))
        case x: Grob.Text            => x.copy(gp = style.applyTo(x.gp))
        case x: Grob.Image           => x.copy(alpha = style.opacity.getOrElse(x.alpha))
        case x: Grob.Group           => x.copy(children = x.children.map(styled(_, style)))
        case x: Grob.Annotated       => x.copy(child = styled(x.child, style))
      def route(grob: Grob, group: TargetGroup[A]): Grob =
        def styleAt(index: Int) = group.series.at(index).toOption.flatMap(styles.get)
        grob match
          case x: Grob.PointBatch if group.size == x.points.size =>
            x.copy(graphicParams = BatchColumn.compact(x.points.indices.map { i =>
              styleAt(i).fold(x.graphicParams.valueAt(i))(_.applyTo(x.graphicParams.valueAt(i)))
            }.toVector))
          case x: Grob.Image if isRaster(group) =>
            x.copy(image = RasterImage.tabulate(x.image.dimensions) { (column, row) =>
              val original = x.image.pixel(column, row).toOption.get
              styleAt(row * x.image.width + column).fold(original) { style =>
                val color = style.fill.fold(original)(Rgba32.fromRgba)
                Rgba32.unsafe(
                  color.red,
                  color.green,
                  color.blue,
                  math.round(color.alpha * style.opacity.getOrElse(1.0)).toInt
                )
              }
            })
          case _ if group.size == 1 => styleAt(0).fold(grob)(styled(grob, _))
          case x: Grob.Group        => x.copy(children = x.children.map(route(_, group)))
          case x: Grob.Annotated    => x.copy(child = route(x.child, group))
          case _                    => grob
      def walk(grob: Grob): Grob = grob match
        case x: Grob.Annotated =>
          val group = x.meta.data
            .collectFirst {
              case (key, value) if key == InteractionCompiler.targetAttribute => value
            }
            .flatMap(byRoute.get)
          x.copy(child = group.fold(walk(x.child))(route(x.child, _)))
        case x: Grob.Group => x.copy(children = x.children.map(walk))
        case _             => grob
      Scene(view.scene.grobs.map(walk)).withSemantics(view.scene.semantics)
    }

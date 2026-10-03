package intaglio.browser

import intaglio.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.util.control.NonFatal

/** A PNG export failure is a value, including asynchronous image decoding and canvas failures. */
enum WidgetExportError extends IntaglioError:
  case InvalidSize(reason: String)
  case Unavailable(reason: String)
  case Failed(reason: String)

  def message: String = this match
    case InvalidSize(reason) => s"Invalid PNG size: $reason"
    case Unavailable(reason) => s"PNG export unavailable: $reason"
    case Failed(reason)      => s"PNG export failed: $reason"

/** Exports an explicit view, independently of host effects and transient hover/focus state. Supply
  * the original compiled view or a windowed view deliberately. Selection is opt-in and painted as
  * outline rings; it does not modify the supplied view or emit interaction events.
  */
object SvgWidgetExport:
  private val MaximumPixels = 16777216L

  private[browser] def dimensions(
      width: Int,
      height: Int,
      scale: Double
  ): Either[WidgetExportError, (Int, Int)] =
    val w = math.ceil(width.toDouble * scale)
    val h = math.ceil(height.toDouble * scale)
    if !scale.isFinite || scale <= 0 || w < 1 || h < 1 ||
      w > 16384 || h > 16384 || w * h > MaximumPixels
    then
      Left(
        WidgetExportError.InvalidSize(
          "use a positive finite scale, at most 16384 per axis and 16777216 pixels"
        )
      )
    else Right((w.toInt, h.toInt))

  /** A self-contained SVG snapshot. Selected marks outside this view simply have no ring. */
  def svg[A](view: SvgWidgetView[A], selection: Selection[A] = Selection[A]()): String =
    val rings = view.navigation.targets.flatMap { geometry =>
      val target = geometry.target
      if selection.targets.contains(target.id) || target.entity.exists(selection.entities.contains)
      then
        view.picking.outline(target.id, 2.0).toOption.map { outline =>
          val d = SvgWidget.pathData(outline)
          s"""<path data-export-selection="true" d="$d" fill="none" stroke="#b45309" stroke-width="2.5"/>"""
        }
      else None
    }.mkString
    val end = view.markup.lastIndexOf("</svg>")
    view.markup.substring(0, end) + rings + view.markup.substring(end)

  /** Returns a PNG data URL, or an actionable error. Temporary URLs and timers are released on
    * every completion path. The caller decides whether to download, display, or persist it.
    */
  def png[A](
      view: SvgWidgetView[A],
      selection: Selection[A] = Selection[A](),
      scale: Double = 1.0
  ): js.Promise[Either[WidgetExportError, String]] =
    dimensions(view.width, view.height, scale) match
      case Left(error)            => js.Promise.resolve(Left(error))
      case Right((width, height)) =>
        new js.Promise[Either[WidgetExportError, String]]((resolve, _) =>
          var url: Option[String] = None
          var timer: Option[js.Dynamic] = None
          var image: Option[js.Dynamic] = None
          var complete = false
          def finish(result: Either[WidgetExportError, String]): Unit =
            if !complete then
              complete = true
              timer.foreach(handle => g.clearTimeout(handle))
              image.foreach { img =>
                img.onload = null
                img.onerror = null
              }
              url.foreach(value => g.URL.revokeObjectURL(value))
              resolve(result)
          try
            if js.typeOf(g.document) == "undefined" || js.typeOf(g.Image) == "undefined" then
              finish(
                Left(
                  WidgetExportError.Unavailable("a browser document and image decoder are required")
                )
              )
            else
              val canvas = g.document.createElement("canvas")
              canvas.width = width
              canvas.height = height
              val context = canvas.getContext("2d")
              if context == null then
                finish(Left(WidgetExportError.Unavailable("this browser has no 2D canvas context")))
              else
                val blob = js.Dynamic.newInstance(g.Blob)(
                  js.Array(svg(view, selection)),
                  js.Dynamic.literal(`type` = "image/svg+xml;charset=utf-8")
                )
                val value = g.URL.createObjectURL(blob).asInstanceOf[String]
                url = Some(value)
                val img = js.Dynamic.newInstance(g.Image)()
                image = Some(img)
                img.onload = (
                    () =>
                      try
                        context.fillStyle = "#ffffff"
                        context.fillRect(0, 0, width, height)
                        context.drawImage(img, 0, 0, width, height)
                        val data = canvas.toDataURL("image/png").asInstanceOf[String]
                        if data.startsWith("data:image/png;base64,") then finish(Right(data))
                        else
                          finish(
                            Left(WidgetExportError.Failed("the canvas encoder returned no PNG"))
                          )
                      catch
                        case NonFatal(error) =>
                          finish(
                            Left(
                              WidgetExportError.Failed(
                                s"${error.getMessage}; embed all image/font assets before export"
                              )
                            )
                          )
                ): js.Function0[Unit]
                img.onerror = (
                    () =>
                      finish(
                        Left(
                          WidgetExportError.Failed(
                            "SVG could not be decoded; embed image/font assets"
                          )
                        )
                      )
                ): js.Function0[Unit]
                val timeout: js.Function0[Unit] = () =>
                  finish(
                    Left(WidgetExportError.Failed("image decoding timed out after 10 seconds"))
                  )
                timer = Some(g.setTimeout(timeout, 10000))
                img.src = value
          catch case NonFatal(error) => finish(Left(WidgetExportError.Failed(error.getMessage)))
        )

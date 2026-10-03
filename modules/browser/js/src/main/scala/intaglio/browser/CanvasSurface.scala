package intaglio.browser

import intaglio.*
import intaglio.canvas.*
import intaglio.interaction.*
import intaglio.svg.SvgFonts
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.typedarray.Uint8Array
import scala.util.control.NonFatal

/** Native Canvas paint only. Input, identities, state and accessible navigation stay in the host.
  */
private[browser] final class CanvasSurface[A](
    report: IntaglioError => Unit,
    ready: () => Unit
):
  private val document = g.document
  val base: js.Dynamic = document.createElement("canvas")
  val emphasis: js.Dynamic = document.createElement("canvas")
  base.className = "intaglio-base"
  base.setAttribute("aria-hidden", "true")
  emphasis.className = "intaglio-canvas-emphasis"
  emphasis.setAttribute("aria-hidden", "true")
  private var view: Option[SvgWidgetView[A]] = None
  private var program: Option[CanvasProgram] = None
  private var cache = CanvasRasterCache.empty(64)
  private var fonts = SvgFonts.empty
  private var loadedFaces = Vector.empty[js.Dynamic]
  private var fontGeneration = 0
  private var pendingFonts = 0
  private var fontFailed = false
  private var disposed = false

  def update(next: SvgWidgetView[A]): Unit =
    view = Some(next)
    program = CanvasRenderer.compile(RenderPlan(next.scene, next.context)) match
      case Right(value) => Some(value)
      case Left(error)  => report(error); None
    base.style.aspectRatio = s"${next.width} / ${next.height}"
    loadFonts(next.fonts)
    resize(force = true)

  private def loadFonts(next: SvgFonts): Unit =
    if next != fonts then
      fontGeneration += 1
      val generation = fontGeneration
      loadedFaces.foreach(face => document.fonts.delete(face))
      loadedFaces = Vector.empty
      fonts = next
      pendingFonts = next.faces.size
      fontFailed = false
      next.faces.foreach { face =>
        try
          val bytes = face.bytes
          val buffer = new Uint8Array(bytes.length)
          bytes.indices.foreach(i => buffer(i) = (bytes(i) & 0xff).toShort)
          val native = js.Dynamic.newInstance(g.FontFace)(
            face.family,
            buffer.buffer,
            js.Dynamic.literal(weight = face.weight.value.toString)
          )
          loadedFaces = loadedFaces :+ native
          document.fonts.add(native)
          native
            .load()
            .asInstanceOf[js.Promise[js.Any]]
            .`then`[Unit](
              (_: js.Any) =>
                if !disposed && generation == fontGeneration then
                  pendingFonts -= 1
                  if pendingFonts == 0 then
                    resize(force = true)
                    ready(),
              (error: Any) =>
                if !disposed && generation == fontGeneration then
                  fontFailed = true
                  pendingFonts -= 1
                  base.setAttribute("data-render-state", "error")
                  report(
                    InteractionError.UnsupportedCapability(s"Canvas font '${face.family}': $error")
                  )
            )
        catch
          case NonFatal(error) =>
            fontFailed = true
            pendingFonts -= 1
            base.setAttribute("data-render-state", "error")
            report(InteractionError.UnsupportedCapability(s"Canvas fonts: ${error.getMessage}"))
      }

  /** Backing pixels follow the CSS box and DPR, while all plans stay in their original coordinates.
    */
  def resize(force: Boolean = false): Unit =
    if !disposed then
      view.foreach { current =>
        val box = base.getBoundingClientRect()
        val cssWidth = box.width.asInstanceOf[Double]
        val ratio = g.window.devicePixelRatio.asInstanceOf[Double]
        val scale = (if cssWidth > 0 then cssWidth / current.width else 1.0) * ratio
        SvgWidgetExport.dimensions(current.width, current.height, scale) match
          case Left(error)            => report(error)
          case Right((width, height)) =>
            val changed =
              base.width.asInstanceOf[Int] != width || base.height.asInstanceOf[Int] != height
            if changed then
              base.width = width
              base.height = height
              emphasis.width = width
              emphasis.height = height
            if (changed || force) && pendingFonts == 0 && !fontFailed then
              val context = base.getContext("2d")
              context.setTransform(1, 0, 0, 1, 0, 0)
              context.clearRect(0, 0, width, height)
              context.setTransform(
                width.toDouble / current.width,
                0,
                0,
                height.toDouble / current.height,
                0,
                0
              )
              program.foreach { value =>
                CanvasRenderer.drawCachedChecked(
                  value,
                  context.asInstanceOf[CanvasRenderingContext2D],
                  cache
                )(using CanvasRasterFactory.browser) match
                  case Left(error) => report(error); base.setAttribute("data-render-state", "error")
                  case Right(_)    => base.setAttribute("data-render-state", "ready")
              }
            else if pendingFonts > 0 || fontFailed then
              val context = base.getContext("2d")
              context.setTransform(1, 0, 0, 1, 0, 0)
              context.clearRect(0, 0, width, height)
              base.setAttribute("data-render-state", if fontFailed then "error" else "loading")
      }

  def clearEmphasis(): Unit =
    val context = emphasis.getContext("2d")
    context.setTransform(1, 0, 0, 1, 0, 0)
    context.clearRect(0, 0, emphasis.width, emphasis.height)

  /** Reuse the already painted bitmap through exact target outlines; no SVG marks enter the DOM. */
  def emphasize(outlines: Vector[TargetOutline]): Unit =
    view.foreach { current =>
      val context = emphasis.getContext("2d")
      context.save()
      try
        context.setTransform(
          emphasis.width.asInstanceOf[Double] / current.width,
          0,
          0,
          emphasis.height.asInstanceOf[Double] / current.height,
          0,
          0
        )
        context.beginPath()
        outlines.flatMap(_.rings).filter(_.nonEmpty).foreach { ring =>
          context.moveTo(ring.head.x, ring.head.y)
          ring.tail.foreach(p => context.lineTo(p.x, p.y))
          context.closePath()
        }
        context.clip()
        context.drawImage(base, 0, 0, current.width, current.height)
      finally context.restore()
    }

  def dispose(): Unit =
    disposed = true
    fontGeneration += 1
    loadedFaces.foreach(face => document.fonts.delete(face))
    loadedFaces = Vector.empty
    fonts = SvgFonts.empty
    pendingFonts = 0
    program = None
    view = None
    cache = CanvasRasterCache.empty(0)
    base.width = 0
    base.height = 0
    emphasis.width = 0
    emphasis.height = 0

private[browser] object CanvasSurface:
  def available: Either[IntaglioError, Unit] =
    try
      val probe = g.document.createElement("canvas")
      val supported = probe.getContext("2d") != null
      probe.width = 0
      probe.height = 0
      Either.cond(supported, (), InteractionError.UnsupportedCapability("Canvas 2D rendering"))
    catch
      case NonFatal(error) =>
        Left(InteractionError.UnsupportedCapability(s"Canvas: ${error.getMessage}"))

package intaglio.browserfixture

import intaglio.*
import intaglio.browser.*
import intaglio.interaction.*
import intaglio.svg.{SvgFonts, SvgFontFace}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.annotation.JSExportTopLevel

object ExportFixture:
  private def ok[A](value: Either[IntaglioError, A]): A =
    value.fold(e => throw new IllegalArgumentException(e.message), identity)

  @JSExportTopLevel("intaglioExportFixture")
  def run(): js.Dynamic = build(SvgFonts.empty)

  @JSExportTopLevel("intaglioExportFontFixture")
  def font(bytes: js.Array[Int]): js.Dynamic =
    val face = ok(SvgFontFace("Intaglio Export Font", bytes.toVector.map(_.toByte).toArray))
    build(ok(SvgFonts(face)))

  private def build(fonts: SvgFonts): js.Dynamic =
    val context = RenderContext.unsafe(400, 300)
    val keys = ok(KeySpace("export-fixture", KeyCodec.text))
    val plan = ok(
      InteractionCompiler.compile(
        ok(
          plot(Vector(("one", 1.0, 2.0), ("two", 2.0, 4.0), ("three", 3.0, 3.0)))
            .aes(_._2, _._3)
            .geomPoint()
            .title("Export α")
            .build
        ).plot,
        keys,
        ok(DataRevision("data")),
        SemanticId.unsafe("export"),
        ok(PlanRevision("plan")),
        PlotCompilerOptions(
          renderContext = Some(context),
          guides = GuidePolicy.Derived(),
          theme = Theme.minimal.copy(plotText =
            Theme.minimal.plotText.copy(title =
              GraphicParams.unsafe(
                stroke = None,
                fill = Some(Rgba.Black),
                fontSize = Length.pointsUnsafe(16),
                fontFamily = Some("Intaglio Export Font")
              )
            )
          )
        )
      )(_._1)
    )
    val original = ok(SvgWidgetView.compile(plan, context, "export-original", fonts = fonts))
    val navigator = ok(DataWindowNavigator.of(plan, context))
    val windows = ok(navigator.windows(PanelWindow(Some((1.5, 3.0)), None)))
    val zoomed = ok(InteractionCompiler.rezoom(plan, windows._1, windows._2))
    val current = ok(SvgWidgetView.compile(zoomed, context, "export-current", fonts = fonts))
    val selected = Selection(Set(ok(keys.entity("two"))))
    val container = g.document.createElement("div")
    container.id = "export-controls"
    g.document.body.appendChild(container)
    val widget = ok(
      SvgWidget.mount(
        container,
        original,
        options = WidgetOptions(
          controls = Set(WidgetControl.Reset, WidgetControl.Fullscreen, WidgetControl.Download),
          toolbarPosition = ToolbarPosition.Bottom,
          sizing = WidgetSizing.Fixed(400)
        )
      )
    )
    var eventCount = 0
    widget.subscribe(_ => eventCount += 1)
    var variants = Vector.empty[SvgWidget[String]]
    def variant(position: String, visibility: String, width: Int): String =
      val prefix = s"variant-${variants.size}"
      val holder = g.document.createElement("div")
      holder.style.width = "240px"
      g.document.body.appendChild(holder)
      val variantView = ok(SvgWidgetView.compile(plan, context, prefix, fonts = fonts))
      val mounted = ok(
        SvgWidget.mount(
          holder,
          variantView,
          options = WidgetOptions(
            controls = Set(WidgetControl.Reset),
            toolbarPosition = ToolbarPosition.valueOf(position),
            toolbarVisibility = ToolbarVisibility.valueOf(visibility),
            sizing = if width == 0 then WidgetSizing.Responsive else WidgetSizing.Fixed(width)
          )
        )
      )
      variants = variants :+ mounted
      prefix
    js.Dynamic.literal(
      events = () => eventCount,
      variant =
        (position: String, visibility: String, width: Int) => variant(position, visibility, width),
      select = () => widget.setSelection(selected).fold(_.message, _ => "ok"),
      navigate =
        () => widget.navigate(PanelWindow(Some((1.5, 3.0)), None)).fold(_.message, _ => "ok"),
      widgetPNG = (viewport: String, selection: String) =>
        widget
          .exportPng(ExportViewport.valueOf(viewport), ExportSelection.valueOf(selection))
          .`then`[String]((value: Either[WidgetExportError, String]) =>
            value.fold(e => "ERROR: " + e.message, identity)
          ),
      fullscreen = () =>
        widget
          .toggleFullscreen()
          .`then`[String]((value: Either[IntaglioError, Unit]) => value.fold(_.message, _ => "ok")),
      dispose = () => { widget.dispose(); variants.foreach(_.dispose()); container.remove() },
      png = (window: String, withSelection: Boolean, scale: Double) =>
        SvgWidgetExport
          .png(
            if window == "original" then original else current,
            if withSelection then selected else Selection[String](),
            scale
          )
          .`then`[String]((value: Either[WidgetExportError, String]) =>
            value.fold(e => "ERROR: " + e.message, identity)
          ),
      svg = (window: String, withSelection: Boolean) =>
        SvgWidgetExport.svg(
          if window == "original" then original else current,
          if withSelection then selected else Selection[String]()
        )
    )

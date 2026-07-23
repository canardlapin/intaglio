package intaglio.canvas

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import intaglio.*

/** Test-only real-browser gallery entry point. Link the test configuration as
  * a NoModule script and call `renderScalafimCanvasGallery()` from an HTML page.
  */
object BrowserGallery:
  @JSExportTopLevel("renderScalafimCanvasGallery")
  def render(): Unit =
    val document = js.Dynamic.global.document
    val root = document.createElement("main")
    root.style.display = "grid"
    root.style.gridTemplateColumns = "repeat(2, 640px)"
    root.style.gap = "24px"
    root.style.padding = "24px"
    document.body.appendChild(root)

    val options = CanvasOptions.unsafe(width = 640, height = 480)
    val cases = RendererConformance.cases.fold(error => throw new IllegalArgumentException(error.message), identity)
    cases.foreach { conformanceCase =>
      val section = document.createElement("section")
      val title = document.createElement("h2")
      title.textContent = s"${conformanceCase.group}: ${conformanceCase.name.value}"
      title.style.font = "16px sans-serif"
      val canvas = document.createElement("canvas")
      canvas.width = options.width
      canvas.height = options.height
      canvas.style.border = "1px solid #d9dde3"
      canvas.style.background = "white"
      val context = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
      CanvasRenderer
        .render(conformanceCase.scene, context, options)
        .fold(error => throw new IllegalArgumentException(error.message), identity)
      section.appendChild(title)
      section.appendChild(canvas)
      root.appendChild(section)
    }
    document.body.dataset.intaglioGallery = "ready"

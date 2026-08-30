package intaglio.svg

import java.awt.Color
import java.io.{ByteArrayInputStream, StringReader}
import java.util.Base64
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import intaglio.*

class SvgXmlSuite extends munit.FunSuite:

  test("every renderer conformance document parses as XML") {
    val options = SvgOptions.unsafe(width = 240, height = 160, title = Some("intaglio & SVG"))
    val cases = RendererConformance.cases.fold(error => fail(error.message), identity)

    cases.foreach { conformanceCase =>
      val document = SvgRenderer
        .render(conformanceCase.scene, options)
        .fold(error => fail(error.message), identity)
      val parsed = parse(document.value)
      assertEquals(parsed.getDocumentElement.getLocalName, "svg")
    }
  }

  test("pattern resources parse as XML without semantic mark names") {
    val recipe =
      PatternRecipe.crossHatch(35.0, 8.0, 1.25).fold(error => fail(error.message), identity)
    val rect = Grob.rectUnsafe(
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.5, 0.5),
      gp = GraphicParams
        .unsafe(stroke = None)
        .withPatternFill(PatternPaint(recipe, Rgba.unsafe(20, 40, 60), Some(Rgba.White))),
      name = Some(GraphicsName.unsafe("patterned-mark"))
    )
    val document = SvgRenderer
      .render(Scene(Vector(rect)), SvgOptions.unsafe(width = 100, height = 80))
      .fold(error => fail(error.message), identity)
    val parsed = parse(document.value)
    val pattern = parsed.getElementsByTagName("pattern").item(0)
    val mark = parsed.getElementsByTagName("rect").item(0)

    assertEquals(pattern.getAttributes.getNamedItem("id").getNodeValue, "pattern-0")
    assertEquals(pattern.getAttributes.getNamedItem("patternUnits").getNodeValue, "userSpaceOnUse")
    assertEquals(pattern.getAttributes.getNamedItem("data-name"), null)
    assertEquals(mark.getAttributes.getNamedItem("data-name").getNodeValue, "patterned-mark")
    assertEquals(mark.getAttributes.getNamedItem("fill").getNodeValue, "url(#pattern-0)")
  }

  test("escaped text, attributes, and supplementary Unicode parse losslessly") {
    val name = GraphicsName.unsafe("name & <group> \"quoted\"")
    val text = Grob
      .text(
        "activation & <signal> \ud83e\udde0",
        Point.npcUnsafe(0.5, 0.5),
        gp = GraphicParams.unsafe(fontFamily = Some("A&B \"Sans\"")),
        name = Some(name)
      )
      .toOption
      .get
    val parsed = parse(
      SvgRenderer.render(Scene(Vector(text))).fold(error => fail(error.message), identity).value
    )
    val element = parsed.getElementsByTagName("text").item(0)

    assertEquals(element.getTextContent, "activation & <signal> \ud83e\udde0")
    assertEquals(element.getAttributes.getNamedItem("data-name").getNodeValue, name.value)
    assertEquals(element.getAttributes.getNamedItem("font-family").getNodeValue, "A&B \"Sans\"")
  }

  test("embedded PNG pixels preserve top-left row order and alpha") {
    val scene = RendererConformance.imageCase.fold(error => fail(error.message), identity).scene
    val svg = SvgRenderer
      .render(scene, SvgOptions.unsafe(width = 100, height = 80))
      .fold(error => fail(error.message), identity)
    val element = parse(svg.value).getElementsByTagName("image").item(0)
    val uri = element.getAttributes.getNamedItem("href").getNodeValue
    val bytes = Base64.getDecoder.decode(uri.stripPrefix("data:image/png;base64,"))
    val image = ImageIO.read(new ByteArrayInputStream(bytes))

    assertEquals(image.getWidth, 2)
    assertEquals(image.getHeight, 2)
    assertEquals(new Color(image.getRGB(0, 0), true), new Color(220, 30, 30, 255))
    assertEquals(new Color(image.getRGB(1, 0), true), new Color(30, 200, 60, 160))
    assertEquals(new Color(image.getRGB(0, 1), true), new Color(40, 80, 220, 255))
    assertEquals(new Color(image.getRGB(1, 1), true).getAlpha, 0)
  }

  private def parse(value: String): org.w3c.dom.Document =
    val factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    factory.newDocumentBuilder().parse(new InputSource(new StringReader(value)))

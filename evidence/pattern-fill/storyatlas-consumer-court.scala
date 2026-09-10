package storyatlas4s.intaglio

import _root_.intaglio as ig
import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import _root_.intaglio.value
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog
import storymodel4s.story.*
import storymodel4s.view.*

/** Pinned consumer court for Intaglio's four reusable pattern recipes.
  *
  * The interaction-state mapping remains StoryAtlas-owned. Intaglio sees only typed paint recipes,
  * and the decoration is applied after the real Atlas lowering so coordinates and semantic groups
  * can be compared directly with the undecorated scene.
  */
class PatternFillConsumerCourtSuite extends FunSuite:
  private enum InteractionState:
    case Selected, Focused, SelectionProxy, FocusProxy

  private val model = Wog.model
  private val discourseLength = model.source.canonicalText.length

  private def ok[E, A](either: Either[E, A]): A =
    either.fold(error => fail(s"unexpected failure: $error"), identity)

  private def compiledScene: NarrativeScene =
    val state = ok(
      CommonViewState.of(relationLayers = Set(RelationLayer.Causal, RelationLayer.Reference))
    )
    val spec = AtlasSpec(
      ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Tokens),
      ThreadPolicy.All(PositiveInt.unsafe(3))
    )
    val provenance = ok(
      ViewProvenance.fixture(
        model.source.canonicalChecksum,
        "pattern-fill-consumer-court",
        AtlasCompiler.configurationChecksum(state, spec)
      )
    )
    ok(AtlasCompiler(provenance).compile(model, state, spec))

  private def patternAssignments(
      scene: NarrativeScene
  ): Vector[(InteractionState, VisualPrimitive.Region, ig.PatternPaint)] =
    val regions = scene.marks.collect { case region: VisualPrimitive.Region => region }.take(4)
    assertEquals(regions.length, 4, "the real fixture must expose four fill-bearing regions")
    val recipes = Vector[ig.PatternRecipe](
      ok(ig.PatternRecipe.angledHatch(30.0, 10.0, 1.25)),
      ok(ig.PatternRecipe.crossHatch(45.0, 10.0, 1.25)),
      ok(ig.PatternRecipe.stipple(10.0, 2.0)),
      ok(ig.PatternRecipe.parallelRules(ig.RuleOrientation.Horizontal, 10.0, 1.25))
    )
    val states = Vector(
      InteractionState.Selected,
      InteractionState.Focused,
      InteractionState.SelectionProxy,
      InteractionState.FocusProxy
    )
    states.zip(regions).zip(recipes).map { case ((state, region), recipe) =>
      (state, region, ig.PatternPaint(recipe, ig.Rgba.Black, Some(ig.Rgba.White)))
    }

  /** StoryAtlas-owned decoration: preserve every lowered grob and replace only the fill channel of
    * the region polygon in a named semantic group.
    */
  private def decorate(
      scene: ig.Scene,
      paintsByName: Map[String, ig.PatternPaint]
  ): ig.Scene =
    def loop(grob: ig.Grob): ig.Grob = grob match
      case group: ig.Grob.Group =>
        group.name.flatMap(name => paintsByName.get(name.value)) match
          case Some(paint) =>
            val children = group.children.map {
              case polygon: ig.Grob.Polygon =>
                ig.Grob.polygonUnsafe(
                  polygon.points,
                  gp = polygon.gp.withPatternFill(paint),
                  viewport = polygon.viewport,
                  name = polygon.name
                )
              case child => loop(child)
            }
            ig.Grob.group(children, viewport = group.viewport, name = group.name)
          case None =>
            ig.Grob.group(group.children.map(loop), viewport = group.viewport, name = group.name)
      case other => other

    ig.Scene(scene.grobs.map(loop))

  private def namedChildren(
      elements: Vector[ig.DeviceElement],
      wanted: String
  ): Option[Vector[ig.DeviceElement]] =
    elements.iterator
      .map {
        case ig.DeviceElement.Group(Some(name), _, _, children) if name.value == wanted =>
          Some(children)
        case ig.DeviceElement.Group(_, _, _, children) => namedChildren(children, wanted)
        case _: ig.DeviceElement.Mark                  => None
      }
      .collectFirst { case Some(children) => children }

  private def namedPolygon(scene: ig.DeviceScene, wanted: String): Vector[ig.DevicePoint] =
    namedChildren(scene.elements, wanted)
      .flatMap(
        _.collectFirst {
          case ig.DeviceElement.Mark(ig.DevicePrimitive.Polyline(points, true, _, _)) => points
          case ig.DeviceElement.Mark(ig.DevicePrimitive.CompoundPolygon(rings, _, _)) =>
            rings.flatten
        }
      )
      .getOrElse(fail(s"no closed polygon for $wanted"))

  private def render(scene: ig.Scene): String =
    ok(SvgRenderer.render(scene, ok(SvgOptions(1200, 400)))).value

  private def occurrences(value: String, needle: String): Int =
    var count = 0
    var offset = value.indexOf(needle)
    while offset >= 0 do
      count += 1
      offset = value.indexOf(needle, offset + needle.length)
    count

  private def attributeValues(value: String, prefix: String): Vector[String] =
    val out = Vector.newBuilder[String]
    var start = value.indexOf(prefix)
    while start >= 0 do
      val valueStart = start + prefix.length
      val end = value.indexOf('"', valueStart)
      if end >= 0 then out += value.substring(valueStart, end)
      start = value.indexOf(prefix, valueStart)
    out.result()

  test("all four interaction states use typed patterns without changing identity or receipts"):
    val source = compiledScene
    val assignments = patternAssignments(source)
    val original = ok(AtlasLowering.lower(source, discourseLength))
    val patterned = decorate(
      original,
      assignments.map { case (_, region, paint) => region.identity.mark.value -> paint }.toMap
    )
    val rendered = render(patterned)

    assertEquals(render(patterned), rendered, "the consumer rendering must be byte-deterministic")
    assertEquals(assignments.map(_._1).distinct.length, 4)
    assertEquals(assignments.map(_._3.recipe).distinct.length, 4)
    assertEquals(assignments.map(_._3.ink).distinct, Vector(ig.Rgba.Black))
    assertEquals(assignments.flatMap(_._3.background).distinct, Vector(ig.Rgba.White))
    assertEquals(occurrences(rendered, "<pattern id=\""), 4)
    assertEquals(occurrences(rendered, "fill=\"url(#pattern-"), 4)

    val patternElements = rendered.linesIterator.filter(_.trim.startsWith("<pattern ")).toVector
    assertEquals(patternElements.length, 4)
    assert(patternElements.forall(!_.contains("data-name=")))

    val names = attributeValues(rendered, "data-name=\"")
    val expectedNames = source.marks.map(_.identity.mark.value)
    assertEquals(names.sorted, expectedNames.sorted)
    assertEquals(names.distinct.length, names.length)

    val originalDevice = ok(
      ig.DeviceScene.fromScene(original, ig.DeviceContext.unsafe(1200.0, 400.0))
    )
    val patternedDevice = ok(
      ig.DeviceScene.fromScene(patterned, ig.DeviceContext.unsafe(1200.0, 400.0))
    )
    assignments.foreach { case (_, region, _) =>
      val id = region.identity.mark
      assertEquals(namedPolygon(patternedDevice, id.value), namedPolygon(originalDevice, id.value))
      assertEquals(source.navigation.addressOf.get(id), Some(region.address))
      assert(source.textualTwin.contains(id.value), s"textual twin lacks ${id.value}")
    }

    assert(rendered.contains(source.provenance.basis.label))
    assert(rendered.contains(source.provenance.sourceChecksum.hex))
    assert(rendered.contains(source.provenance.configChecksum.hex))

package intaglio

/** Derived legend geometry judged on the drawn device scene: key ink boxes from the resolved
  * primitives, label and title positions from their text runs.
  */
class LegendKeyGeometrySuite extends munit.FunSuite:
  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(x: Double, y: Double, condition: String)
  private val rows = Vector("baseline", "early", "late", "washout").zipWithIndex.map {
    (condition, i) => Obs(i.toDouble, i.toDouble * 0.5, condition)
  }

  final case class Box(left: Double, top: Double, right: Double, bottom: Double):
    def overlaps(that: Box): Boolean =
      left < that.right && that.left < right && top < that.bottom && that.top < bottom

  final case class Drawn(
      keys: Vector[Box],
      labels: Vector[DevicePrimitive.TextRun],
      title: Option[DevicePrimitive.TextRun],
      panel: DeviceClip
  )

  private def legend(plan: RenderPlan): Drawn =
    val keys = Vector.newBuilder[Box]
    val labels = Vector.newBuilder[DevicePrimitive.TextRun]
    var title = Option.empty[DevicePrimitive.TextRun]
    var panel = Option.empty[DeviceClip]
    def named(name: Option[GraphicsName], suffix: String) =
      name.exists(n => n.value.contains("legend") && n.value.endsWith(suffix))
    def walk(element: DeviceElement, inLegend: Boolean): Unit =
      element match
        case DeviceElement.Group(name, groupClip, _, children) =>
          if name.exists(_.value == "plot-panel") then panel = groupClip
          children.foreach(walk(_, inLegend))
        case DeviceElement.Annotated(_, children) => children.foreach(walk(_, inLegend))
        case DeviceElement.Mark(primitive)        =>
          primitive match
            case d: DevicePrimitive.Disc if named(d.name, "-key") =>
              val e = d.radius + d.gp.stroke.fold(0.0)(_ => d.gp.lineWidth / 2.0)
              keys += Box(d.centerX - e, d.centerY - e, d.centerX + e, d.centerY + e)
            case t: DevicePrimitive.TextRun if named(t.name, "-label") => labels += t
            case t: DevicePrimitive.TextRun if named(t.name, "-title") => title = Some(t)
            case _                                                     => ()
    ok(plan.deviceScene).elements.foreach(walk(_, inLegend = false))
    Drawn(keys.result(), labels.result(), title, panel.getOrElse(fail("missing plot panel clip")))

  private def program(policy: LayoutPolicy) =
    plot(rows)
      .aes(_.x, _.y)
      .scaleColorDiscrete(_.condition, name = "condition")
      .geomPoint()
      .compilerOptions(
        PlotCompilerOptions(guides = GuidePolicy.Derived(), policy = Some(policy))
      )

  private def check(policy: LayoutPolicy, context: RenderContext): Drawn =
    val drawn = legend(ok(program(policy).renderPlan(context)))
    val where = clue((policy.legendKeyPt, policy.legendFontPt, context.pixelsPerInch))
    assertEquals(drawn.keys.length, rows.length, where)
    assertEquals(drawn.labels.length, rows.length, where)
    // Keys never overlap one another.
    drawn.keys.sliding(2).foreach { pair =>
      assert(!pair(0).overlaps(pair(1)), clue((where, pair)))
    }
    // Each key lies wholly beside the panel, ends before its label begins, and sits on its
    // label's row.
    drawn.keys.zip(drawn.labels).foreach { (key, label) =>
      assert(key.left > drawn.panel.x + drawn.panel.width, clue((where, key, drawn.panel)))
      assert(key.right < label.x, clue((where, key, label.x)))
      assertEqualsDouble((key.top + key.bottom) / 2.0, label.y, 1e-6, where)
    }
    // The title's em box ends above the first key.
    drawn.title.foreach { title =>
      assert(title.y + title.fontSizePx <= drawn.keys.head.top + 1e-6, clue((where, title)))
    }
    drawn

  test(
    "default legend keys fit their key box and rows, clear their labels, and stay off the panel"
  ) {
    val context = RenderContext.unsafe(width = 640, height = 480)
    val drawn = check(LayoutPolicy(), context)
    // A 10pt key box at 96 ppi is 13.33 px: the whole key, stroke included, lies within it.
    drawn.keys.foreach { key =>
      assert(key.right - key.left <= 10.0 * 96.0 / 72.0 + 1e-9, clue(key))
    }
  }

  test("legend keys stay separate across key sizes, text sizes, and densities") {
    for
      keyPt <- Vector(6.0, 10.0, 16.0)
      fontPt <- Vector(8.0, 10.0, 14.0)
      context <- Vector(
        RenderContext.unsafe(width = 640, height = 480),
        RenderContext.unsafe(width = 480, height = 300),
        RenderContext.unsafe(width = 1280, height = 960, pixelsPerInch = 192.0)
      )
    do check(LayoutPolicy(legendKeyPt = keyPt, legendFontPt = fontPt), context)
  }
